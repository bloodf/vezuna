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
