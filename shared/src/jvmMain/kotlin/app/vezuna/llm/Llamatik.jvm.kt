package app.vezuna.llm

actual fun llamatikLocalLlm(): LocalLlm = JvmUnavailableLocalLlm()

class JvmUnavailableLocalLlm : LocalLlm {
    override suspend fun load(modelPath: String, settings: LlmGenerationSettings) =
        throw UnsupportedOperationException("Llamatik inference is available in Android and iOS app targets")

    override suspend fun generate(prompt: String): String =
        throw UnsupportedOperationException("No local model is loaded in the JVM test target")

    override suspend fun unload() = Unit
}

internal actual class EngineControl actual constructor() {
    private val monitor = Any()
    actual fun <T> withLock(action: () -> T): T = synchronized(monitor, action)
}

internal actual fun productionEngineOwner(): LlamatikEngineOwner =
    throw UnsupportedOperationException("Llamatik inference is available in Android and iOS app targets")
