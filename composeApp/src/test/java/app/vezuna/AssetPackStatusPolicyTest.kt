package app.vezuna

import app.vezuna.llm.AssetPackAction
import app.vezuna.llm.AssetPackStatusPolicy
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import org.junit.Test
import kotlin.test.assertEquals

class AssetPackStatusPolicyTest {
    @Test
    fun mapsEveryStatusToAnAction() {
        val expected = mapOf(
            AssetPackStatus.COMPLETED to AssetPackAction.COMPLETE,
            AssetPackStatus.FAILED to AssetPackAction.FAIL,
            AssetPackStatus.CANCELED to AssetPackAction.FAIL,
            AssetPackStatus.NOT_INSTALLED to AssetPackAction.FAIL,
            AssetPackStatus.REQUIRES_USER_CONFIRMATION to AssetPackAction.CONFIRM_USER,
            AssetPackStatus.WAITING_FOR_WIFI to AssetPackAction.CONFIRM_CELLULAR,
            AssetPackStatus.PENDING to AssetPackAction.AWAIT,
            AssetPackStatus.DOWNLOADING to AssetPackAction.AWAIT,
            AssetPackStatus.TRANSFERRING to AssetPackAction.AWAIT,
            AssetPackStatus.UNKNOWN to AssetPackAction.AWAIT,
        )
        expected.forEach { (status, action) -> assertEquals(action, AssetPackStatusPolicy.actionFor(status), "status $status") }
    }
}
