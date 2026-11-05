package app.vezuna.llm

import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.localization.message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class EngineLease
internal enum class EngineReadiness { NotLoaded, KnownLoaded, Uncertain }
internal sealed interface EngineOwnerStatus {
    data object Unbound : EngineOwnerStatus
    data object Superseded : EngineOwnerStatus
    data class Current(val lease: EngineLease, val readiness: EngineReadiness) : EngineOwnerStatus
}
internal enum class NativeStage { Shutdown, Load }
internal enum class LifecycleDisposition { NoEntry, Superseded, ShutdownAcknowledged, LoadAcknowledged, Uncertain }
internal data class VerifiedModelDigest(val sha256: String)
internal sealed interface LifecycleCommand {
    data class Replace(val path: String, val settings: LlmGenerationSettings, val verifiedDigest: VerifiedModelDigest? = null) : LifecycleCommand
    data object Shutdown : LifecycleCommand
}
internal data class LifecycleReceipt(
    val attempt: LifecycleRequest,
    val lease: EngineLease? = null,
    val entered: Boolean = false,
    val lastStage: NativeStage? = null,
    val disposition: LifecycleDisposition = LifecycleDisposition.NoEntry,
)

/** Published once on worker exit, before admission is released. Read after structured invocation exits. */
internal class LifecycleRequest(val command: LifecycleCommand, val expectedLease: EngineLease?) {
    private val terminal = MutableStateFlow(LifecycleReceipt(this))
    val receipt: LifecycleReceipt get() = terminal.value
    internal fun complete(receipt: LifecycleReceipt) { terminal.value = receipt }
}
internal interface ManagedLlmLifecycle {
    val ownerStatus: StateFlow<EngineOwnerStatus>
    suspend fun applyLifecycle(request: LifecycleRequest)
}

/** Only failures classified at an external model-operation boundary are consumed by assistant actions. */
internal class ModelOperationFailure(val userText: UserText, cause: Throwable? = null) : Exception(null, cause)
internal fun sessionSuperseded(): Nothing = throw ModelOperationFailure(message(L10nKey.MODEL_SESSION_SUPERSEDED))
