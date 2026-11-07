package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.data.VezunaRepository
import app.vezuna.ui.nav.NativeAction
import app.vezuna.ui.nav.NativeActions
import app.vezuna.ui.today.requestOnboardingOption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileAge
import app.vezuna.llm.InstalledModel
import app.vezuna.llm.InstalledModelManager
import app.vezuna.llm.LlmGenerationSettings
import app.vezuna.llm.LocalHealthChat
import app.vezuna.llm.LocalLlm
import app.vezuna.llm.ModelCatalog
import app.vezuna.llm.ModelFileStore
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.ui.AppController
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileOnboardingTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")

    private fun controller(repository: VezunaRepository) =
        AppController(repository, InstalledModelManager(ModelCatalog.unconfigured(), NoModels, NoLlm), LocalHealthChat(repository, NoLlm), clock = { now }, timeZone = { TimeZone.UTC })

    @Test
    fun relationshipIsOptionalAndIsolatedAcrossProfiles() = runTest {
        val repository = InMemoryVezunaRepository()
        val c = controller(repository)
        c.initialize()
        c.completeOnboarding("Ana", LocalDate(1990, 5, 4), relationship = " Parent ")
        val first = repository.profiles.value.single()
        assertEquals("Parent", first.relationship)
        c.createProfile("Bia", " Child ")
        val second = repository.profiles.value.first { it.profileId != first.profileId }
        assertEquals("Child", second.relationship)
        c.setRelationship(second.profileId, "  ")
        assertNull(repository.readProfile(second.profileId).profile.relationship)
        assertEquals("Parent", repository.readProfile(first.profileId).profile.relationship)
        assertEquals(LocalDate(1990, 5, 4), repository.readProfile(first.profileId).profile.birthDate)
    }

    @Test
    fun ageCountsCompletedCalendarYears() {
        val today = LocalDate(2026, 11, 1)
        assertEquals(36, ProfileAge.years(LocalDate(1990, 11, 1), today))
        assertEquals(35, ProfileAge.years(LocalDate(1990, 11, 2), today))
        assertEquals(0, ProfileAge.years(today, today))
        assertNull(ProfileAge.years(LocalDate(2026, 11, 2), today))
        assertNull(ProfileAge.years(null, today))
        assertFalse(ProfileAge.isValid(LocalDate(1890, 1, 1), today))
    }

    @Test
    fun freshInstallGetsOnboardingAndExistingUserDoesNot() = runTest {
        val fresh = InMemoryVezunaRepository()
        val c = controller(fresh)
        c.initialize()
        assertTrue(c.state.value.onboarding)
        c.completeOnboarding(" Ana ", LocalDate(1990, 5, 4))
        assertFalse(c.state.value.onboarding)
        val saved = fresh.profiles.value.single()
        assertEquals("Ana", saved.displayName)
        assertEquals(LocalDate(1990, 5, 4), saved.birthDate)

        val existing = InMemoryVezunaRepository().also { it.createProfile(Profile("p", "Me", createdAt = now)) }
        val c2 = controller(existing)
        c2.initialize()
        assertFalse(c2.state.value.onboarding)
        assertEquals("Me", existing.profiles.value.single().displayName)
    }

    @Test
    fun profileCanBeSavedBeforeOptionalStepsAndSkippedWithoutLosingIt() = runTest {
        val repository = InMemoryVezunaRepository()
        val c = controller(repository)
        c.initialize()
        c.completeOnboarding("Ana", null, finish = false)
        assertTrue(c.state.value.onboarding)
        assertEquals("Ana", repository.profiles.value.single().displayName)
        c.skipOnboarding()
        assertFalse(c.state.value.onboarding)
        assertEquals("Ana", repository.profiles.value.single().displayName)
    }

    @Test
    fun skippedOptionsDoNotLinkDeviceHealth() = runTest {
        val repository = InMemoryVezunaRepository()
        val c = controller(repository)
        c.initialize()
        c.completeOnboarding("Ana", null, finish = false)
        assertNull(repository.explicitHealthLinkId())
        c.skipOnboarding()
        assertFalse(c.state.value.onboarding)
        assertNull(repository.explicitHealthLinkId())
        assertEquals("Ana", repository.profiles.value.single().displayName)
        assertTrue(repository.readProfile(repository.profiles.value.single().profileId).vitals.isEmpty)
    }

    @Test
    fun deviceHealthCanLinkSavedCurrentProfile() = runTest {
        val repository = InMemoryVezunaRepository()
        val c = controller(repository)
        c.initialize()
        c.completeOnboarding("Ana", null, finish = false)
        val id = c.state.value.data!!.profile.profileId
        var requestedProfile: String? = null
        requestOnboardingOption(c, NativeActions(setOf(NativeAction.HEALTH), performHealth = {
            requestedProfile = it.profileId
        }), NativeAction.HEALTH, id)
        assertEquals(id, requestedProfile)
        assertEquals(id, repository.explicitHealthLinkId())
        assertEquals(id, c.state.value.healthLinkedProfileId)
        c.skipOnboarding()
        assertFalse(c.state.value.onboarding)
    }

    @Test
    fun unavailableOptionsAndDeniedHealthNeverBlockCompletion() = runTest {
        val repository = InMemoryVezunaRepository()
        val c = controller(repository)
        c.initialize()
        val id = c.state.value.data!!.profile.profileId
        var requests = 0
        requestOnboardingOption(c, NativeActions(perform = { requests++ }), NativeAction.HEALTH, id)
        requestOnboardingOption(c, NativeActions(setOf(NativeAction.HEALTH), enabled = emptySet(), perform = { requests++ }), NativeAction.HEALTH, id)
        val normalOnly = NativeActions(setOf(NativeAction.HEALTH), perform = { requests++ })
        assertFalse(normalOnly.supportsOnboarding(NativeAction.HEALTH))
        assertTrue(NativeAction.HEALTH in normalOnly)
        assertFalse(NativeActions(performHealth = {}).supportsOnboarding(NativeAction.HEALTH))
        requestOnboardingOption(c, normalOnly, NativeAction.HEALTH, id)
        assertEquals(0, requests)
        assertNull(repository.explicitHealthLinkId())
        normalOnly.perform(NativeAction.HEALTH)
        assertEquals(1, requests)
        requests = 0
        val denied = NativeActions(setOf(NativeAction.HEALTH), performHealth = { requests++ })
        assertTrue(denied.supportsOnboarding(NativeAction.HEALTH))
        requestOnboardingOption(c, denied, NativeAction.HEALTH, id)
        assertEquals(1, requests)
        assertEquals(id, repository.explicitHealthLinkId())
        assertTrue(repository.readProfile(id).vitals.isEmpty)
        c.skipOnboarding()
        requestOnboardingOption(c, denied, NativeAction.HEALTH, id)
        assertEquals(1, requests)
        assertFalse(c.state.value.onboarding)
    }

    @Test
    fun optionalHealthDoesNotReplaceAnotherProfilesLink() = runTest {
        val repository = InMemoryVezunaRepository()
        val c = controller(repository)
        c.initialize()
        val id = c.state.value.data!!.profile.profileId
        repository.createProfile(Profile("other", "Other", createdAt = Instant.parse("2099-01-01T00:00:00Z")))
        repository.setLinkedHealthProfile("other")
        var requests = 0
        requestOnboardingOption(c, NativeActions(setOf(NativeAction.HEALTH), performHealth = { requests++ }), NativeAction.HEALTH, id)
        assertEquals("other", repository.explicitHealthLinkId())
        assertEquals(0, requests)
        c.skipOnboarding()
        assertFalse(c.state.value.onboarding)
    }

    @Test
    fun switchingProfileDuringLinkDoesNotStartNativeImport() = runTest {
        val repository = InMemoryVezunaRepository()
        val linking = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val delayed = object : VezunaRepository by repository {
            override suspend fun linkOnboardingHealthProfile(profileId: String, zone: TimeZone): app.vezuna.data.VitalsSyncContext? {
                linking.complete(Unit)
                resume.await()
                return repository.linkOnboardingHealthProfile(profileId, zone)
            }
        }
        val c = controller(delayed)
        c.initialize()
        val id = c.state.value.data!!.profile.profileId
        repository.createProfile(Profile("other", "Other", createdAt = Instant.parse("2099-01-01T00:00:00Z")))
        var requests = 0
        val request = launch {
            requestOnboardingOption(c, NativeActions(setOf(NativeAction.HEALTH), performHealth = { requests++ }), NativeAction.HEALTH, id)
        }
        withTimeout(5_000) { linking.await() }
        c.switchProfile("other")
        resume.complete(Unit)
        withTimeout(5_000) { request.join() }
        assertEquals("other", c.state.value.data!!.profile.profileId)
        assertEquals(id, repository.explicitHealthLinkId())
        assertEquals(0, requests)
    }

    @Test
    fun competingLinkWriterCannotBeOverwrittenByOnboarding() = runTest {
        val repository = InMemoryVezunaRepository()
        val linking = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val delayed = object : VezunaRepository by repository {
            override suspend fun linkOnboardingHealthProfile(profileId: String, zone: TimeZone): app.vezuna.data.VitalsSyncContext? {
                linking.complete(Unit)
                resume.await()
                return repository.linkOnboardingHealthProfile(profileId, zone)
            }
        }
        val c = controller(delayed)
        c.initialize()
        val id = c.state.value.data!!.profile.profileId
        repository.createProfile(Profile("other", "Other", createdAt = Instant.parse("2099-01-01T00:00:00Z")))
        var requests = 0
        val request = launch {
            requestOnboardingOption(c, NativeActions(setOf(NativeAction.HEALTH), performHealth = { requests++ }), NativeAction.HEALTH, id)
        }
        withTimeout(5_000) { linking.await() }
        repository.setLinkedHealthProfile("other")
        resume.complete(Unit)
        withTimeout(5_000) { request.join() }
        assertEquals("other", repository.explicitHealthLinkId())
        assertEquals(0, requests)
    }

    @Test
    fun relinkDuringCachedReloadCannotApproveWrongNativeTarget() = runTest {
        val repository = InMemoryVezunaRepository()
        val reading = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var delay = false
        val delayed = object : VezunaRepository by repository {
            override suspend fun peekProfile(profileId: String): app.vezuna.domain.ProfileData {
                if (delay) {
                    reading.complete(Unit)
                    resume.await()
                }
                return repository.peekProfile(profileId)
            }
        }
        val c = controller(delayed)
        c.initialize()
        val id = c.state.value.data!!.profile.profileId
        repository.createProfile(Profile("other", "Other", createdAt = Instant.parse("2099-01-01T00:00:00Z")))
        delay = true
        var requests = 0
        val request = launch {
            requestOnboardingOption(c, NativeActions(setOf(NativeAction.HEALTH), performHealth = { requests++ }), NativeAction.HEALTH, id)
        }
        withTimeout(5_000) { reading.await() }
        repository.setLinkedHealthProfile("other")
        resume.complete(Unit)
        withTimeout(5_000) { request.join() }
        assertEquals("other", repository.explicitHealthLinkId())
        assertEquals(0, requests)
    }

    @Test
    fun failedConditionalLinkSaveNeverDispatchesAndCompletionRemainsAvailable() = runTest {
        val vault = GenerationalMemoryVault()
        val repository = app.vezuna.data.VaultBackedRepository(vault)
        repository.load()
        val c = controller(repository)
        c.initialize()
        val id = c.state.value.data!!.profile.profileId
        vault.failingWrites += "repository-health-link-v1"
        var requests = 0
        requestOnboardingOption(c, NativeActions(setOf(NativeAction.HEALTH), performHealth = { requests++ }), NativeAction.HEALTH, id)
        assertEquals(0, requests)
        assertNull(repository.explicitHealthLinkId())
        assertTrue(repository.peekProfile(id).vitals.isEmpty)
        assertTrue(c.state.value.error != null)
        c.skipOnboarding()
        assertFalse(c.state.value.onboarding)
    }

    @Test
    fun cancellationDuringConditionalLinkDoesNotDispatchOrPin() = runTest {
        val repository = InMemoryVezunaRepository()
        val linking = CompletableDeferred<Unit>()
        val delayed = object : VezunaRepository by repository {
            override suspend fun linkOnboardingHealthProfile(profileId: String, zone: TimeZone): app.vezuna.data.VitalsSyncContext? {
                linking.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val c = controller(delayed)
        c.initialize()
        val id = c.state.value.data!!.profile.profileId
        var requests = 0
        val request = launch {
            requestOnboardingOption(c, NativeActions(setOf(NativeAction.HEALTH), performHealth = { requests++ }), NativeAction.HEALTH, id)
        }
        withTimeout(5_000) { linking.await() }
        request.cancel()
        withTimeout(5_000) { request.join() }
        assertEquals(0, requests)
        assertNull(repository.explicitHealthLinkId())
        c.skipOnboarding()
        assertFalse(c.state.value.onboarding)
    }

    @Test
    fun cancellationAfterLinkSaveDoesNotLaunchNativePermission() = runTest {
        val repository = InMemoryVezunaRepository()
        var cancelCapture = false
        val delayed = object : VezunaRepository by repository {
            override suspend fun captureVitalsSync(zone: TimeZone): app.vezuna.data.VitalsSyncContext {
                val context = repository.captureVitalsSync(zone)
                if (cancelCapture) kotlinx.coroutines.currentCoroutineContext().let { it[kotlinx.coroutines.Job]!!.cancel() }
                return context
            }
        }
        val c = controller(delayed)
        c.initialize()
        val id = c.state.value.data!!.profile.profileId
        cancelCapture = true
        var requests = 0
        val request = launch {
            requestOnboardingOption(c, NativeActions(setOf(NativeAction.HEALTH), performHealth = { requests++ }), NativeAction.HEALTH, id)
        }
        withTimeout(5_000) { request.join() }
        assertTrue(request.isCancelled)
        assertEquals(id, repository.explicitHealthLinkId())
        assertEquals(0, requests)
        assertTrue(repository.peekProfile(id).vitals.isEmpty)
        c.skipOnboarding()
        assertFalse(c.state.value.onboarding)
    }

    @Test
    fun notificationsRequireExplicitActionAndNeverLinkHealth() = runTest {
        val repository = InMemoryVezunaRepository()
        val c = controller(repository)
        c.initialize()
        val id = c.state.value.data!!.profile.profileId
        var requests = 0
        val actions = NativeActions(setOf(NativeAction.REMINDERS), perform = { requests++ })
        c.completeOnboarding("Ana", null, finish = false)
        assertEquals(0, requests)
        requestOnboardingOption(c, actions, NativeAction.REMINDERS, id)
        assertEquals(1, requests)
        assertNull(repository.explicitHealthLinkId())
        c.skipOnboarding()
        assertEquals(1, requests)
        assertFalse(c.state.value.onboarding)
    }

    @Test
    fun blankNameKeepsDefaultAndFutureBirthDateIsRejected() = runTest {
        val repository = InMemoryVezunaRepository()
        val c = controller(repository)
        c.initialize()
        val defaultName = repository.profiles.value.single().displayName
        c.completeOnboarding("  ", LocalDate(2027, 1, 1))
        assertEquals(L10nKey.BIRTH_DATE_INVALID, (c.state.value.error as UserText.Key).key)
        assertTrue(c.state.value.onboarding)
        c.completeOnboarding("", null)
        assertFalse(c.state.value.onboarding)
        assertEquals(defaultName, repository.profiles.value.single().displayName)
        assertNull(repository.profiles.value.single().birthDate)
    }

    @Test
    fun capLeapDayAndLegacyJson() {
        val today = LocalDate(2026, 11, 1)
        assertFalse(ProfileAge.isValid(LocalDate(1895, 10, 31), today))
        assertTrue(ProfileAge.isValid(LocalDate(1896, 11, 1), today))
        assertEquals(1, ProfileAge.years(LocalDate(2024, 2, 29), LocalDate(2025, 3, 1)))
        assertEquals(0, ProfileAge.years(LocalDate(2024, 2, 29), LocalDate(2025, 2, 28)))
        val legacy = kotlinx.serialization.json.Json.decodeFromString<Profile>("""{"profileId":"p","displayName":"Me","createdAt":"2026-01-01T00:00:00Z"}""")
        assertNull(legacy.birthDate)
    }

    @Test
    fun freshProfileUsesDeviceDefaultNameAndBirthDateClears() = runTest {
        val repository = InMemoryVezunaRepository()
        val c = controller(repository)
        c.initialize()
        val p = repository.profiles.value.single()
        assertEquals(app.vezuna.domain.DefaultProfileName.of(app.vezuna.localization.ContentLanguage.device()), p.displayName)
        c.setBirthDate(p.profileId, LocalDate(2000, 1, 1))
        assertEquals(LocalDate(2000, 1, 1), repository.profiles.value.single().birthDate)
        c.setBirthDate(p.profileId, null)
        assertNull(repository.profiles.value.single().birthDate)
        c.setBirthDate(p.profileId, LocalDate(1800, 1, 1))
        assertEquals(L10nKey.BIRTH_DATE_INVALID, (c.state.value.error as UserText.Key).key)
    }

    @Test
    fun reInitMidFirstRunKeepsOnboarding() = runTest {
        val repository = InMemoryVezunaRepository()
        val c = controller(repository)
        c.initialize()
        c.initialize()
        assertTrue(c.state.value.onboarding)
        assertEquals(1, repository.profiles.value.size)
        c.createProfile("Second")
        c.initialize()
        assertFalse(c.state.value.onboarding)
    }

    private object NoModels : ModelFileStore {
        override suspend fun installed(): List<InstalledModel> = emptyList()
        override suspend fun install(model: InstalledModel, sourcePath: String) = Unit
        override suspend fun verifyIntegrity(model: InstalledModel): Boolean = true
        override suspend fun delete(model: InstalledModel) = Unit
        override suspend fun saveSelection(modelId: String?) = Unit
        override suspend fun savedSelection(): String? = null
    }

    private object NoLlm : LocalLlm {
        override suspend fun load(modelPath: String, settings: LlmGenerationSettings) = Unit
        override suspend fun generate(prompt: String): String = ""
        override suspend fun unload() = Unit
    }
}
