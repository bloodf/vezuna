package app.vezuna.llm

import app.vezuna.localization.L10nKey
import app.vezuna.localization.failMessage
import kotlinx.coroutines.CancellationException

interface DeviceCapacity {
    fun totalRamBytes(): Long?
    fun freeStorageBytes(): Long?
}

object UnknownDeviceCapacity : DeviceCapacity {
    override fun totalRamBytes(): Long? = null
    override fun freeStorageBytes(): Long? = null
}

/** A transient policy for this exact artifact and requested settings, evaluated immediately before load. */
fun interface ModelLoadAdmission {
    fun requireFits(pack: ModelPack, settings: LlmGenerationSettings)
}

object UnavailableModelLoadAdmission : ModelLoadAdmission {
    override fun requireFits(pack: ModelPack, settings: LlmGenerationSettings): Nothing =
        failMessage(L10nKey.MODEL_LOAD_POLICY_UNAVAILABLE)
}

data class ModelLoadPolicyKey(
    val sha256: String,
    val sizeBytes: Long,
    val settings: LlmGenerationSettings,
    val nativeBuild: String,
)

class BudgetedModelLoadAdmission(
    private val nativeBuild: String,
    budgets: Map<ModelLoadPolicyKey, Long>,
    private val snapshotProvider: () -> LoadMemorySnapshot,
) : ModelLoadAdmission {
    private val approvedBudgets = budgets.toMap()

    override fun requireFits(pack: ModelPack, settings: LlmGenerationSettings) {
        val budget = approvedBudgets[ModelLoadPolicyKey(pack.sha256, pack.sizeBytes, settings, nativeBuild)]
            ?.takeIf { it > 0 } ?: failMessage(L10nKey.MODEL_LOAD_POLICY_UNAVAILABLE)
        val snapshot = try { snapshotProvider() }
        catch (canceled: CancellationException) { throw canceled }
        catch (failure: app.vezuna.localization.VitaMessageException) { throw failure }
        catch (_: Exception) { failMessage(L10nKey.MODEL_CAPACITY_UNAVAILABLE) }
        requireLoadMemory(pack, snapshot, budget)
    }
}

/** One platform memory reading; budget values must come from an approved artifact-specific policy. */
data class LoadMemorySnapshot(val totalBytes: Long, val availableBytes: Long, val lowMemory: Boolean, val thresholdBytes: Long)

fun requireLoadMemory(pack: ModelPack, snapshot: LoadMemorySnapshot, requiredAvailableBytes: Long) {
    if (requiredAvailableBytes <= 0) failMessage(L10nKey.MODEL_LOAD_POLICY_UNAVAILABLE)
    if (snapshot.totalBytes <= 0 || snapshot.availableBytes < 0 || snapshot.availableBytes > snapshot.totalBytes ||
        snapshot.thresholdBytes <= 0 || snapshot.thresholdBytes > snapshot.totalBytes) failMessage(L10nKey.MODEL_CAPACITY_UNAVAILABLE)
    if (snapshot.totalBytes < pack.minimumRamBytes) {
        failMessage(L10nKey.MODEL_INSUFFICIENT_RAM, pack.minimumRamBytes / 1_000_000, snapshot.totalBytes / 1_000_000)
    }
    if (snapshot.lowMemory || snapshot.availableBytes < requiredAvailableBytes) failMessage(L10nKey.MODEL_MEMORY_PRESSURE)
}

object ModelFitPolicy {
    private const val BYTES_PER_MB = 1_000_000L

    fun requiredFreeBytes(pack: ModelPack): Long {
        val size = pack.sizeBytes
        val fifth = size / 5 + if (size % 5 == 0L) 0 else 1
        if (size > Long.MAX_VALUE - fifth) failMessage(L10nKey.MODEL_CAPACITY_UNAVAILABLE)
        return size + fifth
    }

    fun requiredFreeBytes(pack: ModelPack, delivered: Boolean): Long {
        val copy = requiredFreeBytes(pack)
        if (!delivered && pack.sizeBytes > Long.MAX_VALUE - copy) failMessage(L10nKey.MODEL_CAPACITY_UNAVAILABLE)
        return if (delivered) copy else pack.sizeBytes + copy
    }

    fun requireFits(pack: ModelPack, capacity: DeviceCapacity, delivered: Boolean = false) {
        val needed = requiredFreeBytes(pack, delivered)
        val ram: Long?
        val free: Long?
        try {
            ram = capacity.totalRamBytes()
            free = capacity.freeStorageBytes()
        } catch (canceled: CancellationException) { throw canceled }
        catch (_: Exception) { failMessage(L10nKey.MODEL_CAPACITY_UNAVAILABLE) }
        if (ram == null || ram <= 0 || free == null || free < 0) failMessage(L10nKey.MODEL_CAPACITY_UNAVAILABLE)
        if (ram < pack.minimumRamBytes) failMessage(L10nKey.MODEL_INSUFFICIENT_RAM, pack.minimumRamBytes / BYTES_PER_MB, ram / BYTES_PER_MB)
        if (free < needed) failMessage(L10nKey.MODEL_INSUFFICIENT_STORAGE, needed / BYTES_PER_MB, free / BYTES_PER_MB)
    }
}
