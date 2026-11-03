package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.data.VezunaRepository
import app.vezuna.domain.ProfileVitals
import app.vezuna.llm.InstalledModel
import app.vezuna.llm.InstalledModelManager
import app.vezuna.llm.LlmGenerationSettings
import app.vezuna.llm.LocalHealthChat
import app.vezuna.llm.LocalLlm
import app.vezuna.llm.ModelCatalog
import app.vezuna.llm.ModelFileStore
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.security.BackupMember
import app.vezuna.ui.AppController
import app.vezuna.ui.AppScreen
import app.vezuna.ui.RestoreMode
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestoreBackupControllerTest {
    private val data = sampleProfileData()

    private suspend fun controller(repository: VezunaRepository) =
        AppController(repository, InstalledModelManager(ModelCatalog.unconfigured(), NoModels, NoLlm), LocalHealthChat(repository, NoLlm)).also { it.initialize() }

    @Test
    fun restoringIntoAnEmptyDeviceCreatesTheProfileAndSelectsIt() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = controller(repository)
        controller.restoreBackup(data, RestoreMode.REPLACE_EXISTING)
        assertNull(controller.state.value.error)
        assertEquals(data.profile.profileId, controller.state.value.data?.profile?.profileId)
        assertEquals(data.medications, controller.state.value.data?.medications)
        assertEquals(data.vitals.copy(lastSync = null), controller.state.value.data?.vitals)
        assertEquals(UserText.Key(L10nKey.BACKUP_RESTORED), controller.state.value.notice)
        // sample data's source file is not on this device, so its key must not dangle
        assertNull(controller.state.value.data?.documents?.single()?.vaultKey)
    }

    @Test
    fun replaceOverwritesTheExistingProfileWithoutStaleRecords() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = controller(repository)
        controller.restoreBackup(data.copy(chat = emptyList()), RestoreMode.REPLACE_EXISTING)
        repository.addChatMessage(data.chat.single().copy(messageId = "OLD-newer"))
        controller.restoreBackup(data, RestoreMode.REPLACE_EXISTING)
        val stored = repository.readProfile(data.profile.profileId)
        assertEquals(listOf("OLD-msg"), stored.chat.map { it.messageId })
        assertEquals(2, repository.profiles.value.size) // the default "Me" profile plus the restored one
    }

    @Test
    fun restoreAsNewKeepsTheExistingProfileAndUsesFreshIds() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = controller(repository)
        controller.restoreBackup(data, RestoreMode.REPLACE_EXISTING)
        controller.restoreBackup(data, RestoreMode.RESTORE_AS_NEW)
        assertNull(controller.state.value.error)
        assertEquals(3, repository.profiles.value.size)
        val restored = controller.state.value.data!!
        assertTrue(restored.profile.profileId != data.profile.profileId)
        assertEquals(data.observations.single().originalValue, restored.observations.single().originalValue)
        assertEquals(data.vitals.copy(lastSync = null), restored.vitals)
        // replace also clears lastSync of the existing profile's vitals
        assertEquals(data.vitals.copy(lastSync = null), repository.readProfile(data.profile.profileId).vitals)
        assertEquals(data.copy(vitals = data.vitals.copy(lastSync = null)), repository.readProfile(data.profile.profileId).copy(documents = data.documents))
    }

    @Test
    fun vitalsFailureStillRestoresTheProfileAndSaysSo() = runTest {
        val inner = InMemoryVezunaRepository()
        val repository = object : VezunaRepository by inner {
            override suspend fun replaceVitals(profileId: String, vitals: ProfileVitals) = throw IllegalStateException("disk full")
        }
        val controller = controller(repository)
        controller.restoreBackup(data, RestoreMode.REPLACE_EXISTING)
        assertNull(controller.state.value.error)
        assertEquals(UserText.Key(L10nKey.BACKUP_RESTORED_WITHOUT_VITALS), controller.state.value.notice)
        assertEquals(data.medications, inner.readProfile(data.profile.profileId).medications)
    }

    @Test
    fun completingOnboardingRenamesTheLoadedProfileNotTheFirstInTheList() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = controller(repository)
        val loadedId = repository.ids().single()
        repository.createProfile(app.vezuna.domain.Profile("aaa", "Aaa", createdAt = data.profile.createdAt))
        controller.completeOnboarding("Bob", null)
        assertEquals("Bob", repository.peekProfile(loadedId).profile.displayName)
        assertEquals("Aaa", repository.peekProfile("aaa").profile.displayName)
    }

    // ---- first-run restore removes the untouched default profile ----

    private fun VezunaRepository.ids() = profiles.value.map { it.profileId }

    private suspend fun firstRunRestore(repository: VezunaRepository, prepare: suspend (String) -> Unit = {}): AppController {
        val controller = controller(repository)
        prepare(repository.ids().single())
        controller.startFirstRunRestore()
        controller.restoreBackup(data, RestoreMode.REPLACE_EXISTING)
        return controller
    }

    @Test
    fun firstRunRestoreRemovesTheUntouchedDefaultProfile() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = firstRunRestore(repository)
        assertNull(controller.state.value.error)
        assertEquals(listOf(data.profile.profileId), repository.ids())
        assertEquals(listOf(data.profile.profileId), controller.state.value.profiles.map { it.profileId })
        assertEquals(data.profile.profileId, controller.state.value.data?.profile?.profileId)
        assertEquals(data.profile.profileId, repository.linkedHealthProfileId())
        assertEquals(data.profile.profileId, controller.state.value.healthLinkedProfileId)
    }

    @Test
    fun localizedDefaultNamesCountAsUntouched() = runTest {
        val repository = InMemoryVezunaRepository()
        firstRunRestore(repository) { id -> repository.updateProfile(repository.profiles.value.single().copy(displayName = "Eu")) }
        assertEquals(listOf(data.profile.profileId), repository.ids())
    }

    @Test
    fun defaultProfileIsKeptWhenItHasARecord() = runTest {
        val repository = InMemoryVezunaRepository()
        firstRunRestore(repository) { id ->
            repository.addChatMessage(sampleProfileData("ME").chat.single().copy(profileId = id))
        }
        assertEquals(2, repository.ids().size)
    }

    @Test
    fun defaultProfileIsKeptWhenItHasAMedication() = runTest {
        val repository = InMemoryVezunaRepository()
        firstRunRestore(repository) { id -> repository.addMedication(sampleProfileData("ME").medications.single().copy(profileId = id, schedules = emptyList())) }
        assertEquals(2, repository.ids().size)
    }

    @Test
    fun defaultProfileIsKeptWhenItHasVitals() = runTest {
        val repository = InMemoryVezunaRepository()
        firstRunRestore(repository) { id -> repository.replaceVitals(id, data.vitals) }
        assertEquals(2, repository.ids().size)
    }

    @Test
    fun defaultProfileIsKeptWithACustomNameOrExplicitLink() = runTest {
        val renamed = InMemoryVezunaRepository()
        firstRunRestore(renamed) { id -> renamed.updateProfile(renamed.profiles.value.single().copy(displayName = "Maria")) }
        assertEquals(2, renamed.ids().size)

        val linked = InMemoryVezunaRepository()
        firstRunRestore(linked) { id -> linked.setLinkedHealthProfile(id) }
        assertEquals(2, linked.ids().size)
    }

    @Test
    fun defaultProfileIsKeptWhenAnotherProfileExists() = runTest {
        val repository = InMemoryVezunaRepository()
        firstRunRestore(repository) { repository.createProfile(app.vezuna.domain.Profile("other", "Zed", createdAt = data.profile.createdAt)) }
        assertEquals(3, repository.ids().size)
    }

    @Test
    fun defaultProfileIsKeptWhenTheRestoreFails() = runTest {
        val inner = InMemoryVezunaRepository()
        val repository = object : VezunaRepository by inner {
            override suspend fun restoreProfile(data: app.vezuna.domain.ProfileData) = throw IllegalStateException("wrong passphrase")
        }
        val controller = controller(repository)
        controller.startFirstRunRestore()
        controller.restoreBackup(data, RestoreMode.REPLACE_EXISTING)
        assertTrue(controller.state.value.error != null)
        assertEquals(1, inner.profiles.value.size)
    }

    @Test
    fun defaultProfileIsKeptForARestoreOutsideFirstRun() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = controller(repository)
        controller.skipOnboarding() // e.g. Start on Welcome, restore later from More
        controller.restoreBackup(data, RestoreMode.REPLACE_EXISTING)
        assertEquals(2, repository.ids().size)
    }

    @Test
    fun defaultProfileIsKeptWhenTheAppRelaunchesBetweenWelcomeAndRestore() = runTest {
        val repository = InMemoryVezunaRepository()
        controller(repository).startFirstRunRestore()
        val relaunched = controller(repository) // the flag is memory-only
        relaunched.restoreBackup(data, RestoreMode.REPLACE_EXISTING)
        assertEquals(2, repository.ids().size)
    }

    @Test
    fun relaunchAfterAFirstRunRestoreOpensTheRestoredProfile() = runTest {
        val vault = GenerationalMemoryVault()
        val repository = app.vezuna.data.VaultBackedRepository(vault, persistSelection = true).also { it.load() }
        firstRunRestore(repository)
        val reopened = app.vezuna.data.VaultBackedRepository(vault, persistSelection = true).also { it.load() }
        val relaunched = controller(reopened)
        assertEquals(listOf(data.profile.profileId), reopened.ids())
        assertEquals(data.profile.profileId, relaunched.state.value.data?.profile?.profileId)
        assertEquals(data.profile.profileId, reopened.linkedHealthProfileId())
    }

    @Test
    fun defaultProfileIsKeptWhenAnyRecordTypeWasAdded() = runTest {
        val me = sampleProfileData("ME")
        val additions: Map<String, suspend (VezunaRepository, String) -> Unit> = mapOf(
            "birthDate" to { r, id -> r.updateProfile(r.profiles.value.single { it.profileId == id }.copy(birthDate = LocalDate(1990, 1, 1))) },
            "documents" to { r, id -> r.addDocument(me.documents.single().copy(profileId = id)) },
            "reports" to { r, id -> r.addDocument(me.documents.single().copy(profileId = id)); r.addReport(me.reports.single().copy(profileId = id)) },
            "observations" to { r, id ->
                r.addDocument(me.documents.single().copy(profileId = id))
                r.addReport(me.reports.single().copy(profileId = id))
                r.addObservation(me.observations.single().copy(profileId = id))
            },
            "ocrCandidates" to { r, id -> r.addDocument(me.documents.single().copy(profileId = id)); r.addCandidate(me.ocrCandidates.single().copy(profileId = id)) },
            "doseEvents" to { r, id ->
                val medication = me.medications.single()
                r.addMedication(medication.copy(profileId = id, schedules = medication.schedules.map { it.copy(profileId = id) }))
                r.addDoseEvent(me.doseEvents.single().copy(profileId = id))
            },
        )
        additions.forEach { (field, add) ->
            val repository = InMemoryVezunaRepository()
            firstRunRestore(repository) { id -> add(repository, id) }
            assertEquals(2, repository.ids().size, "$field keeps the default profile")
        }
    }

    @Test
    fun defaultProfileIsKeptWhenTheBackupContainsItsOwnId() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = controller(repository)
        val defaultId = repository.ids().single()
        controller.startFirstRunRestore()
        controller.restoreBackup(data.copy(profile = data.profile.copy(profileId = defaultId)), RestoreMode.REPLACE_EXISTING)
        assertEquals(listOf(defaultId), repository.ids())
        assertEquals(defaultId, controller.state.value.data?.profile?.profileId)
    }

    @Test
    fun defaultProfileHoldingADocumentCountsAsTouched() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = controller(repository)
        val defaultId = repository.ids().single()
        repository.addVaultDocument(
            app.vezuna.domain.VaultDocument(app.vezuna.domain.SourceDocument(defaultId, "kept", "rx.pdf", "application/pdf", "h", kotlin.time.Instant.parse("2026-01-01T00:00:00Z"), "k")),
        )
        controller.startFirstRunRestore()
        controller.restoreBackup(data, RestoreMode.REPLACE_EXISTING)
        assertTrue(defaultId in repository.ids())
    }

    @Test
    fun aRestoredProfileNamedMeSurvivesAndTheUntouchedDefaultGoes() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = controller(repository)
        val defaultId = repository.ids().single()
        controller.startFirstRunRestore()
        controller.restoreBackup(data.copy(profile = data.profile.copy(displayName = "Me")), RestoreMode.REPLACE_EXISTING)
        assertEquals(listOf(data.profile.profileId), repository.ids())
        assertTrue(defaultId !in repository.ids())
        assertEquals("Me", repository.peekProfile(data.profile.profileId).profile.displayName)
    }

    @Test
    fun householdFirstRunRestoreKeepsEveryRestoredMemberAndDropsTheDefault() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = controller(repository)
        controller.startFirstRunRestore()
        controller.restoreHousehold(listOf(BackupMember(sampleProfileData("A")), BackupMember(sampleProfileData("B"))), RestoreMode.REPLACE_EXISTING)
        assertEquals(setOf("A-profile", "B-profile"), repository.ids().toSet())
        assertEquals(setOf("A-profile", "B-profile"), controller.state.value.profiles.map { it.profileId }.toSet())
    }

    @Test
    fun defaultProfileIsKeptWhenSwitchingToTheRestoredProfileFails() = runTest {
        val inner = InMemoryVezunaRepository()
        val repository = object : VezunaRepository by inner {
            override suspend fun readProfile(profileId: String): app.vezuna.domain.ProfileData =
                if (profileId == data.profile.profileId) throw IllegalStateException("unreadable") else inner.readProfile(profileId)
        }
        val controller = controller(repository)
        controller.startFirstRunRestore()
        controller.restoreBackup(data, RestoreMode.REPLACE_EXISTING)
        assertEquals(2, inner.profiles.value.size)
        assertEquals(inner.profiles.value.map { it.profileId }.toSet(), controller.state.value.profiles.map { it.profileId }.toSet())
    }

    @Test
    fun leavingBackupsDisarmsTheFirstRunRestore() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = controller(repository)
        controller.startFirstRunRestore()
        controller.navigate(AppScreen.Backups)
        controller.back() // Welcome > Restore, then out again
        controller.restoreBackup(data, RestoreMode.REPLACE_EXISTING) // a later restore from More
        assertEquals(2, repository.ids().size)
    }

    @Test
    fun navigatingAwayFromBackupsDisarmsTheFirstRunRestore() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = controller(repository)
        controller.startFirstRunRestore()
        controller.navigate(AppScreen.Backups)
        controller.selectRoot(app.vezuna.ui.nav.Destination.entries.first())
        controller.restoreBackup(data, RestoreMode.REPLACE_EXISTING)
        assertEquals(2, repository.ids().size)
    }

    @Test
    fun retryingFromBackupsAfterAWrongPassphraseStillRemovesTheDefault() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = controller(repository)
        controller.startFirstRunRestore()
        controller.navigate(AppScreen.Backups)
        // A wrong passphrase never reaches restoreBackup and the person stays on Backups; the screen re-navigates only when elsewhere.
        controller.navigate(AppScreen.Backups)
        controller.restoreBackup(data, RestoreMode.REPLACE_EXISTING)
        assertEquals(listOf(data.profile.profileId), repository.ids())
    }

    @Test
    fun aFailedCleanupIsReportedAndTheProfileListStaysCurrent() = runTest {
        val inner = InMemoryVezunaRepository()
        val repository = object : VezunaRepository by inner {
            override suspend fun deleteProfile(profileId: String) = throw IllegalStateException("tombstone write failed")
        }
        val warnings = mutableListOf<String>()
        val controller = AppController(
            repository, InstalledModelManager(ModelCatalog.unconfigured(), NoModels, NoLlm), LocalHealthChat(repository, NoLlm),
            warn = { message, _ -> warnings += message },
        ).also { it.initialize() }
        controller.startFirstRunRestore()
        controller.restoreBackup(data, RestoreMode.REPLACE_EXISTING)
        assertEquals(1, warnings.size)
        assertEquals(2, controller.state.value.profiles.size)
        assertNull(controller.state.value.error)
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
