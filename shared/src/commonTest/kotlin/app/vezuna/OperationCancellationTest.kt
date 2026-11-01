package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationCancellationTest {
    @Test
    fun cancelledOperationClearsLoading() = runTest {
        val llm = object : LocalLlm {
            override suspend fun load(modelPath: String, settings: LlmGenerationSettings) = Unit
            override suspend fun generate(prompt: String): String = ""
            override suspend fun unload() = Unit
        }
        val store = object : ModelFileStore {
            override suspend fun installed(): List<InstalledModel> = CompletableDeferred<List<InstalledModel>>().await()
            override suspend fun install(model: InstalledModel, sourcePath: String) = Unit
            override suspend fun verifyIntegrity(model: InstalledModel): Boolean = true
            override suspend fun delete(model: InstalledModel) = Unit
            override suspend fun saveSelection(modelId: String?) = Unit
            override suspend fun savedSelection(): String? = null
        }
        val repository = InMemoryVezunaRepository()
        val controller = AppController(repository, InstalledModelManager(ModelCatalog.unconfigured(), store, llm), LocalHealthChat(repository, llm))

        val job = launch { controller.initialize() }
        runCurrent()
        assertTrue(controller.state.value.loading, "operation should be suspended and loading")

        job.cancel()
        runCurrent()
        assertFalse(controller.state.value.loading, "cancellation must clear loading")
    }
}
