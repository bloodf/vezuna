package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
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

    private class Gated(private val inner: InMemoryVezunaRepository) : VezunaRepository by inner {
        var gate: CompletableDeferred<Unit>? = null
        var gatedId: String? = null
        override suspend fun readProfile(profileId: String): ProfileData = inner.readProfile(profileId).also { hold(profileId) }
        override suspend fun peekProfile(profileId: String): ProfileData = inner.peekProfile(profileId).also { hold(profileId) }
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
