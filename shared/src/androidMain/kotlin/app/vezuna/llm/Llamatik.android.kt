package app.vezuna.llm

import com.llamatik.core.platform.GenStream
import com.llamatik.core.platform.LlamaBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
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
    override fun generateStream(prompt: String): Flow<String> = callbackFlow {
        var finished = false
        val callbacks = object : GenStream {
            override fun onDelta(text: String) { trySend(text) }
            override fun onComplete() { finished = true; close() }
            override fun onError(message: String) { finished = true; close(IllegalStateException(message)) }
        }
        launch(Dispatchers.Default) {
            try {
                LlamaBridge.generateStream(prompt, callbacks)
            } catch (error: Throwable) {
                finished = true
                close(error)
            }
        }
        awaitClose { if (!finished) LlamaBridge.nativeCancelGenerate() }
    }.flowOn(Dispatchers.Default)

    override suspend fun unload() = LlamaBridge.shutdown()
}
