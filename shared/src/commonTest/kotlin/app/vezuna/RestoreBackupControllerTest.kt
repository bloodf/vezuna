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
import app.vezuna.ui.AppController
import app.vezuna.ui.RestoreMode
import kotlinx.coroutines.test.runTest
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
