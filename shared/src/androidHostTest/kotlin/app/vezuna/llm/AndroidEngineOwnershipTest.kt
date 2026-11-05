package app.vezuna.llm

import com.llamatik.core.platform.GenStream
import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.domain.Profile
import app.vezuna.localization.ContentLanguage
import app.vezuna.localization.VitaMessageException
import app.vezuna.ui.AppController
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Instant
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidEngineOwnershipTest {
    private val CANDIDATE = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"

    @Test fun healthRenderBudgetAndPolicyAreBoundToLease() = boundedTest {
        fixture { bridge, dispatcher ->
            val llm = LlamatikLocalLlm(AndroidEngineOwner(bridge, dispatcher))
            val store = Store(CANDIDATE); val manager = store.manager(llm)
            manager.select("a", LlmGenerationSettings(contextLength = 512, maxTokens = 100))
            val empty = HealthPrompt("rules", "")
            val overhead = bridge.onRender(listOf("system" to empty.system, "user" to empty.userData))!!.encodeToByteArray().size
            val limit = 512 - 100 - 16 - overhead
            bridge.events.clear()
            llm.generateStream(HealthPrompt("rules", "x".repeat(limit))).collect()
            assertEquals(listOf("render", "stream"), bridge.events.toList())
            bridge.events.clear()
            assertFailsWith<VitaMessageException> { llm.generateStream(HealthPrompt("rules", "x".repeat(limit + 1))).collect() }
            assertEquals(listOf("render"), bridge.events.toList())
            bridge.onRender = { "unexpected" }
            assertFailsWith<VitaMessageException> { llm.generateStream(empty).collect() }
            llm.load("unverified", LlmGenerationSettings())
            bridge.events.clear()
            assertFailsWith<VitaMessageException> { llm.generateStream(empty).collect() }
            assertTrue(bridge.events.isEmpty())
        }
    }

    @Test fun verifiedLoadAndReplacementFenceHealthPolicy() = boundedTest {
        fixture { bridge, dispatcher ->
            val owner = AndroidEngineOwner(bridge, dispatcher)
            val first = LlamatikLocalLlm(owner); val second = LlamatikLocalLlm(owner)
            val store = Store(CANDIDATE); val manager = store.manager(first)
            store.integrity = false
            assertFailsWith<ModelOperationFailure> { manager.select("a") }
            assertTrue(bridge.events.isEmpty())
            store.integrity = true
            manager.select("a")
            first.generateStream(HealthPrompt("rules", "data")).collect()
            store.manager(second).select("a", LlmGenerationSettings(contextLength = 256, maxTokens = 220))
            bridge.events.clear()
            assertFailsWith<VitaMessageException> { second.generateStream(HealthPrompt("rules", "data")).collect() }
            assertFailsWith<VitaMessageException> { first.generateStream(HealthPrompt("rules", "data")).collect() }
            assertFalse(bridge.events.contains("stream"))
        }
    }

    @Test fun cancellationDuringHealthRenderDoesNotEnterOrCancelGeneration() = boundedTest {
        fixture { bridge, dispatcher ->
            val llm = LlamatikLocalLlm(AndroidEngineOwner(bridge, dispatcher))
            Store(CANDIDATE).manager(llm).select("a")
            val render = Barrier(); val original = bridge.onRender
            bridge.onRender = { messages -> render.block(); original(messages) }
            bridge.events.clear()
            val job = launch { llm.generateStream(HealthPrompt("rules", "data")).collect() }
            render.await(); job.cancel(); render.release(); join(job)
            assertEquals(listOf("render"), bridge.events.toList())
        }
    }

    @Test fun injectedAdapterStartsWithoutJni() = boundedTest {
        val calls = mutableListOf<String>()
        val bridge = object : AndroidLlamaBridge {
            override fun params(settings: LlmGenerationSettings) { calls += "params" }
            override fun init(path: String): Boolean { calls += "init"; return true }
            override fun generate(prompt: String): String { calls += "generate"; return "answer" }
            override fun stream(prompt: String, callbacks: GenStream) { callbacks.onComplete() }
            override fun cancel() { error("Unexpected cancel") }
            override fun shutdown() { calls += "shutdown" }
        }
        val adapter = LlamatikLocalLlm(AndroidEngineOwner(bridge))
        adapter.load("synthetic.gguf", LlmGenerationSettings())
        assertEquals("answer", adapter.generate("synthetic"))
        adapter.generateStream("synthetic stream").collect()
        adapter.unload()
        assertEquals(listOf("params", "init", "generate", "shutdown"), calls)
    }
    private fun boundedTest(test: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(5_000) { test() }
    }
    private class Barrier {
        val entered = CompletableDeferred<Unit>()
        private val release = CountDownLatch(1)
        fun block() { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) { "Barrier release timed out" } }
        fun release() = release.countDown()
        suspend fun await() = withTimeout(5_000) { entered.await() }
    }
    private class Bridge : AndroidLlamaBridge {
        val events = CopyOnWriteArrayList<String>()
        var onParams: () -> Unit = {}
        var onInit: () -> Boolean = { true }
        var onShutdown: () -> Unit = {}
        var onGenerate: () -> String = { "answer" }
        var onStream: (GenStream) -> Unit = { it.onDelta("answer"); it.onComplete() }
        var onCancel: () -> Unit = {}
        var onRender: (List<Pair<String, String>>) -> String? = { messages ->
            "<|im_start|>system\n${messages[0].second}<|im_end|>\n<|im_start|>user\n${messages[1].second}<|im_end|>\n<|im_start|>assistant\n"
        }
        override fun render(messages: List<Pair<String, String>>): String? { events += "render"; return onRender(messages) }
        override fun params(settings: LlmGenerationSettings) { events += "params"; onParams() }
        override fun init(path: String): Boolean { events += "init:$path"; return onInit() }
        override fun shutdown() { events += "shutdown"; onShutdown() }
        override fun generate(prompt: String): String { events += "generate"; return onGenerate() }
        override fun stream(prompt: String, callbacks: GenStream) { events += "stream"; onStream(callbacks) }
        override fun cancel() { events += "cancel"; onCancel() }
    }
    private class Store(sha: String = "0".repeat(64)) : ModelFileStore {
        val a = ModelPack("a", "A", "a.gguf", "android-a", "ios-a", 1, 1, 4096, sha)
        val b = a.copy(modelId = "b", fileName = "b.gguf", displayName = "B")
        var entries = listOf(InstalledModel(a, "a.gguf", 1), InstalledModel(b, "b.gguf", 1))
        var selected: String? = null
        val events = CopyOnWriteArrayList<String>()
        var save: suspend (String?) -> Unit = { selected = it }
        var integrity = true
        var installAction: suspend () -> Unit = {}
        var deleteAction: suspend () -> Unit = {}
        var read: suspend () -> Unit = {}
        override suspend fun installed(): List<InstalledModel> { read(); return entries }
        override suspend fun savedSelection() = selected
        override suspend fun saveSelection(modelId: String?) { events += "save:$modelId"; save(modelId) }
        override suspend fun install(model: InstalledModel, sourcePath: String) { events += "install"; installAction() }
        override suspend fun delete(model: InstalledModel) { events += "delete"; deleteAction(); entries = entries.filterNot { it == model }; if (selected == model.pack.modelId) selected = null }
        override suspend fun verifyIntegrity(model: InstalledModel) = integrity
        fun manager(llm: LocalLlm) = InstalledModelManager(ModelCatalog(listOf(a, b)), this, llm)
    }
    private suspend fun join(job: Job) = withContext(NonCancellable) { withTimeout(5_000) { job.join() } }
    private suspend fun signal(channel: Channel<Unit>) = withTimeout(5_000) { channel.receive() }
    private suspend fun fixture(test: suspend CoroutineScope.(Bridge, CoroutineDispatcher) -> Unit) = coroutineScope {
        val dispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val scopeJob = currentCoroutineContext()[Job]!!
        try { test(Bridge(), dispatcher) } finally {
            try {
                withContext(NonCancellable) {
                    withTimeout(5_000) {
                        val children = scopeJob.children.toList()
                        children.forEach { it.cancel() }
                        children.joinAll()
                    }
                }
            } finally { dispatcher.close() }
        }
    }

    @Test fun firstAndSameIdClaimsRevokeOldSessionPermanently() = boundedTest {
        for (id in listOf("a", "b")) fixture { bridge, dispatcher ->
            val owner = AndroidEngineOwner(bridge, dispatcher)
            val a = LlamatikLocalLlm(owner); val b = LlamatikLocalLlm(owner)
            val store = Store(); val ma = store.manager(a); val mb = store.manager(b)
            ma.select("a"); bridge.events.clear(); store.events.clear()
            mb.select(id)
            assertEquals(listOf("shutdown", "params", "init:$id.gguf"), bridge.events.toList())
            assertEquals(EngineOwnerStatus.Superseded, a.ownerStatus.value)
            assertEquals(id, mb.readyModelId())
            bridge.events.clear(); store.events.clear()
            val request = LifecycleRequest(LifecycleCommand.Shutdown, a.lease())
            a.applyLifecycle(request)
            ma.unload()
            assertEquals(LifecycleDisposition.Superseded, request.receipt.disposition)
            assertFailsWith<ModelOperationFailure> { ma.select("a") }
            ma.refresh()
            assertTrue(bridge.events.isEmpty()); assertTrue(store.events.isEmpty())
            assertNull(ma.readyModelId()); assertEquals(id, mb.readyModelId())
        }
    }

    @Test fun canceledQueuedGenerationLoadAndUnloadNeverTouchActiveAttempt() = boundedTest {
        for (kind in listOf("sync", "stream", "load", "unload")) fixture { bridge, dispatcher ->
            val queued = Channel<Unit>(Channel.UNLIMITED)
            val owner = AndroidEngineOwner(bridge, dispatcher, EngineSchedulingHooks(queued = { queued.trySend(Unit) }))
            val llm = LlamatikLocalLlm(owner); llm.load("a", LlmGenerationSettings())
            val native = Barrier(); bridge.onGenerate = { native.block(); bridge.events += "A-return"; "a" }
            val a = launch { llm.generate("A") }; native.await()
            val b = launch {
                when (kind) {
                    "sync" -> llm.generate("B")
                    "stream" -> llm.generateStream("B").collect()
                    "load" -> llm.load("b", LlmGenerationSettings())
                    else -> llm.unload()
                }
            }
            try {
                signal(queued); b.cancel(); join(b)
                assertEquals(0, bridge.events.count { it == "cancel" })
            } finally { native.release(); join(a); join(b) }
            bridge.onGenerate = { "successor" }
            assertEquals("successor", llm.generate("C"))
            assertEquals(listOf("params", "init:a", "generate", "A-return", "generate"), bridge.events.toList())
        }
    }

    @Test fun acquiredBeforeCallingCancellationTransfersNoLease() = boundedTest {
        for (command in listOf(LifecycleCommand.Replace("b", LlmGenerationSettings()), LifecycleCommand.Shutdown)) fixture { bridge, dispatcher ->
            val hook = AtomicReference<Barrier?>()
            val owner = AndroidEngineOwner(bridge, dispatcher, EngineSchedulingHooks(acquired = { hook.get()?.block() }))
            val a = LlamatikLocalLlm(owner); a.load("a", LlmGenerationSettings())
            val previous = a.lease(); val barrier = Barrier(); hook.set(barrier)
            val request = LifecycleRequest(command, previous)
            val job = launch { a.applyLifecycle(request) }
            try { barrier.await(); job.cancel() } finally { hook.set(null); barrier.release(); join(job) }
            assertEquals(LifecycleDisposition.NoEntry, request.receipt.disposition)
            assertTrue(previous === a.lease())
            assertEquals(listOf("params", "init:a"), bridge.events.toList())
            assertEquals("answer", a.generate("C"))
        }
    }

    @Test fun oldCapturedGenerationCannotAdoptReplacementLease() = boundedTest {
        for (stream in listOf(false, true)) fixture { bridge, dispatcher ->
            val hook = AtomicReference<Barrier?>()
            val owner = AndroidEngineOwner(bridge, dispatcher, EngineSchedulingHooks(waiting = { hook.get()?.block() }))
            val llm = LlamatikLocalLlm(owner); llm.load("a", LlmGenerationSettings())
            val barrier = Barrier(); hook.set(barrier)
            val stale = async(dispatcher) { runCatching { if (stream) llm.generateStream("old").collect() else llm.generate("old") } }
            try {
                barrier.await(); hook.set(null)
                llm.load("a", LlmGenerationSettings())
            } finally { hook.set(null); barrier.release() }
            assertTrue(withTimeout(5_000) { stale.await() }.exceptionOrNull() is VitaMessageException)
            assertEquals("answer", llm.generate("new"))
            assertEquals(1, bridge.events.count { it == "generate" })
            assertEquals(0, bridge.events.count { it == "stream" })
        }
    }

    @Test fun capturedGenerationRejectsDistinctWrapperClaimForBothStylesAndIds() = boundedTest {
        for (stream in listOf(false, true)) for (id in listOf("a", "b")) fixture { bridge, dispatcher ->
            val hook = AtomicReference<Barrier?>()
            val owner = AndroidEngineOwner(bridge, dispatcher, EngineSchedulingHooks(waiting = { hook.get()?.block() }))
            val a = LlamatikLocalLlm(owner); val b = LlamatikLocalLlm(owner)
            val store = Store(); val ma = store.manager(a); val mb = store.manager(b)
            ma.select("a"); bridge.events.clear()
            val barrier = Barrier(); hook.set(barrier)
            val stale = async(dispatcher) { runCatching { if (stream) a.generateStream("old").collect() else a.generate("old") } }
            try {
                barrier.await(); hook.set(null)
                mb.select(id)
            } finally { hook.set(null); barrier.release() }
            assertTrue(withTimeout(5_000) { stale.await() }.exceptionOrNull() is VitaMessageException)
            assertNull(ma.readyModelId()); assertEquals(id, mb.readyModelId())
            assertEquals(listOf("shutdown", "params", "init:$id.gguf"), bridge.events.toList())
            if (stream) {
                val chunks = mutableListOf<String>()
                b.generateStream("new").collect { chunks += it }
                assertEquals(listOf("answer"), chunks)
            } else assertEquals("answer", b.generate("new"))
            assertEquals(listOf("shutdown", "params", "init:$id.gguf", if (stream) "stream" else "generate"), bridge.events.toList())
            assertEquals(id, mb.readyModelId())
        }
    }

    @Test fun lifecycleFailuresRemainUncertainUntilOrdinaryShutdown() = boundedTest {
        for (failure in listOf("params", "false", "init", "shutdown")) fixture { bridge, dispatcher ->
            val llm = LlamatikLocalLlm(AndroidEngineOwner(bridge, dispatcher)); llm.load("a", LlmGenerationSettings())
            bridge.events.clear()
            when (failure) {
                "params" -> bridge.onParams = { error("synthetic") }
                "false" -> bridge.onInit = { false }
                "init" -> bridge.onInit = { error("synthetic") }
                else -> bridge.onShutdown = { error("synthetic") }
            }
            val request = LifecycleRequest(LifecycleCommand.Replace("b", LlmGenerationSettings()), llm.lease())
            assertFailsWith<ModelOperationFailure> { llm.applyLifecycle(request) }
            assertEquals(LifecycleDisposition.Uncertain, request.receipt.disposition)
            assertEquals(if (failure == "shutdown") NativeStage.Shutdown else NativeStage.Load, request.receipt.lastStage)
            assertEquals(EngineReadiness.Uncertain, (llm.ownerStatus.value as EngineOwnerStatus.Current).readiness)
            assertFailsWith<VitaMessageException> { llm.generate("reject") }
            if (failure == "shutdown") assertEquals(listOf("shutdown"), bridge.events.toList())
            bridge.onShutdown = {}; llm.unload()
            assertEquals(EngineReadiness.NotLoaded, (llm.ownerStatus.value as EngineOwnerStatus.Current).readiness)
        }
    }

    @Test fun canceledEnteredLoadAndShutdownReconcileReceiptsAndBusy() = boundedTest {
        for (stage in listOf("init", "shutdown")) fixture { bridge, dispatcher ->
            val llm = LlamatikLocalLlm(AndroidEngineOwner(bridge, dispatcher)); val store = Store(); val manager = store.manager(llm)
            manager.select("a"); store.events.clear()
            val native = Barrier()
            if (stage == "init") bridge.onInit = { native.block(); true } else bridge.onShutdown = { native.block() }
            val job = launch { manager.select("b") }
            try { native.await(); job.cancel() } finally { native.release(); join(job) }
            assertTrue(job.isCancelled); assertFalse(manager.state.value.busy)
            assertEquals(if (stage == "init") "b" else null, manager.state.value.loadedModelId)
            assertEquals("a", manager.state.value.selectedModelId)
            assertTrue(store.events.isEmpty()); assertEquals(0, bridge.events.count { it == "cancel" })
            assertEquals(if (stage == "init") "b" else null, manager.readyModelId())
        }
    }

    @Test fun cancelOnceThrowingAndTerminalCallbacksRetainAdmission() = boundedTest {
        for (terminal in listOf("none", "complete", "error")) fixture { bridge, dispatcher ->
            val queued = Channel<Unit>(Channel.UNLIMITED); val cancelEntered = CompletableDeferred<Unit>()
            val owner = AndroidEngineOwner(bridge, dispatcher, EngineSchedulingHooks(queued = { queued.trySend(Unit) }))
            val llm = LlamatikLocalLlm(owner); llm.load("a", LlmGenerationSettings())
            val native = Barrier(); var retained: GenStream? = null
            bridge.onCancel = { cancelEntered.complete(Unit); error("synthetic cancel failure") }
            bridge.onStream = { callbacks ->
                retained = callbacks
                if (terminal == "complete") callbacks.onComplete() else if (terminal == "error") callbacks.onError("synthetic")
                native.block(); bridge.events += "A-return"
            }
            val a = launch { runCatching { llm.generateStream("A").collect() } }; native.await()
            val successorNative = Barrier()
            bridge.onGenerate = { successorNative.block(); "answer" }
            val b = async { llm.generate("B") }
            try {
                signal(queued); a.cancel()
                if (terminal == "none") withTimeout(5_000) { cancelEntered.await() }
                retained!!.onDelta("late"); retained!!.onComplete(); retained!!.onError("late")
            } finally { native.release(); join(a) }
            try {
                successorNative.await()
                retained!!.onDelta("stale"); retained!!.onError("stale")
            } finally { successorNative.release() }
            assertEquals("answer", withTimeout(5_000) { b.await() })
            assertEquals(if (terminal == "none") 1 else 0, bridge.events.count { it == "cancel" })
            assertTrue(bridge.events.indexOf("A-return") < bridge.events.indexOf("generate"))
        }
    }

    @Test fun saveFailureRetainsAndroidLoadedAcknowledgementAndUnknownSelection() = boundedTest {
        for (mutation in listOf(false, true)) fixture { bridge, dispatcher ->
            val llm = LlamatikLocalLlm(AndroidEngineOwner(bridge, dispatcher)); val store = Store(); val manager = store.manager(llm)
            manager.select("a"); bridge.events.clear()
            store.save = { if (mutation) store.selected = it; error("synthetic save failure") }
            assertFailsWith<ModelOperationFailure> { manager.select("b") }
            assertEquals(SavedModelSelection.Unknown, manager.state.value.selection)
            assertEquals("b", manager.state.value.loadedModelId); assertEquals("b", manager.readyModelId())
            assertEquals(listOf("shutdown", "params", "init:b.gguf"), bridge.events.toList())
            store.save = { store.selected = it }; manager.refresh()
            assertEquals(SavedModelSelection.Known(if (mutation) "b" else "a"), manager.state.value.selection)
            assertFalse(manager.state.value.busy)
        }
    }

    @Test fun actualControllerProfileSwitchWaitsForNativeReturnAndDiscardsA() = boundedTest {
        fixture { bridge, dispatcher ->
            val queued = Channel<Unit>(Channel.UNLIMITED); val canceled = CompletableDeferred<Unit>()
            val llm = LlamatikLocalLlm(AndroidEngineOwner(bridge, dispatcher, EngineSchedulingHooks(queued = { queued.trySend(Unit) })))
            val store = Store(CANDIDATE); store.selected = "a"; val manager = store.manager(llm)
            val repository = InMemoryVezunaRepository()
            val now = Instant.parse("2026-01-01T00:00:00Z")
            repository.createProfile(Profile("A", "A", createdAt = now)); repository.createProfile(Profile("B", "B", createdAt = now))
            val controller = AppController(repository, manager, LocalHealthChat(repository, llm) { ContentLanguage.EN })
            controller.initialize(); controller.switchProfile("A")
            val native = Barrier(); val successor = Barrier(); val retained = AtomicReference<GenStream>()
            bridge.onCancel = { canceled.complete(Unit) }
            var first = true
            bridge.onStream = { cb ->
                if (first) { first = false; retained.set(cb); native.block(); cb.onDelta("A-secret"); cb.onComplete(); bridge.events += "A-return" }
                else { bridge.events += "B-entry"; cb.onDelta("B-answer"); successor.block(); cb.onComplete() }
            }
            val a = launch { controller.ask("A-question") }; native.await()
            controller.switchProfile("B")
            val visible = CopyOnWriteArrayList<String>()
            val observer = launch(Dispatchers.Unconfined) {
                controller.state.collect { state ->
                    if (state.data?.profile?.profileId == "B") {
                        visible += state.streamingAnswer.orEmpty()
                        visible.addAll(state.data?.chat.orEmpty().map { it.content })
                    }
                }
            }
            val b = async { controller.ask("B-question") }
            try { withTimeout(5_000) { canceled.await() }; signal(queued) }
            finally { native.release(); join(a) }
            try {
                successor.await()
                withTimeout(5_000) { controller.state.first { it.streamingAnswer == "B-answer" } }
                retained.get().onDelta("A-late-secret")
                retained.get().onError("A-late-error")
                retained.get().onComplete()
                yield()
                assertEquals("B", controller.state.value.data?.profile?.profileId)
                assertEquals("B-answer", controller.state.value.streamingAnswer)
                assertTrue(controller.state.value.data?.chat.orEmpty().isEmpty())
                assertTrue(visible.none { it.contains("A-secret") || it.contains("A-late") })
            } finally { successor.release(); observer.cancel(); join(observer) }
            assertTrue(withTimeout(5_000) { b.await() })
            assertEquals("B", controller.state.value.data?.profile?.profileId)
            assertEquals(listOf("B-question", "B-answer"), repository.readProfile("B").chat.map { it.content })
            assertTrue(repository.readProfile("A").chat.isEmpty())
            assertTrue(bridge.events.indexOf("A-return") < bridge.events.indexOf("B-entry"))
        }
    }

    @Test fun canceledBeforeWorkerStartSkipsEveryOperationKind() = boundedTest {
        for (kind in listOf("load", "unload", "sync", "stream")) {
            val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val bridge = Bridge(); val waiting = Channel<Unit>(Channel.UNLIMITED)
            val owner = AndroidEngineOwner(bridge, dispatcher, EngineSchedulingHooks(waiting = { waiting.trySend(Unit) }))
            val llm = LlamatikLocalLlm(owner)
            val parked = Barrier()
            try {
                llm.load("a", LlmGenerationSettings()); signal(waiting)
                val occupant = launch(dispatcher) { parked.block() }; parked.await()
                val job = launch {
                    when (kind) {
                        "load" -> llm.load("b", LlmGenerationSettings())
                        "unload" -> llm.unload()
                        "sync" -> llm.generate("B")
                        else -> llm.generateStream("B").collect()
                    }
                }
                try { signal(waiting); job.cancel() }
                finally { parked.release(); join(occupant); join(job) }
                assertEquals(listOf("params", "init:a"), bridge.events.toList())
                assertEquals("answer", llm.generate("C"))
            } finally { parked.release(); dispatcher.close() }
        }
    }

    @Test fun synchronousCancelReachesNativeAndFencesSuccessorThroughCancelReturn() = boundedTest {
        fixture { bridge, dispatcher ->
            val queued = Channel<Unit>(Channel.UNLIMITED)
            val llm = LlamatikLocalLlm(AndroidEngineOwner(bridge, dispatcher, EngineSchedulingHooks(queued = { queued.trySend(Unit) })))
            llm.load("a", LlmGenerationSettings())
            val native = Barrier(); val cancel = Barrier(); val nativeReturned = CompletableDeferred<Unit>()
            bridge.onGenerate = { native.block(); bridge.events += "A-return"; nativeReturned.complete(Unit); "A" }
            bridge.onCancel = { cancel.block(); bridge.events += "cancel-return" }
            val a = launch(dispatcher) { llm.generate("A") }; native.await()
            val cancelJob = launch(dispatcher) { a.cancel() }
            val b = async { llm.generate("B") }
            try {
                cancel.await(); signal(queued)
                native.release(); withTimeout(5_000) { nativeReturned.await() }
                bridge.onGenerate = { "B" }
            } finally { cancel.release(); native.release(); join(cancelJob); join(a) }
            assertEquals("B", withTimeout(5_000) { b.await() })
            assertEquals(1, bridge.events.count { it == "cancel" })
            assertEquals(listOf("params", "init:a", "generate", "cancel", "A-return", "cancel-return", "generate"), bridge.events.toList())
        }
    }

    @Test fun acknowledgedCanceledReceiptCannotReviveSupersededManager() = boundedTest {
        fixture { bridge, dispatcher ->
            val afterReturn = AtomicReference<Barrier?>()
            val owner = AndroidEngineOwner(bridge, dispatcher, EngineSchedulingHooks(returned = { afterReturn.getAndSet(null)?.block() }))
            val a = LlamatikLocalLlm(owner); val b = LlamatikLocalLlm(owner)
            val store = Store(); val ma = store.manager(a); val mb = store.manager(b)
            ma.select("a")
            val init = Barrier(); val reconcile = Barrier()
            bridge.onInit = { init.block(); true }
            val job = launch { ma.select("b") }
            try {
                init.await(); job.cancel(); afterReturn.set(reconcile); init.release(); reconcile.await()
                bridge.onInit = { true }; mb.select("a")
            } finally { init.release(); reconcile.release(); join(job) }
            assertTrue(job.isCancelled); assertEquals("b", ma.state.value.loadedModelId)
            assertNull(ma.readyModelId()); assertEquals(EngineOwnerStatus.Superseded, a.ownerStatus.value)
            assertEquals("a", mb.readyModelId()); assertFalse(ma.state.value.busy)
        }
    }

    @Test fun canceledEnteredFailuresPreserveCancellationAndUncertainty() = boundedTest {
        for (stage in listOf("params", "init", "false", "shutdown")) fixture { bridge, dispatcher ->
            val llm = LlamatikLocalLlm(AndroidEngineOwner(bridge, dispatcher)); val store = Store(); val manager = store.manager(llm)
            manager.select("a"); val barrier = Barrier()
            when (stage) {
                "params" -> bridge.onParams = { barrier.block(); error("synthetic") }
                "init" -> bridge.onInit = { barrier.block(); error("synthetic") }
                "false" -> bridge.onInit = { barrier.block(); false }
                else -> bridge.onShutdown = { barrier.block(); error("synthetic") }
            }
            val observed = AtomicReference<CancellationException?>()
            val original = CancellationException("operator stop")
            val job = launch {
                try { manager.select("b") }
                catch (canceled: CancellationException) { observed.set(canceled); throw canceled }
            }
            try { barrier.await(); job.cancel(original) } finally { barrier.release(); join(job) }
            assertTrue(job.isCancelled); assertTrue(observed.get() === original || observed.get()?.cause === original, "Original cancellation must survive coroutine stack-trace recovery"); assertFalse(manager.state.value.busy)
            assertNull(manager.readyModelId()); assertNull(manager.state.value.loadedModelId)
            assertEquals(EngineReadiness.Uncertain, (llm.ownerStatus.value as EngineOwnerStatus.Current).readiness)
            assertEquals(0, bridge.events.count { it == "cancel" })
        }
    }

    @Test fun unboundShutdownAndGenerationHaveNoNativeEffects() = boundedTest {
        fixture { bridge, dispatcher ->
            val llm = LlamatikLocalLlm(AndroidEngineOwner(bridge, dispatcher))
            val request = LifecycleRequest(LifecycleCommand.Shutdown, null)
            llm.applyLifecycle(request)
            assertEquals(LifecycleDisposition.NoEntry, request.receipt.disposition)
            assertEquals(EngineOwnerStatus.Unbound, llm.ownerStatus.value)
            assertFailsWith<VitaMessageException> { llm.generate("reject") }
            assertFailsWith<VitaMessageException> { llm.generateStream("reject").collect() }
            assertTrue(bridge.events.isEmpty())
        }
    }

    @Test fun hiddenSaveReturnLeavesUnknownWithoutCompensatingNativeOrStorageEffects() = boundedTest {
        fixture { bridge, dispatcher ->
            val llm = LlamatikLocalLlm(AndroidEngineOwner(bridge, dispatcher)); val store = Store(); val manager = store.manager(llm)
            manager.select("a"); bridge.events.clear(); store.events.clear()
            val saved = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            store.save = { store.selected = it; saved.complete(Unit); release.await() }
            val job = launch { manager.select("b") }
            try { withTimeout(5_000) { saved.await() }; job.cancel() }
            finally { release.complete(Unit); join(job) }
            assertTrue(job.isCancelled); assertFalse(manager.state.value.busy)
            assertEquals(SavedModelSelection.Unknown, manager.state.value.selection)
            assertEquals("b", manager.readyModelId()); assertEquals("b", store.selected)
            assertEquals(listOf("shutdown", "params", "init:b.gguf"), bridge.events.toList())
            assertEquals(listOf("save:b"), store.events.toList())
            store.read = { error("synthetic read") }; manager.refresh()
            assertEquals(SavedModelSelection.Unknown, manager.state.value.selection)
            assertEquals("b", manager.readyModelId())
            store.read = {}; store.save = { store.selected = it }; manager.select("b")
            assertEquals(SavedModelSelection.Known("b"), manager.state.value.selection)
        }
    }

    @Test fun alreadyEnteredStorageMutationCanFinishAfterSuccessorSave() = boundedTest {
        fixture { bridge, dispatcher ->
            val owner = AndroidEngineOwner(bridge, dispatcher); val store = Store()
            val a = LlamatikLocalLlm(owner); val b = LlamatikLocalLlm(owner)
            val ma = store.manager(a); val mb = store.manager(b)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            store.save = { id -> if (id == "a") { entered.complete(Unit); release.await() }; store.selected = id }
            val old = launch { ma.select("a") }
            try {
                withTimeout(5_000) { entered.await() }; mb.select("b")
                assertEquals("b", store.selected)
            } finally { release.complete(Unit); join(old) }
            // Accepted storage ordering limit: last acknowledgement is not newest-engine-owner-wins.
            assertEquals("a", store.selected)
            assertEquals(SavedModelSelection.Known("a"), ma.state.value.selection)
            assertEquals(SavedModelSelection.Known("b"), mb.state.value.selection)
            assertNull(ma.readyModelId()); assertEquals("b", mb.readyModelId())
            store.events.clear()
            assertFailsWith<ModelOperationFailure> { ma.delete("a") }
            assertFailsWith<ModelOperationFailure> { ma.install(store.a, "synthetic", 2) }
            assertTrue(store.events.isEmpty())
        }
    }

    @Test fun fileMutationFailuresRetainRefreshRequirementAndDisableRelevantLoadedFile() = boundedTest {
        for (kind in listOf("install", "delete")) for (outcome in listOf("before", "after", "cancel", "readback")) fixture { bridge, dispatcher ->
            val llm = LlamatikLocalLlm(AndroidEngineOwner(bridge, dispatcher)); val store = Store(); val manager = store.manager(llm)
            manager.select("a"); bridge.events.clear(); store.events.clear()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val effect: suspend () -> Unit = {
                if (outcome == "before") error("synthetic before mutation")
                store.entries = store.entries.filterNot { it.pack.modelId == "a" }
                if (kind == "delete") store.selected = null
                when (outcome) {
                    "after" -> error("synthetic after mutation")
                    "cancel" -> { entered.complete(Unit); release.await() }
                    "readback" -> store.read = { error("synthetic readback") }
                }
            }
            if (kind == "install") store.installAction = effect else store.deleteAction = effect
            val result = CompletableDeferred<Throwable?>()
            val job = launch {
                try { if (kind == "install") manager.install(store.a, "synthetic", 2) else manager.delete("a"); result.complete(null) }
                catch (failure: Throwable) { result.complete(failure); if (failure is CancellationException) throw failure }
            }
            try { if (outcome == "cancel") { withTimeout(5_000) { entered.await() }; job.cancel() } }
            finally { release.complete(Unit); join(job) }
            assertTrue(withTimeout(5_000) { result.await() } != null)
            assertFalse(manager.state.value.busy); assertTrue(manager.state.value.metadataNeedsRefresh)
            assertNull(manager.readyModelId()); assertEquals(listOf("shutdown"), bridge.events.toList())
            if (kind == "delete" && outcome != "readback") assertEquals(SavedModelSelection.Unknown, manager.state.value.selection)
        }
    }

    @Test fun canceledQueuedManagerOperationsClearBusyWithoutCancelingActiveGeneration() = boundedTest {
        for (kind in listOf("select", "refresh", "unload")) fixture { bridge, dispatcher ->
            val queued = Channel<Unit>(Channel.UNLIMITED)
            val llm = LlamatikLocalLlm(AndroidEngineOwner(bridge, dispatcher, EngineSchedulingHooks(queued = { queued.trySend(Unit) })))
            val store = Store(); val manager = store.manager(llm); manager.select("a")
            bridge.events.clear(); store.events.clear()
            val native = Barrier(); bridge.onGenerate = { native.block(); "A" }
            val a = launch { llm.generate("A") }; native.await()
            if (kind == "refresh") store.selected = "b"
            val waiter = launch { when (kind) { "select" -> manager.select("b"); "refresh" -> manager.refresh(); else -> manager.unload() } }
            try {
                signal(queued); assertTrue(manager.state.value.busy)
                waiter.cancel(); join(waiter)
                assertFalse(manager.state.value.busy)
                assertEquals(listOf("generate"), bridge.events.toList())
                assertTrue(store.events.isEmpty()); assertEquals("a", manager.readyModelId())
            } finally { native.release(); join(a); join(waiter) }
            manager.select("b"); assertEquals("b", manager.readyModelId())
        }
    }

    @Test fun newWrapperReplacementWaitsThroughOldStreamActualReturn() = boundedTest {
        fixture { bridge, dispatcher ->
            val queued = Channel<Unit>(Channel.UNLIMITED)
            val owner = AndroidEngineOwner(bridge, dispatcher, EngineSchedulingHooks(queued = { queued.trySend(Unit) }))
            val a = LlamatikLocalLlm(owner); val b = LlamatikLocalLlm(owner)
            a.load("a", LlmGenerationSettings()); bridge.events.clear()
            val native = Barrier(); bridge.onStream = { native.block(); bridge.events += "A-return" }
            val old = launch { a.generateStream("A").collect() }; native.await()
            val successor = async { b.load("b", LlmGenerationSettings()) }
            try { signal(queued) } finally { native.release(); join(old) }
            withTimeout(5_000) { successor.await() }
            assertEquals(listOf("stream", "A-return", "shutdown", "params", "init:b"), bridge.events.toList())
            assertEquals(EngineOwnerStatus.Superseded, a.ownerStatus.value)
        }
    }

    @Test fun nativeReturnWithoutTerminalAndThrownGenerationReleaseAdmission() = boundedTest {
        fixture { bridge, dispatcher ->
            val llm = LlamatikLocalLlm(AndroidEngineOwner(bridge, dispatcher)); llm.load("a", LlmGenerationSettings())
            bridge.onStream = { it.onDelta("chunk") }
            val chunks = mutableListOf<String>(); llm.generateStream("A").collect { chunks += it }
            assertEquals(listOf("chunk"), chunks)
            bridge.onStream = { error("synthetic stream") }
            assertFailsWith<IllegalStateException> { llm.generateStream("fail").collect() }
            bridge.onGenerate = { error("synthetic generation") }
            assertFailsWith<IllegalStateException> { llm.generate("fail") }
            bridge.onGenerate = { "successor" }; assertEquals("successor", llm.generate("C"))
        }
    }

    @Test fun failedNormalizationAndHiddenClearReturnStayUnknownThenExplicitRefreshObservesNone() = boundedTest {
        for (outcome in listOf("before", "after", "cancel")) fixture { bridge, dispatcher ->
            val llm = LlamatikLocalLlm(AndroidEngineOwner(bridge, dispatcher)); val store = Store(); val manager = store.manager(llm)
            store.selected = "missing"
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            store.save = {
                if (outcome == "before") error("synthetic clear")
                store.selected = it
                if (outcome == "after") error("synthetic after clear")
                entered.complete(Unit); release.await()
            }
            val job = launch { manager.refresh() }
            try { if (outcome == "cancel") { withTimeout(5_000) { entered.await() }; job.cancel() } }
            finally { release.complete(Unit); join(job) }
            assertEquals(SavedModelSelection.Unknown, manager.state.value.selection)
            assertFalse(manager.state.value.busy); assertNull(manager.readyModelId())
            assertTrue(bridge.events.isEmpty())
            store.save = { store.selected = it }; manager.refresh()
            assertEquals(SavedModelSelection.Known(null), manager.state.value.selection)
            assertEquals(EngineOwnerStatus.Unbound, llm.ownerStatus.value)
        }
    }

    @Test fun acquiredGenerationCancellationAndAcknowledgedDeleteKeepTruthfulProjections() = boundedTest {
        for (stream in listOf(false, true)) fixture { bridge, dispatcher ->
            val hook = AtomicReference<Barrier?>()
            val llm = LlamatikLocalLlm(AndroidEngineOwner(bridge, dispatcher, EngineSchedulingHooks(acquired = { hook.get()?.block() })))
            val store = Store(); val manager = store.manager(llm); manager.select("a")
            bridge.events.clear()
            val barrier = Barrier(); hook.set(barrier)
            val job = launch { if (stream) llm.generateStream("cancel").collect() else llm.generate("cancel") }
            try { barrier.await(); job.cancel() } finally { hook.set(null); barrier.release(); join(job) }
            assertTrue(bridge.events.isEmpty()); assertEquals("a", manager.readyModelId())
            manager.delete("a")
            assertEquals(SavedModelSelection.Known(null), manager.state.value.selection)
            assertFalse(manager.state.value.metadataNeedsRefresh); assertNull(manager.readyModelId())
            assertEquals(listOf("shutdown"), bridge.events.toList())
        }
    }

    @Test fun deleteInternalSelectionClearIsUnknownWithoutAnyExplicitSelectionSave() = boundedTest {
        for (outcome in listOf("before", "after", "cancel", "acknowledged")) fixture { bridge, dispatcher ->
            val llm = LlamatikLocalLlm(AndroidEngineOwner(bridge, dispatcher)); val store = Store(); val manager = store.manager(llm)
            manager.select("b")
            // Another manager changed storage after this manager's last acknowledged observation.
            store.selected = "a"; store.events.clear(); bridge.events.clear()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            store.deleteAction = {
                if (outcome == "before") error("synthetic before delete")
                store.selected = null
                if (outcome == "after") error("synthetic after internal clear")
                if (outcome == "cancel") { entered.complete(Unit); release.await() }
            }
            val job = launch { try { manager.delete("a") } catch (_: ModelOperationFailure) {} }
            try { if (outcome == "cancel") { withTimeout(5_000) { entered.await() }; job.cancel() } }
            finally { release.complete(Unit); join(job) }
            assertEquals(listOf("delete"), store.events.toList())
            assertTrue(bridge.events.isEmpty()); assertEquals("b", manager.readyModelId())
            assertEquals(if (outcome == "acknowledged") SavedModelSelection.Known("b") else SavedModelSelection.Unknown, manager.state.value.selection)
            assertEquals(outcome != "acknowledged", manager.state.value.metadataNeedsRefresh)
        }
    }

}
