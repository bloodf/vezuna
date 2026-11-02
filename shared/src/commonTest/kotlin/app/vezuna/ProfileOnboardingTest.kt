package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
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

    private fun controller(repository: InMemoryVezunaRepository) =
        AppController(repository, InstalledModelManager(ModelCatalog.unconfigured(), NoModels, NoLlm), LocalHealthChat(repository, NoLlm), clock = { now }, timeZone = { TimeZone.UTC })

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
