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

    /** Headroom for the verified copy into private storage: the pack size plus a 20% margin. */
    fun requiredFreeBytes(pack: ModelPack): Long = (pack.sizeBytes * 12 + 9) / 10

    /**
     * Before delivery the store keeps its own copy of the pack while it is copied into private storage, so both must fit.
     * With [delivered] the pack is already on disk and only the copy headroom is still needed.
     */
    fun requiredFreeBytes(pack: ModelPack, delivered: Boolean): Long =
        if (delivered) requiredFreeBytes(pack) else pack.sizeBytes + requiredFreeBytes(pack)

    fun requireFits(pack: ModelPack, capacity: DeviceCapacity, delivered: Boolean = false) {
        capacity.totalRamBytes()?.let { ram ->
            if (ram < pack.minimumRamBytes) failMessage(L10nKey.MODEL_INSUFFICIENT_RAM, pack.minimumRamBytes / BYTES_PER_MB, ram / BYTES_PER_MB)
        }
        capacity.freeStorageBytes()?.let { free ->
            val needed = requiredFreeBytes(pack, delivered)
            if (free < needed) failMessage(L10nKey.MODEL_INSUFFICIENT_STORAGE, needed / BYTES_PER_MB, free / BYTES_PER_MB)
        }
    }
}
