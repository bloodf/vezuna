package app.vezuna.llm

import com.llamatik.core.platform.GenStream
import com.llamatik.core.platform.LlamaBridge

actual fun llamatikLocalLlm(): LocalLlm = LlamatikLocalLlm()

// Referencing this delegate does not initialize LlamaBridge. Only an actual production call does.
private object NativeLlamaBridge : LlamatikBridge {
    override fun params(settings: LlmGenerationSettings) = LlamaBridge.updateGenerateParams(
        temperature = settings.temperature, maxTokens = settings.maxTokens, topP = 0.95f,
        topK = 40, repeatPenalty = 1.1f, contextLength = settings.contextLength,
        numThreads = 4, useMmap = true, flashAttention = false, gpuLayers = 0, batchSize = 512,
    )
    override fun init(path: String) = LlamaBridge.initGenerateModel(path)
    override fun render(messages: List<Pair<String, String>>) = LlamaBridge.applyChatTemplate(messages, true)
    override fun generate(prompt: String) = LlamaBridge.generate(prompt)
    override fun stream(prompt: String, callbacks: LlamatikStream) = LlamaBridge.generateStream(prompt, object : GenStream {
        override fun onDelta(text: String) = callbacks.onDelta(text)
        override fun onComplete() = callbacks.onComplete()
        override fun onError(message: String) = callbacks.onError(message)
    })
    override fun cancel() = LlamaBridge.nativeCancelGenerate()
    override fun shutdown() = LlamaBridge.shutdown()
}
private val productionOwner by lazy { LlamatikEngineOwner(NativeLlamaBridge) }

internal actual fun productionEngineOwner(): LlamatikEngineOwner = productionOwner

internal actual class EngineControl actual constructor() {
    private val monitor = Any()
    actual fun <T> withLock(action: () -> T): T = synchronized(monitor, action)
}
