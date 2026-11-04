package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.data.VaultBackedRepository
import app.vezuna.data.VezunaRepository
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.llm.InstalledModel
import app.vezuna.llm.InstalledModelManager
import app.vezuna.llm.LlmGenerationSettings
import app.vezuna.llm.LocalHealthChat
import app.vezuna.llm.LocalLlm
import app.vezuna.llm.ModelCatalog
import app.vezuna.llm.ModelFileStore
import app.vezuna.ui.AppController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** A refresh of the open profile that is still reading must never overwrite a profile the user switched to meanwhile. */
class ProfileReloadRaceTest {
    private val t = Instant.parse("2026-11-01T12:00:00Z")

    /** Holds one chosen call of the repository until released; the hold comes BEFORE the real read, like a slow disk. */
    private class Gated(private val inner: VezunaRepository) : VezunaRepository by inner {
        var gate: CompletableDeferred<Unit>? = null
        var gatedId: String? = null
        var linkGate: CompletableDeferred<Unit>? = null
        var failOpenOf: String? = null
        var addGate: CompletableDeferred<Unit>? = null
        override suspend fun readProfile(profileId: String): ProfileData { if (profileId == failOpenOf) throw NoSuchElementException(profileId); hold(profileId); return inner.readProfile(profileId) }
        override suspend fun peekProfile(profileId: String): ProfileData { hold(profileId); return inner.peekProfile(profileId) }
        override suspend fun linkedHealthProfileId(): String? {
            linkGate?.let { linkGate = null; it.await() }
            return inner.linkedHealthProfileId()
        }
        override suspend fun addMedication(medication: app.vezuna.domain.Medication) {
            addGate?.let { addGate = null; it.await() }
            inner.addMedication(medication)
        }
        private suspend fun hold(profileId: String) { if (profileId == gatedId) gate?.await() }
    }

    @Test
    fun slowReloadOfPreviousProfileDoesNotOverwriteSwitch() = runTest {
        val repository = Gated(InMemoryVezunaRepository())
        repository.createProfile(Profile("a", "Alice", createdAt = t))
        repository.createProfile(Profile("b", "Bruno", createdAt = t))
        val controller = AppController(repository, InstalledModelManager(ModelCatalog.unconfigured(), NoModels, NoLlm), LocalHealthChat(repository, NoLlm))
        controller.initialize()
        controller.switchProfile("a")
        repository.gate = CompletableDeferred()
        repository.gatedId = "a"

        val reload = launch { controller.refreshDashboard() }
        testScheduler.advanceUntilIdle()
        controller.switchProfile("b")
        repository.gate?.complete(Unit)
        reload.join()

        assertEquals("b", controller.state.value.data?.profile?.profileId)
    }

    private val vault = GenerationalMemoryVault()

    private suspend fun persisted() = VaultBackedRepository(vault, persistSelection = true).also { it.load() }

    private suspend fun reopenedFirst() = persisted().profiles.value.first().profileId

    private suspend fun gatedApp(llm: LocalLlm = NoLlm): Pair<Gated, AppController> {
        val repository = Gated(persisted())
        repository.createProfile(Profile("a", "Alice", createdAt = t))
        repository.createProfile(Profile("b", "Bruno", createdAt = t))
        val controller = AppController(repository, InstalledModelManager(ModelCatalog.unconfigured(), NoModels, NoLlm), LocalHealthChat(repository, llm))
        controller.initialize()
        controller.switchProfile("a")
        return repository to controller
    }

    @Test
    fun staleRefreshKeepsSavedSelectionOnTheNewProfile() = runTest {
        val (repository, controller) = gatedApp()
        repository.gate = CompletableDeferred()
        repository.gatedId = "a"
        val reload = launch { controller.refreshDashboard() }
        testScheduler.advanceUntilIdle()
        controller.switchProfile("b")
        repository.gate?.complete(Unit)
        reload.join()
        assertEquals("b", controller.state.value.data?.profile?.profileId)
        assertEquals("b", reopenedFirst())
    }

    @Test
    fun answerFinishingAfterSwitchDoesNotReopenThePreviousProfile() = runTest {
        val release = CompletableDeferred<Unit>()
        val (_, controller) = gatedApp(object : LocalLlm {
            override suspend fun load(modelPath: String, settings: LlmGenerationSettings) = Unit
            override suspend fun generate(prompt: String): String { release.await(); return "late" }
            override suspend fun unload() = Unit
        })
        val ask = launch { runCatching { controller.ask("How am I?") } }
        testScheduler.advanceUntilIdle()
        controller.switchProfile("b")
        release.complete(Unit)
        ask.join()
        assertEquals("b", controller.state.value.data?.profile?.profileId)
        assertEquals(null, controller.state.value.streamingAnswer)
        assertEquals("b", reopenedFirst())
    }

    @Test
    fun failedOpenDoesNotPinRetryToTheSameProfile() = runTest {
        val (repository, controller) = gatedApp()
        repository.failOpenOf = "b"
        controller.switchProfile("b")
        assertEquals(null, controller.state.value.data)
        repository.failOpenOf = null
        controller.initialize() // Retry
        assertEquals("a", controller.state.value.data?.profile?.profileId)
    }

    @Test
    fun refreshRacingADeleteDoesNotThrow() = runTest {
        val (repository, controller) = gatedApp()
        repository.gate = CompletableDeferred()
        repository.gatedId = "a"
        val reload = launch { controller.refreshDashboard() }
        testScheduler.advanceUntilIdle()
        controller.deleteProfile("a", "Alice")
        repository.gate?.complete(Unit) // the refresh now reads a profile that no longer exists
        reload.join()
        assertEquals("b", controller.state.value.data?.profile?.profileId)
    }

    @Test
    fun saveOnTheOldProfileFinishingAfterASwitchLandsThereAndLeavesTheNewProfileOpen() = runTest {
        val (repository, controller) = gatedApp()
        val release = CompletableDeferred<Unit>()
        repository.addGate = release
        val save = launch { controller.addMedication("Metformin", "", "", "08:00") }
        testScheduler.advanceUntilIdle()
        controller.switchProfile("b")
        release.complete(Unit)
        save.join()
        assertEquals("b", controller.state.value.data?.profile?.profileId)
        assertEquals("b", reopenedFirst())
        assertEquals(emptyList<String>(), controller.state.value.data?.medications?.map { it.displayName })
        assertEquals(listOf("Metformin"), persisted().peekProfile("a").medications.map { it.displayName })
        assertEquals(null, controller.state.value.notice)
    }

    @Test
    fun healthLinkReadAfterTheStaleCheckCannotOverwriteASwitch() = runTest {
        val (repository, controller) = gatedApp()
        repository.linkGate = CompletableDeferred()
        val first = repository.linkGate
        val reload = launch { controller.refreshDashboard() }
        testScheduler.advanceUntilIdle()
        controller.switchProfile("b")
        first?.complete(Unit)
        reload.join()
        assertEquals("b", controller.state.value.data?.profile?.profileId)
    }

    @Test
    fun runningAnswerEndsOnSwitchAndNeverShowsUnderTheNewProfile() = runTest {
        val release = CompletableDeferred<Unit>()
        val (_, controller) = gatedApp(object : LocalLlm {
            override suspend fun load(modelPath: String, settings: LlmGenerationSettings) = Unit
            override suspend fun generate(prompt: String): String = ""
            override fun generateStream(prompt: String) = kotlinx.coroutines.flow.flow {
                emit("partial")
                release.await()
                emit(" late")
            }
            override suspend fun unload() = Unit
        })
        val ask = launch { runCatching { controller.ask("How am I?") } }
        testScheduler.advanceUntilIdle()
        assertEquals("partial", controller.state.value.streamingAnswer)
        controller.switchProfile("b")
        assertEquals(null, controller.state.value.streamingAnswer)
        release.complete(Unit)
        ask.join()
        assertEquals(null, controller.state.value.streamingAnswer)
        assertEquals(emptyList(), controller.state.value.data?.chat)
        assertEquals(emptyList(), persisted().peekProfile("a").chat)
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
