package app.vezuna.llm

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs

class AndroidDeviceCapacity(context: Context) : DeviceCapacity {
    private val appContext = context.applicationContext

    override fun totalRamBytes(): Long? {
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        return ActivityManager.MemoryInfo().also(manager::getMemoryInfo).totalMem
    }

    override fun freeStorageBytes(): Long? = try {
        StatFs(appContext.noBackupFilesDir.path).availableBytes
    } catch (error: IllegalArgumentException) {
        null
    }
}
