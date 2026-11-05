package app.vezuna

import app.vezuna.llm.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModelManagerLifecycleContractTest {
    private val pack = ModelPack("a", "A", "a.gguf", "android-a", "ios-a", 1, 1, 4096, "0".repeat(64))
    private class Backend : LocalLlm {
        val events = mutableListOf<String>()
        var failLoad = false
        override suspend fun load(modelPath: String, settings: LlmGenerationSettings) {
            events += "load"
            if (failLoad) error("synthetic load failure")
        }
        override suspend fun generate(prompt: String) = "answer"
        override suspend fun unload() { events += "unload" }
    }
    private class Store(val model: InstalledModel) : ModelFileStore {
        var selection: String? = null
        var beforeRead: suspend () -> Unit = {}
        var onSave: suspend (String?) -> Unit = { selection = it }
        var entries = listOf(model)
        var installAction: suspend () -> Unit = {}
        var selectionRead: suspend () -> Unit = {}
        override suspend fun installed(): List<InstalledModel> { beforeRead(); return entries }
        override suspend fun install(model: InstalledModel, sourcePath: String) { installAction(); entries = listOf(model) }
        override suspend fun delete(model: InstalledModel) { entries = emptyList(); if (selection == model.pack.modelId) selection = null }
        override suspend fun saveSelection(modelId: String?) { onSave(modelId) }
        override suspend fun savedSelection(): String? { selectionRead(); return selection }
        override suspend fun verifyIntegrity(model: InstalledModel) = true
    }
    @Test fun canceledRefreshClearsBusyAfterReadEntered() = runTest(timeout = 5.seconds) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = Store(InstalledModel(pack, "a.gguf", 1))
        store.beforeRead = { entered.complete(Unit); release.await() }
        val manager = InstalledModelManager(ModelCatalog(listOf(pack)), store, Backend())
        val job = launch { manager.refresh() }
        entered.await()
        assertTrue(manager.state.value.busy)
        job.cancel()
        release.complete(Unit)
        job.join()
        assertFalse(manager.state.value.busy)
    }
    @Test fun failedPrevalidationClearsBusyAndPreservesLegacyLoad() = runTest(timeout = 5.seconds) {
        val store = Store(InstalledModel(pack, "a.gguf", 1))
        val backend = Backend()
        val manager = InstalledModelManager(ModelCatalog(listOf(pack)), store, backend)
        manager.select("a")
        assertFails { manager.select("missing") }
        assertFalse(manager.state.value.busy)
        assertEquals("a", manager.state.value.loadedModelId)
        assertEquals(listOf("load"), backend.events)
    }
    @Test fun legacySaveFailureUnloadsAndLeavesSelectionUnknown() = runTest(timeout = 5.seconds) {
        for (mutate in listOf(false, true)) {
            val store = Store(InstalledModel(pack, "a.gguf", 1)); val backend = Backend()
            val manager = InstalledModelManager(ModelCatalog(listOf(pack)), store, backend)
            store.onSave = { if (mutate) store.selection = it; error("synthetic") }
            assertFailsWith<ModelOperationFailure> { manager.select("a") }
            assertEquals(listOf("load", "unload"), backend.events)
            assertNull(manager.state.value.loadedModelId)
            assertEquals(SavedModelSelection.Unknown, manager.state.value.selection)
            assertFalse(manager.state.value.busy)
        }
    }
    @Test fun legacyFailedReinstallRetainsLoadedModelAndDoesNotUnload() = runTest(timeout = 5.seconds) {
        val store = Store(InstalledModel(pack, "a.gguf", 1)); val backend = Backend()
        val manager = InstalledModelManager(ModelCatalog(listOf(pack)), store, backend)
        manager.select("a")
        store.installAction = { error("synthetic install") }
        assertFailsWith<ModelOperationFailure> { manager.install(pack, "synthetic", 2) }
        assertEquals(listOf("load"), backend.events)
        assertEquals("a", manager.state.value.loadedModelId)
        assertTrue(manager.state.value.metadataNeedsRefresh)
        assertFalse(manager.state.value.busy)
    }
    @Test fun legacyRefreshFailurePreservesCleanupAndUnknownAfterFailedNormalization() = runTest(timeout = 5.seconds) {
        val store = Store(InstalledModel(pack, "a.gguf", 1)); val backend = Backend()
        val manager = InstalledModelManager(ModelCatalog(listOf(pack)), store, backend)
        manager.select("a")
        store.selection = "missing"
        store.onSave = { error("synthetic clear") }
        manager.refresh()
        assertEquals(SavedModelSelection.Unknown, manager.state.value.selection)
        assertNull(manager.state.value.loadedModelId)
        assertEquals(listOf("load", "unload"), backend.events)
        assertNotNull(manager.state.value.error)
        store.selectionRead = { error("synthetic read") }
        manager.refresh()
        assertEquals(SavedModelSelection.Unknown, manager.state.value.selection)
        assertFalse(manager.state.value.busy)
    }
    @Test fun canceledManagerMutexWaiterCannotClearActiveBusy() = runTest(timeout = 5.seconds) {
        val store = Store(InstalledModel(pack, "a.gguf", 1)); val backend = Backend()
        val manager = InstalledModelManager(ModelCatalog(listOf(pack)), store, backend)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        store.beforeRead = { entered.complete(Unit); release.await() }
        val active = launch { manager.refresh() }; entered.await()
        val waiting = launch { manager.select("a") }; runCurrent()
        waiting.cancel(); waiting.join()
        assertTrue(manager.state.value.busy)
        release.complete(Unit); active.join()
        assertFalse(manager.state.value.busy)
    }
    @Test fun legacySelectionCancellationKeepsUnknownAndClearsBusyWithoutCompensation() = runTest(timeout = 5.seconds) {
        val store = Store(InstalledModel(pack, "a.gguf", 1)); val backend = Backend()
        val manager = InstalledModelManager(ModelCatalog(listOf(pack)), store, backend)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        store.onSave = { store.selection = it; entered.complete(Unit); release.await() }
        val job = launch { manager.select("a") }; entered.await()
        job.cancel(); release.complete(Unit); job.join()
        assertTrue(job.isCancelled); assertFalse(manager.state.value.busy)
        assertEquals(SavedModelSelection.Unknown, manager.state.value.selection)
        assertEquals(listOf("load"), backend.events)
    }

}
