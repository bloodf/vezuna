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

/** A budget is valid only for the complete artifact, settings and native build identity. */
data class AndroidLoadPolicyKey(
    val sha256: String,
    val sizeBytes: Long,
    val settings: LlmGenerationSettings,
    val nativeBuild: String,
)

class AndroidModelLoadAdmission(
    context: Context,
    private val nativeBuild: String,
    private val budgets: Map<AndroidLoadPolicyKey, Long> = emptyMap(),
) : ModelLoadAdmission {
    private val appContext = context.applicationContext
    private val approvedBudgets = budgets.toMap()

    override fun requireFits(pack: ModelPack, settings: LlmGenerationSettings) {
        val budget = approvedBudgets[AndroidLoadPolicyKey(pack.sha256, pack.sizeBytes, settings, nativeBuild)]
            ?.takeIf { it > 0 } ?: failMessage(L10nKey.MODEL_LOAD_POLICY_UNAVAILABLE)
        val info = try {
            val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: failMessage(L10nKey.MODEL_CAPACITY_UNAVAILABLE)
            ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        } catch (canceled: kotlinx.coroutines.CancellationException) { throw canceled }
        catch (failure: app.vezuna.localization.VitaMessageException) { throw failure }
        catch (_: Exception) { failMessage(L10nKey.MODEL_CAPACITY_UNAVAILABLE) }
        requireLoadMemory(pack, LoadMemorySnapshot(info.totalMem, info.availMem, info.lowMemory, info.threshold), budget)
    }
}
