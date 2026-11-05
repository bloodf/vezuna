package app.vezuna.llm

import com.llamatik.core.platform.GenStream
import com.llamatik.core.platform.LlamaBridge
import app.vezuna.localization.L10nKey
import app.vezuna.localization.message
import app.vezuna.localization.failMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

actual fun llamatikLocalLlm(): LocalLlm = LlamatikLocalLlm()

internal interface AndroidLlamaBridge {
    fun params(settings: LlmGenerationSettings)
    fun init(path: String): Boolean
    fun generate(prompt: String): String
    fun stream(prompt: String, callbacks: GenStream)
    fun cancel()
    fun shutdown()
}

// Referencing this delegate does not initialize LlamaBridge. Only an actual production call does.
private object NativeLlamaBridge : AndroidLlamaBridge {
    override fun params(settings: LlmGenerationSettings) = LlamaBridge.updateGenerateParams(
        temperature = settings.temperature, maxTokens = settings.maxTokens, topP = 0.95f,
        topK = 40, repeatPenalty = 1.1f, contextLength = settings.contextLength,
        numThreads = 4, useMmap = true, flashAttention = false, gpuLayers = 0, batchSize = 512,
    )
    override fun init(path: String) = LlamaBridge.initGenerateModel(path)
    override fun generate(prompt: String) = LlamaBridge.generate(prompt)
    override fun stream(prompt: String, callbacks: GenStream) = LlamaBridge.generateStream(prompt, callbacks)
    override fun cancel() = LlamaBridge.nativeCancelGenerate()
    override fun shutdown() = LlamaBridge.shutdown()
}
private val productionOwner by lazy { AndroidEngineOwner(NativeLlamaBridge) }

class LlamatikLocalLlm internal constructor(private val owner: AndroidEngineOwner) : LocalLlm, ManagedLlmLifecycle {
    constructor() : this(productionOwner)
    private val status = MutableStateFlow<EngineOwnerStatus>(EngineOwnerStatus.Unbound)
    // Public construction is retained; the optional protocol and its types stay module-internal.
    @Suppress("EXPOSED_PROPERTY_TYPE")
    override val ownerStatus = status.asStateFlow()
    internal fun publish(value: EngineOwnerStatus) { status.value = value }
    @Suppress("EXPOSED_PARAMETER_TYPE")
    override suspend fun applyLifecycle(request: LifecycleRequest) = owner.lifecycle(this, request)
    override suspend fun load(modelPath: String, settings: LlmGenerationSettings) = applyLifecycle(
        LifecycleRequest(LifecycleCommand.Replace(modelPath, settings), lease()),
    )
    override suspend fun unload() = applyLifecycle(LifecycleRequest(LifecycleCommand.Shutdown, lease()))
    internal fun lease() = (ownerStatus.value as? EngineOwnerStatus.Current)?.lease
    override suspend fun generate(prompt: String): String = owner.generate(this, lease(), prompt)
    override fun generateStream(prompt: String): Flow<String> = owner.stream(this, prompt)
}

/** Hooks only observe scheduling boundaries; tests inject positive barriers without replacing owner logic. */
internal class EngineSchedulingHooks(
    val waiting: () -> Unit = {},
    val acquired: () -> Unit = {},
    val queued: () -> Unit = {},
    val returned: () -> Unit = {},
)
internal class AndroidEngineOwner(
    private val bridge: AndroidLlamaBridge,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val hooks: EngineSchedulingHooks = EngineSchedulingHooks(),
) {
    private val admission = Mutex()
    private val control = Any()
    private var holder: LlamatikLocalLlm? = null
    private var lease: EngineLease? = null
    private var readiness = EngineReadiness.NotLoaded
    private var active: Attempt? = null
    private enum class Phase { Waiting, Calling, Terminal, Returned }
    private class Attempt(val generation: Boolean) {
        var phase = Phase.Waiting
        var canceled = false
        var cancelSent = false
        var cancelFailed = false
        var job: Job? = null
    }

    private fun cancel(attempt: Attempt) = synchronized(control) {
        attempt.canceled = true
        if (active === attempt && attempt.generation && attempt.phase == Phase.Calling && !attempt.cancelSent) {
            attempt.cancelSent = true
            try { bridge.cancel() } catch (_: Exception) { attempt.cancelFailed = true }
        }
    }

    private suspend fun <T> work(attempt: Attempt, body: () -> T): T = coroutineScope {
        hooks.waiting()
        val worker = async(dispatcher) {
            if (!admission.tryLock()) {
                hooks.queued()
                admission.lock()
            }
            try {
                attempt.job = currentCoroutineContext()[Job]
                hooks.acquired()
                currentCoroutineContext().ensureActive()
                try { body() }
                catch (failure: Throwable) {
                    // An entered native failure cannot replace the caller's already requested cancellation.
                    currentCoroutineContext().ensureActive()
                    throw failure
                }
            } finally {
                synchronized(control) {
                    attempt.phase = Phase.Returned
                    if (active === attempt) active = null
                }
                admission.unlock()
                hooks.returned()
            }
        }
        try { worker.await() } catch (canceled: CancellationException) {
            cancel(attempt)
            throw canceled
        }
    }

    private fun calling(attempt: Attempt) {
        attempt.job?.ensureActive()
        if (attempt.canceled) throw CancellationException("Model operation canceled")
        attempt.phase = Phase.Calling
        active = attempt
    }
    private fun publish(wrapper: LlamatikLocalLlm, value: EngineReadiness) {
        readiness = value
        wrapper.publish(EngineOwnerStatus.Current(checkNotNull(lease), value))
    }
    private fun requireReady(wrapper: LlamatikLocalLlm, expected: EngineLease?) {
        if (wrapper.ownerStatus.value == EngineOwnerStatus.Superseded || expected != null && (expected !== lease || holder !== wrapper)) failMessage(L10nKey.MODEL_SESSION_SUPERSEDED)
        if (expected == null || readiness != EngineReadiness.KnownLoaded) {
            failMessage(L10nKey.CHAT_NEEDS_MODEL)
        }
    }

    suspend fun lifecycle(wrapper: LlamatikLocalLlm, request: LifecycleRequest) {
        val attempt = Attempt(false)
        var receipt = LifecycleReceipt(request)
        try {
            work(attempt) {
                try {
                    synchronized(control) {
                        if (wrapper.ownerStatus.value == EngineOwnerStatus.Superseded ||
                            request.expectedLease != null && (holder !== wrapper || request.expectedLease !== lease)) {
                            receipt = receipt.copy(disposition = LifecycleDisposition.Superseded)
                            if (request.command == LifecycleCommand.Shutdown) return@work
                            sessionSuperseded()
                        }
                        if (request.command == LifecycleCommand.Shutdown && wrapper.ownerStatus.value == EngineOwnerStatus.Unbound) return@work
                        // Cancellation and the first Calling/claim transition are one control interval.
                        calling(attempt)
                        if (request.command is LifecycleCommand.Replace) {
                            if (holder !== wrapper) holder?.publish(EngineOwnerStatus.Superseded)
                            holder = wrapper
                            lease = EngineLease()
                            wrapper.publish(EngineOwnerStatus.Current(checkNotNull(lease), EngineReadiness.NotLoaded))
                        }
                        receipt = receipt.copy(lease = lease)
                    }
                    fun shutdown() {
                        receipt = receipt.copy(entered = true, lastStage = NativeStage.Shutdown, disposition = LifecycleDisposition.Uncertain)
                        synchronized(control) { publish(wrapper, EngineReadiness.Uncertain) }
                        bridge.shutdown()
                        receipt = receipt.copy(disposition = LifecycleDisposition.ShutdownAcknowledged)
                        synchronized(control) { publish(wrapper, EngineReadiness.NotLoaded) }
                    }
                    val needsShutdown = request.command == LifecycleCommand.Shutdown || readiness != EngineReadiness.NotLoaded
                    if (needsShutdown) shutdown()
                    if (request.command is LifecycleCommand.Replace) {
                        synchronized(control) {
                            // The first Calling committed at claim. Only a subsequent stage checks cancellation again.
                            if (needsShutdown) calling(attempt)
                            receipt = receipt.copy(entered = true, lastStage = NativeStage.Load, disposition = LifecycleDisposition.Uncertain)
                            publish(wrapper, EngineReadiness.Uncertain)
                        }
                        val command = request.command as LifecycleCommand.Replace
                        bridge.params(command.settings)
                        check(bridge.init(command.path)) { "Model initialization failed" }
                        receipt = receipt.copy(disposition = LifecycleDisposition.LoadAcknowledged)
                        synchronized(control) { publish(wrapper, EngineReadiness.KnownLoaded) }
                    }
                } catch (canceled: CancellationException) {
                    throw canceled
                } catch (expected: ModelOperationFailure) {
                    throw expected
                } catch (failure: Exception) {
                    throw ModelOperationFailure(message(L10nKey.MODEL_LOADING_FAILED), failure)
                } finally {
                    request.complete(receipt)
                }
            }
        } catch (canceled: CancellationException) {
            throw canceled
        }
    }

    suspend fun generate(wrapper: LlamatikLocalLlm, expected: EngineLease?, prompt: String): String {
        val attempt = Attempt(true)
        return work(attempt) {
            synchronized(control) { requireReady(wrapper, expected); calling(attempt) }
            bridge.generate(prompt)
        }
    }

    fun stream(wrapper: LlamatikLocalLlm, prompt: String): Flow<String> = callbackFlow {
        val expected = wrapper.lease() // Captured at collection, before waiting.
        val attempt = Attempt(true)
        val callbacks = object : GenStream {
            override fun onDelta(text: String) {
                val deliver = synchronized(control) { active === attempt && attempt.phase == Phase.Calling && !attempt.canceled }
                if (deliver && !trySend(text).isSuccess) cancel(attempt)
            }
            private fun terminal(error: Throwable? = null) {
                val close = synchronized(control) {
                    if (active === attempt && attempt.phase == Phase.Calling) {
                        attempt.phase = Phase.Terminal
                        true
                    } else false
                }
                if (close) channel.close(error)
            }
            override fun onComplete() = terminal()
            override fun onError(message: String) = terminal(IllegalStateException("Model generation failed"))
        }
        launch {
            try {
                work(attempt) {
                    synchronized(control) { requireReady(wrapper, expected); calling(attempt) }
                    bridge.stream(prompt, callbacks)
                }
                channel.close()
            } catch (failure: Throwable) { channel.close(failure) }
        }
        awaitClose { cancel(attempt) }
    }.buffer(Channel.UNLIMITED)
}
