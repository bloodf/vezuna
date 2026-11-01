package app.vezuna.llm

import com.google.android.play.core.assetpacks.model.AssetPackStatus

/** What the loader must do for a given Play Asset Delivery pack status. */
enum class AssetPackAction { AWAIT, COMPLETE, FAIL, CONFIRM_USER, CONFIRM_CELLULAR }

object AssetPackStatusPolicy {
    const val FETCH_TIMEOUT_MS = 30L * 60 * 1000

    fun actionFor(status: Int): AssetPackAction = when (status) {
        AssetPackStatus.COMPLETED -> AssetPackAction.COMPLETE
        AssetPackStatus.FAILED,
        AssetPackStatus.CANCELED,
        AssetPackStatus.NOT_INSTALLED,
        -> AssetPackAction.FAIL
        AssetPackStatus.REQUIRES_USER_CONFIRMATION -> AssetPackAction.CONFIRM_USER
        AssetPackStatus.WAITING_FOR_WIFI -> AssetPackAction.CONFIRM_CELLULAR
        else -> AssetPackAction.AWAIT
    }
}
