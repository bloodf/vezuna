package app.vezuna.ios

import app.vezuna.llm.DeviceCapacity
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.Foundation.NSURLVolumeAvailableCapacityForImportantUsageKey

class IosDeviceCapacity : DeviceCapacity {
    override fun totalRamBytes(): Long? = NSProcessInfo.processInfo.physicalMemory.toLong()

    @OptIn(ExperimentalForeignApi::class)
    override fun freeStorageBytes(): Long? {
        val url = NSURL.fileURLWithPath(NSHomeDirectory())
        val values = url.resourceValuesForKeys(listOf(NSURLVolumeAvailableCapacityForImportantUsageKey), null)
        return (values?.get(NSURLVolumeAvailableCapacityForImportantUsageKey) as? NSNumber)?.longLongValue
    }
}
