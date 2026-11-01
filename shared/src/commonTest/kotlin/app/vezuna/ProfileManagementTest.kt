package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.domain.Profile
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
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProfileManagementTest {
    private val t = Instant.parse("2026-11-01T12:00:00Z")

    private suspend fun controller(repository: InMemoryVezunaRepository): AppController {
        repository.createProfile(Profile("a", "Alice", createdAt = t))
        repository.createProfile(Profile("b", "Bruno", createdAt = t))
        return AppController(repository, InstalledModelManager(ModelCatalog.unconfigured(), NoModels, NoLlm), LocalHealthChat(repository, NoLlm)).also { it.initialize() }
    }

    private fun AppController.errorKey() = (state.value.error as? UserText.Key)?.key

    @Test
    fun renameUpdatesProfileAndRejectsBlank() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = controller(repository)
        controller.renameProfile("a", "  Alicia ")
        assertNull(controller.errorKey())
        assertEquals("Alicia", repository.readProfile("a").profile.displayName)
        assertEquals("Alicia", controller.state.value.data?.profile?.displayName)
        controller.renameProfile("a", " ")
        assertEquals(L10nKey.PROFILE_NAME_REQUIRED, controller.errorKey())
    }

    @Test
    fun deleteRequiresTypedNameAndSwitchesAwayFromDeletedProfile() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = controller(repository)
        controller.deleteProfile("a", "alice")
        assertEquals(L10nKey.PROFILE_NAME_MISMATCH, controller.errorKey())
        assertNotNull(repository.profiles.value.firstOrNull { it.profileId == "a" })

        controller.deleteProfile("a", "Alice")
        assertNull(controller.errorKey())
        assertEquals(listOf("b"), repository.profiles.value.map { it.profileId })
        assertEquals("b", controller.state.value.data?.profile?.profileId)
    }

    @Test
    fun lastRemainingProfileCannotBeDeleted() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = controller(repository)
        controller.deleteProfile("a", "Alice")
        controller.deleteProfile("b", "Bruno")
        assertEquals(L10nKey.LAST_PROFILE_CANNOT_BE_DELETED, controller.errorKey())
        assertEquals(listOf("b"), repository.profiles.value.map { it.profileId })
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
