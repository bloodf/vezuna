package app.vezuna.llm

import com.llamatik.core.platform.GenStream
import com.llamatik.core.platform.LlamaBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.isActive
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

actual fun llamatikLocalLlm(): LocalLlm = LlamatikLocalLlm()

class LlamatikLocalLlm : LocalLlm {
    override suspend fun load(modelPath: String, settings: LlmGenerationSettings) {
        LlamaBridge.updateGenerateParams(
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            topP = 0.95f,
            topK = 40,
            repeatPenalty = 1.1f,
            contextLength = settings.contextLength,
            numThreads = 4,
            useMmap = true,
            flashAttention = false,
            gpuLayers = 0,
            batchSize = 512,
        )
        check(LlamaBridge.initGenerateModel(modelPath)) { "Llamatik could not load the verified GGUF model" }
    }

    override suspend fun generate(prompt: String): String = LlamaBridge.generate(prompt)
    /**
     * Llamatik's native streaming call blocks the calling thread and reports through [GenStream]; it runs on its own
     * coroutine so cancelling the collector can reach [LlamaBridge.nativeCancelGenerate]. The cancel is sent only while
     * a generation is running: a cancel issued while idle could abort the next generation (sticky flag).
     * A cancel racing the very start of a generation can still be lost; the channel check below narrows that window.
     */
    override fun generateStream(prompt: String): Flow<String> = callbackFlow {
        val run = StreamRun()
        val callbacks = object : GenStream {
            // The buffer is unlimited, so a failed send only means the collector is gone.
            override fun onDelta(text: String) { if (!trySend(text).isSuccess) run.cancelNative() }
            override fun onComplete() { run.finished = true; close() }
            override fun onError(message: String) { run.finished = true; close(IllegalStateException(message)) }
        }
        launch(Dispatchers.Default) {
            if (!isActive) return@launch
            run.started = true
            try {
                try {
                    LlamaBridge.generateStream(prompt, callbacks)
                } finally {
                    // Cleared as soon as the native call returns so a late collector cancel cannot hit the next generation.
                    run.finished = true
                }
                close()
            } catch (error: Throwable) {
                close(error)
            }
        }
        awaitClose { run.cancelNative() }
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.Default)

    override suspend fun unload() = LlamaBridge.shutdown()
}

private class StreamRun {
    @Volatile var started = false
    @Volatile var finished = false

    fun cancelNative() { if (started && !finished) LlamaBridge.nativeCancelGenerate() }
}
