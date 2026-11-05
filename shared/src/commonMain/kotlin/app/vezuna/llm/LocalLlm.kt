package app.vezuna.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class LlmGenerationSettings(
    val contextLength: Int = 4096,
    val maxTokens: Int = 512,
    val temperature: Float = 0.2f,
)

data class HealthPrompt(val system: String, val userData: String)

interface LocalLlm {
    suspend fun load(modelPath: String, settings: LlmGenerationSettings)
    suspend fun generate(prompt: String): String

    /** Emits answer text increments; cancelling the collector stops generation. Engines without streaming emit one chunk. */
    fun generateStream(prompt: String): Flow<String> = flow { emit(generate(prompt)) }

    fun generateStream(prompt: HealthPrompt): Flow<String> = flow {
        app.vezuna.localization.failMessage(app.vezuna.localization.L10nKey.PROMPT_UNSUPPORTED)
    }

    suspend fun unload()
}

expect fun llamatikLocalLlm(): LocalLlm
