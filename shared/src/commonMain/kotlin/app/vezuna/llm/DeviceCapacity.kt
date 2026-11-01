package app.vezuna.llm

import app.vezuna.localization.L10nKey
import app.vezuna.localization.failMessage

/** What the device can offer a model; null means the platform could not tell, which never blocks an install. */
interface DeviceCapacity {
    fun totalRamBytes(): Long?
    fun freeStorageBytes(): Long?
}

object UnknownDeviceCapacity : DeviceCapacity {
    override fun totalRamBytes(): Long? = null
    override fun freeStorageBytes(): Long? = null
}

object ModelFitPolicy {
    private const val BYTES_PER_MB = 1_000_000L

    /** Free storage must cover the pack plus a 20% margin for the verified temporary copy. */
    fun requiredFreeBytes(pack: ModelPack): Long = (pack.sizeBytes * 12 + 9) / 10

    fun requireFits(pack: ModelPack, capacity: DeviceCapacity) {
        capacity.totalRamBytes()?.let { ram ->
            if (ram < pack.minimumRamBytes) failMessage(L10nKey.MODEL_INSUFFICIENT_RAM, pack.minimumRamBytes / BYTES_PER_MB, ram / BYTES_PER_MB)
        }
        capacity.freeStorageBytes()?.let { free ->
            val needed = requiredFreeBytes(pack)
            if (free < needed) failMessage(L10nKey.MODEL_INSUFFICIENT_STORAGE, needed / BYTES_PER_MB, free / BYTES_PER_MB)
        }
    }
}
