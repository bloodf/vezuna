package app.vezuna.llm

import com.llamatik.core.platform.LlamaBridge

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
    override suspend fun unload() = LlamaBridge.shutdown()
}
