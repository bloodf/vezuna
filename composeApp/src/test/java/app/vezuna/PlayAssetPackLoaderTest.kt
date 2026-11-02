package app.vezuna

import android.app.Activity
import app.vezuna.license.PremiumLicenseGate
import app.vezuna.llm.ModelPack
import app.vezuna.llm.ModelPackLoadState
import app.vezuna.llm.PlayAssetPackLoader
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.AssetPackStates
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayAssetPackLoaderTest {
    private val pack = ModelPack("m", "M", "m.gguf", "pack", "ios", 10, 10, 4096, "a".repeat(64))

    private class FakeState(private val status: Int, private val downloaded: Long = 0) : AssetPackState() {
        override fun name() = "pack"
        override fun status() = status
        override fun errorCode() = 0
        override fun bytesDownloaded() = downloaded
        override fun totalBytesToDownload() = 100L
        override fun transferProgressPercentage() = 0
        override fun updateAvailability() = 0
        override fun availableVersionTag(): String = ""
        override fun installedVersionTag(): String = ""
    }

    private class FakeStates(private val state: AssetPackState) : AssetPackStates() {
        override fun totalBytes() = 100L
        override fun packStates(): Map<String, AssetPackState> = mapOf("pack" to state)
    }

    private class Recorder {
        val confirmations = AtomicInteger()
        val cancels = AtomicInteger()
        var listener: AssetPackStateUpdateListener? = null
    }

    private fun manager(recorder: Recorder, initial: AssetPackState): AssetPackManager = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(AssetPackManager::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getPackLocation" -> null
            "registerListener" -> { recorder.listener = args[0] as AssetPackStateUpdateListener; null }
            "unregisterListener" -> null
            "fetch", "getPackStates" -> Tasks.forResult(FakeStates(initial))
            "showConfirmationDialog", "showCellularDataConfirmation" -> { recorder.confirmations.incrementAndGet(); Tasks.forResult(Activity.RESULT_OK) }
            "cancel" -> { recorder.cancels.incrementAndGet(); FakeStates(initial) }
            else -> error("unexpected ${method.name}")
        }
    } as AssetPackManager

    private val allowAll = object : PremiumLicenseGate { override suspend fun premiumAllowed() = true }

    @Test
    fun confirmationRequestedWithoutActivityIsReplayedWhenOneBinds() = runTest {
        val recorder = Recorder()
        val loader = PlayAssetPackLoader({ manager(recorder, FakeState(AssetPackStatus.REQUIRES_USER_CONFIRMATION)) }, allowAll, inactivityTimeoutMs = 1_000_000)
        val result = async(start = CoroutineStart.UNDISPATCHED) { loader.load(pack) }
        runCurrent()
        assertEquals(0, recorder.confirmations.get())

        loader.bindActivity(Activity())
        assertEquals(1, recorder.confirmations.get())

        recorder.listener!!.onStateUpdate(FakeState(AssetPackStatus.FAILED))
        result.await()
        assertTrue(loader.state.value is ModelPackLoadState.Failed)
    }

    @Test
    fun repeatedConfirmationStatusWhileUnboundAfterShowingDoesNotFailTheDelivery() = runTest {
        val recorder = Recorder()
        val loader = PlayAssetPackLoader({ manager(recorder, FakeState(AssetPackStatus.REQUIRES_USER_CONFIRMATION)) }, allowAll, inactivityTimeoutMs = 1_000_000)
        loader.bindActivity(Activity())
        val result = async(start = CoroutineStart.UNDISPATCHED) { loader.load(pack) }
        runCurrent()
        assertEquals(1, recorder.confirmations.get())

        loader.bindActivity(null)
        recorder.listener!!.onStateUpdate(FakeState(AssetPackStatus.REQUIRES_USER_CONFIRMATION))
        advanceTimeBy(120_000)
        runCurrent()
        assertTrue(result.isActive, "a confirmed delivery must not be failed by the bind timeout")

        recorder.listener!!.onStateUpdate(FakeState(AssetPackStatus.FAILED))
        result.await()
    }

    @Test
    fun unboundConfirmationFailsWithGuidanceAndCancelsThePack() = runTest {
        val recorder = Recorder()
        val loader = PlayAssetPackLoader({ manager(recorder, FakeState(AssetPackStatus.REQUIRES_USER_CONFIRMATION)) }, allowAll, inactivityTimeoutMs = 1_000_000)
        val result = async(start = CoroutineStart.UNDISPATCHED) { loader.load(pack) }
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(null, result.await())
        val failed = loader.state.value as ModelPackLoadState.Failed
        assertEquals(NativeKey.MODEL_CONFIRMATION_NEEDED, (failed.message as NativeText.Key).key)
        assertEquals(1, recorder.cancels.get())
    }

    @Test
    fun stalledDownloadTimesOutOnInactivityAndIsCancelled() = runTest {
        val recorder = Recorder()
        val loader = PlayAssetPackLoader({ manager(recorder, FakeState(AssetPackStatus.DOWNLOADING, 5)) }, allowAll, inactivityTimeoutMs = 1_000)
        val result = async(start = CoroutineStart.UNDISPATCHED) { loader.load(pack) }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(null, result.await())
        assertTrue(loader.state.value is ModelPackLoadState.Failed)
        assertEquals(1, recorder.cancels.get())
    }

    @Test
    fun progressKeepsTheDeliveryAlive() = runTest {
        val recorder = Recorder()
        val loader = PlayAssetPackLoader({ manager(recorder, FakeState(AssetPackStatus.DOWNLOADING, 5)) }, allowAll, inactivityTimeoutMs = 1_000)
        val result = async(start = CoroutineStart.UNDISPATCHED) { loader.load(pack) }
        repeat(5) { step ->
            advanceTimeBy(900)
            recorder.listener!!.onStateUpdate(FakeState(AssetPackStatus.DOWNLOADING, 10L + step))
        }
        assertTrue(result.isActive)
        result.cancel()
        runCurrent()
        assertEquals(1, recorder.cancels.get())
    }
}
