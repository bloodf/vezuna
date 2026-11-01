package app.vezuna.llm

import android.app.Activity
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
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

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
    @Volatile private var activityRef: WeakReference<Activity>? = null

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

    /** Activity used to show Play confirmation dialogs; held weakly so the loader can outlive recreation. */
    fun bindActivity(activity: Activity?) { activityRef = activity?.let(::WeakReference) }

    private suspend fun fetch(packId: String): AssetPackLocation? {
        val done = CompletableDeferred<Unit>()
        val shown = AtomicBoolean(false)
        val listener = AssetPackStateUpdateListener { state -> if (state.name() == packId) handle(state, packId, done, shown) }
        manager.registerListener(listener)
        try {
            val finished = withTimeoutOrNull(AssetPackStatusPolicy.FETCH_TIMEOUT_MS) {
                // Listener is registered before fetch; states are re-read afterwards to catch updates emitted in between.
                manager.fetch(listOf(packId)).await().packStates()[packId]?.let { handle(it, packId, done, shown) }
                manager.getPackStates(listOf(packId)).await().packStates()[packId]?.let { handle(it, packId, done, shown) }
                done.await()
            }
            if (finished == null) failNative(NativeKey.MODEL_DELIVERY_FAILED)
        } finally {
            manager.unregisterListener(listener)
        }
        return manager.getPackLocation(packId)
    }

    private fun handle(state: AssetPackState, packId: String, done: CompletableDeferred<Unit>, shown: AtomicBoolean) {
        updateFrom(state)
        when (AssetPackStatusPolicy.actionFor(state.status())) {
            AssetPackAction.COMPLETE -> done.complete(Unit)
            AssetPackAction.FAIL -> done.completeExceptionally(
                NativeMessageException(nativeMessage(NativeKey.ASSET_PACK_UNAVAILABLE, packId, state.status(), state.errorCode())),
            )
            AssetPackAction.CONFIRM_USER -> confirm(done, shown) { manager.showConfirmationDialog(it) }
            AssetPackAction.CONFIRM_CELLULAR -> confirm(done, shown) { manager.showCellularDataConfirmation(it) }
            AssetPackAction.AWAIT -> Unit
        }
    }

    private fun confirm(done: CompletableDeferred<Unit>, shown: AtomicBoolean, show: (Activity) -> Task<Int>) {
        val activity = activityRef?.get() ?: return
        if (!shown.compareAndSet(false, true)) return
        show(activity)
            .addOnSuccessListener { result ->
                if (result != Activity.RESULT_OK) done.completeExceptionally(NativeMessageException(nativeMessage(NativeKey.MODEL_DELIVERY_FAILED)))
            }
            .addOnFailureListener { error -> done.completeExceptionally(error) }
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
}
