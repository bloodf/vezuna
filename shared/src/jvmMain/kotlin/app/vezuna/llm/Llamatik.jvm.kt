package app.vezuna.llm

actual fun llamatikLocalLlm(): LocalLlm = JvmUnavailableLocalLlm()

class JvmUnavailableLocalLlm : LocalLlm {
    override suspend fun load(modelPath: String, settings: LlmGenerationSettings) =
        throw UnsupportedOperationException("Llamatik inference is available in Android and iOS app targets")

    override suspend fun generate(prompt: String): String =
        throw UnsupportedOperationException("No local model is loaded in the JVM test target")

    override suspend fun unload() = Unit
}
