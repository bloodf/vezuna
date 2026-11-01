package app.vezuna.llm

data class LlmGenerationSettings(
    val contextLength: Int = 4096,
    val maxTokens: Int = 512,
    val temperature: Float = 0.2f,
)

interface LocalLlm {
    suspend fun load(modelPath: String, settings: LlmGenerationSettings)
    suspend fun generate(prompt: String): String
    suspend fun unload()
}

expect fun llamatikLocalLlm(): LocalLlm
