package app.vezuna.llm

import android.app.Activity
import app.vezuna.NativeKey
import app.vezuna.NativeMessageException
import app.vezuna.nativeMessage
import com.google.android.gms.tasks.Task
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One in-flight pack delivery. Confirmation dialogs need an Activity; when none is bound the request is kept
 * and replayed by [retryPendingConfirmation], and the delivery fails if no Activity appears in time.
 */
internal class AssetPackFetchSession(
    private val packId: String,
    private val manager: AssetPackManager,
    private val scope: CoroutineScope,
    private val activity: () -> Activity?,
    private val onState: (AssetPackState) -> Unit,
    private val confirmationBindTimeoutMs: Long = AssetPackStatusPolicy.CONFIRMATION_BIND_TIMEOUT_MS,
) {
    val done = CompletableDeferred<Unit>()
    private val shown = AtomicBoolean(false)
    @Volatile private var pending: AssetPackAction? = null
    private val progress = AtomicLong(0)

    /** Changes whenever the delivery makes progress; an unchanged value across the inactivity window means a stall. */
    fun progressMarker(): Long = progress.get()

    fun handle(state: AssetPackState) {
        progress.set(state.bytesDownloaded() * 31 + state.status())
        onState(state)
        when (val action = AssetPackStatusPolicy.actionFor(state.status())) {
            AssetPackAction.COMPLETE -> done.complete(Unit)
            AssetPackAction.FAIL -> done.completeExceptionally(
                NativeMessageException(nativeMessage(NativeKey.ASSET_PACK_UNAVAILABLE, packId, state.status(), state.errorCode())),
            )
            AssetPackAction.CONFIRM_USER, AssetPackAction.CONFIRM_CELLULAR -> confirm(action)
            AssetPackAction.AWAIT -> Unit
        }
    }

    fun retryPendingConfirmation() { pending?.let(::confirm) }

    private fun confirm(action: AssetPackAction) {
        // A dialog already answered or on screen covers any later confirmation status; nothing is waiting for an activity.
        if (shown.get()) { pending = null; return }
        val host = activity()
        if (host == null) {
            if (pending == null) {
                pending = action
                scope.launch {
                    delay(confirmationBindTimeoutMs)
                    if (pending != null) done.completeExceptionally(NativeMessageException(nativeMessage(NativeKey.MODEL_CONFIRMATION_NEEDED)))
                }
            }
            return
        }
        if (!shown.compareAndSet(false, true)) return
        pending = null
        val task: Task<Int> = if (action == AssetPackAction.CONFIRM_USER) manager.showConfirmationDialog(host) else manager.showCellularDataConfirmation(host)
        task.addOnSuccessListener(DIRECT) { result ->
            if (result != Activity.RESULT_OK) done.completeExceptionally(NativeMessageException(nativeMessage(NativeKey.MODEL_DELIVERY_FAILED)))
        }.addOnFailureListener(DIRECT) { error -> done.completeExceptionally(error) }
    }

    private companion object {
        val DIRECT = java.util.concurrent.Executor { it.run() }
    }
}
