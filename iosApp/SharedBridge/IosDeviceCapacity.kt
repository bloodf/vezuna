package app.vezuna.ios

import app.vezuna.llm.DeviceCapacity
import app.vezuna.llm.ProcessHeadroomSnapshot
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.Foundation.NSURLVolumeAvailableCapacityForImportantUsageKey

/** Zero means unavailable or exceeded limit. Never interpreted as system pressure. */
interface NativeProcessHeadroom {
    fun headroomBytes(): Long
}

class IosDeviceCapacity(private val processHeadroom: NativeProcessHeadroom? = null) : DeviceCapacity {
    /** Sample on demand; the SDK reading can become stale immediately. */
    fun loadMemorySnapshot(): ProcessHeadroomSnapshot? {
        val headroom = processHeadroom?.headroomBytes() ?: return null
        val total = totalRamBytes() ?: return null
        if (headroom <= 0 || total <= 0) return null
        return ProcessHeadroomSnapshot(total, headroom)
    }

    override fun totalRamBytes(): Long? = NSProcessInfo.processInfo.physicalMemory.toLong()

    @OptIn(ExperimentalForeignApi::class)
    override fun freeStorageBytes(): Long? {
        val url = NSURL.fileURLWithPath(NSHomeDirectory())
        val values = url.resourceValuesForKeys(listOf(NSURLVolumeAvailableCapacityForImportantUsageKey), null)
        return (values?.get(NSURLVolumeAvailableCapacityForImportantUsageKey) as? NSNumber)?.longLongValue
    }
}
