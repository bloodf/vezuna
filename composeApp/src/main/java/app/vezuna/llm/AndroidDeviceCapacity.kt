package app.vezuna.llm

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import app.vezuna.localization.L10nKey
import app.vezuna.localization.failMessage

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

class AndroidModelLoadAdmission(
    context: Context,
    nativeBuild: String,
    budgets: Map<ModelLoadPolicyKey, Long> = emptyMap(),
) : ModelLoadAdmission {
    private val appContext = context.applicationContext
    private val admission = BudgetedModelLoadAdmission(nativeBuild, budgets, LoadMemoryMetric.ANDROID_SYSTEM_AVAILABLE_BYTES) {
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: failMessage(L10nKey.MODEL_CAPACITY_UNAVAILABLE)
        val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        LoadMemorySnapshot(info.totalMem, info.availMem, info.lowMemory, info.threshold)
    }

    override fun requireFits(pack: ModelPack, settings: LlmGenerationSettings) = admission.requireFits(pack, settings)
}
