package app.vezuna.llm

import android.content.Context
import app.vezuna.NativeKey
import app.vezuna.NativeMessageException
import app.vezuna.NativeText
import app.vezuna.failNative
import app.vezuna.nativeMessage
import app.vezuna.toNativeText
import app.vezuna.license.AppFeature
import app.vezuna.license.FeatureAccessPolicy
import app.vezuna.license.PremiumLicenseGate
import com.google.android.play.core.assetpacks.AssetPackLocation
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface ModelPackLoadState {
    data object Idle : ModelPackLoadState
    data object CheckingAvailability : ModelPackLoadState
    data class Downloading(val progressPercent: Int) : ModelPackLoadState
    data object WaitingForWifi : ModelPackLoadState
    data class Ready(val modelId: String) : ModelPackLoadState
    data class Unavailable(val reason: NativeText) : ModelPackLoadState
    data class Failed(val message: NativeText) : ModelPackLoadState
}

class PlayAssetPackLoader(
    context: Context,
    private val premiumGate: PremiumLicenseGate,
) {
    private val appContext = context.applicationContext
    private val manager: AssetPackManager by lazy { AssetPackManagerFactory.getInstance(appContext) }
    private val mutableState = MutableStateFlow<ModelPackLoadState>(ModelPackLoadState.Idle)
    val state: StateFlow<ModelPackLoadState> = mutableState.asStateFlow()

    /** Returns the pack file location (used in place, no release needed) of the pack file; the model store streams it into private storage. */
    suspend fun load(pack: ModelPack): ModelPackSource? {
        if (!FeatureAccessPolicy.isAllowed(AppFeature.PREMIUM_AI_MODEL, premiumGate.premiumAllowed())) {
            mutableState.value = ModelPackLoadState.Unavailable(nativeMessage(NativeKey.MODEL_PREMIUM_UNAVAILABLE))
            return null
        }
        mutableState.value = ModelPackLoadState.CheckingAvailability
        return try {
            val location = manager.getPackLocation(pack.androidAssetPackId) ?: fetch(pack.androidAssetPackId)
            if (location == null) {
                mutableState.value = ModelPackLoadState.Unavailable(nativeMessage(NativeKey.MODEL_PACK_UNAVAILABLE))
                return null
            }
            val path = resolvePackFile(location, pack)
            mutableState.value = ModelPackLoadState.Ready(pack.modelId)
            ModelPackSource(path)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            mutableState.value = ModelPackLoadState.Failed(error.toNativeText(NativeKey.MODEL_DELIVERY_FAILED))
            null
        }
    }

    fun clearState() { mutableState.value = ModelPackLoadState.Idle }

    private suspend fun fetch(packId: String): AssetPackLocation? {
        val initial = manager.fetch(listOf(packId)).await()
        updateFrom(initial.packStates()[packId])
        val terminal = initial.packStates()[packId]?.takeIf { it.status() in TERMINAL_STATUSES }
        if (terminal != null && terminal.status() != AssetPackStatus.COMPLETED) {
            failNative(NativeKey.ASSET_PACK_UNAVAILABLE, packId, terminal.status(), terminal.errorCode())
        }
        if (terminal == null) awaitCompletion(packId)
        return manager.getPackLocation(packId)
    }

    private suspend fun awaitCompletion(packId: String) = suspendCancellableCoroutine<Unit> { continuation ->
        lateinit var listener: AssetPackStateUpdateListener
        listener = AssetPackStateUpdateListener { state ->
            if (state.name() != packId) return@AssetPackStateUpdateListener
            updateFrom(state)
            when (state.status()) {
                AssetPackStatus.COMPLETED -> {
                    manager.unregisterListener(listener)
                    if (continuation.isActive) continuation.resume(Unit)
                }
                AssetPackStatus.FAILED,
                AssetPackStatus.CANCELED,
                AssetPackStatus.NOT_INSTALLED,
                -> {
                    manager.unregisterListener(listener)
                    if (continuation.isActive) continuation.resumeWithException(NativeMessageException(nativeMessage(NativeKey.ASSET_PACK_UNAVAILABLE, packId, state.status(), state.errorCode())))
                }
            }
        }
        manager.registerListener(listener)
        continuation.invokeOnCancellation { manager.unregisterListener(listener) }
    }

    private fun updateFrom(state: AssetPackState?) {
        if (state == null) return
        mutableState.value = when (state.status()) {
            AssetPackStatus.WAITING_FOR_WIFI -> ModelPackLoadState.WaitingForWifi
            AssetPackStatus.DOWNLOADING,
            AssetPackStatus.TRANSFERRING,
            AssetPackStatus.PENDING,
            -> {
                val total = state.totalBytesToDownload()
                val progress = if (total > 0) (state.bytesDownloaded() * 100 / total).toInt().coerceIn(0, 100) else 0
                ModelPackLoadState.Downloading(progress)
            }
            else -> ModelPackLoadState.CheckingAvailability
        }
    }

    private suspend fun resolvePackFile(location: AssetPackLocation, pack: ModelPack): String = withContext(Dispatchers.IO) {
        val assets = location.assetsPath() ?: failNative(NativeKey.ASSET_PATH_UNAVAILABLE)
        val root = File(assets).canonicalFile
        val file = File(root, pack.fileName).canonicalFile
        if (file.parentFile != root || !file.isFile) failNative(NativeKey.GGUF_MISSING)
        file.path
    }

    private companion object {
        val TERMINAL_STATUSES = setOf(AssetPackStatus.COMPLETED, AssetPackStatus.FAILED, AssetPackStatus.CANCELED, AssetPackStatus.NOT_INSTALLED)
    }
}
