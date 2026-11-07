package app.vezuna

import android.app.Activity
import app.vezuna.license.PremiumLicenseGate
import app.vezuna.llm.ModelPack
import app.vezuna.llm.ModelPackLoadState
import app.vezuna.llm.PlayAssetPackLoader
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tasks.TaskCompletionSource
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
import kotlin.test.assertNull
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
        val providerCalls = AtomicInteger()
        val unregisters = AtomicInteger()
        val calls = mutableListOf<String>()
        var stalledMethod: String? = null
        var failedMethod: String? = null
        var rejectConfirmation = false
        var queriedState: AssetPackState? = null
        var unregisterThrows = false
        var confirmationThrows = false
        var emitDuringFetch: AssetPackState? = null
        var listener: AssetPackStateUpdateListener? = null
    }

    private fun manager(recorder: Recorder, initial: AssetPackState): AssetPackManager = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(AssetPackManager::class.java),
    ) { _, method, args ->
        recorder.calls += method.name
        if (recorder.failedMethod == method.name) error("${method.name} failed")
        when (method.name) {
            "getPackLocation" -> { recorder.providerCalls.incrementAndGet(); null }
            "registerListener" -> { recorder.listener = args[0] as AssetPackStateUpdateListener; null }
            "unregisterListener" -> {
                recorder.unregisters.incrementAndGet()
                if (recorder.unregisterThrows) error("unregister failed")
                null
            }
            "fetch", "getPackStates" -> {
                recorder.providerCalls.incrementAndGet()
                if (method.name == "fetch") recorder.emitDuringFetch?.let { recorder.listener!!.onStateUpdate(it) }
                if (recorder.stalledMethod == method.name) TaskCompletionSource<AssetPackStates>().task
                else Tasks.forResult(FakeStates(if (method.name == "getPackStates") recorder.queriedState ?: initial else initial))
            }
            "showConfirmationDialog", "showCellularDataConfirmation" -> { recorder.confirmations.incrementAndGet()
                if (recorder.confirmationThrows) error("confirmation failed")
                Tasks.forResult(if (recorder.rejectConfirmation) Activity.RESULT_CANCELED else Activity.RESULT_OK) }
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
    fun packWithoutAnAndroidStoreIdIsRejectedBeforeAnyProviderWork() = runTest {
        val recorder = Recorder()
        val loader = PlayAssetPackLoader({ manager(recorder, FakeState(AssetPackStatus.DOWNLOADING)) }, allowAll)
        val androidLess = ModelPack("m", "M", "m.gguf", null, "ios", 10, 10, 4096, "a".repeat(64))
        assertNull(loader.load(androidLess))
        val unavailable = loader.state.value
        assertTrue(unavailable is ModelPackLoadState.Unavailable)
        assertEquals(NativeKey.MODEL_PACK_UNAVAILABLE, ((unavailable as ModelPackLoadState.Unavailable).reason as NativeText.Key).key)
        assertEquals(0, recorder.providerCalls.get())
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
    @Test
    fun stalledPlayTasksAreBoundedAndCleanupRunsOnce() = runTest {
        for (method in listOf("fetch", "getPackStates")) {
            val recorder = Recorder().apply { stalledMethod = method }
            val loader = PlayAssetPackLoader({ manager(recorder, FakeState(AssetPackStatus.DOWNLOADING)) }, allowAll, 1_000)
            val result = async(start = CoroutineStart.UNDISPATCHED) { loader.load(pack) }
            advanceTimeBy(2_001)
            runCurrent()
            assertNull(result.await())
            assertEquals(1, recorder.unregisters.get())
            assertEquals(1, recorder.cancels.get())
            assertTrue(recorder.calls.indexOf("registerListener") < recorder.calls.indexOf("fetch"))
            val terminal = loader.state.value
            recorder.listener!!.onStateUpdate(FakeState(AssetPackStatus.WAITING_FOR_WIFI))
            assertEquals(terminal, loader.state.value)
            assertEquals(0, recorder.confirmations.get())
        }
    }

    @Test
    fun terminalCallbackDuringFetchDoesNotWaitForStalledTask() = runTest {
        val recorder = Recorder().apply {
            stalledMethod = "fetch"
            emitDuringFetch = FakeState(AssetPackStatus.FAILED)
        }
        val loader = PlayAssetPackLoader({ manager(recorder, FakeState(AssetPackStatus.PENDING)) }, allowAll)
        assertNull(loader.load(pack))
        assertEquals(1, recorder.unregisters.get())
        assertEquals(1, recorder.cancels.get())
    }

    @Test
    fun unregisterFailureStillCancelsPackOnce() = runTest {
        val recorder = Recorder().apply { unregisterThrows = true }
        val loader = PlayAssetPackLoader({ manager(recorder, FakeState(AssetPackStatus.FAILED)) }, allowAll)
        assertNull(loader.load(pack))
        assertEquals(1, recorder.unregisters.get())
        assertEquals(1, recorder.cancels.get())
    }

    @Test
    fun wifiWaitRequiresExplicitDialogAndThrownDialogFailsDelivery() = runTest {
        val recorder = Recorder().apply { confirmationThrows = true }
        val loader = PlayAssetPackLoader({ manager(recorder, FakeState(AssetPackStatus.WAITING_FOR_WIFI)) }, allowAll)
        val result = async(start = CoroutineStart.UNDISPATCHED) { loader.load(pack) }
        runCurrent()
        assertEquals(ModelPackLoadState.WaitingForWifi, loader.state.value)
        assertEquals(0, recorder.confirmations.get())
        loader.bindActivity(Activity())
        runCurrent()
        assertNull(result.await())
        assertEquals(1, recorder.confirmations.get())
        assertEquals(1, recorder.cancels.get())
    }

    @Test
    fun providerFailuresAndCallerCancellationCleanUpExactlyOnce() = runTest {
        for (method in listOf("registerListener", "fetch", "getPackStates")) {
            val recorder = Recorder().apply { failedMethod = method }
            val loader = PlayAssetPackLoader({ manager(recorder, FakeState(AssetPackStatus.DOWNLOADING)) }, allowAll)
            assertNull(loader.load(pack))
            assertTrue(loader.state.value is ModelPackLoadState.Failed)
            assertEquals(1, recorder.unregisters.get())
            assertEquals(1, recorder.cancels.get())
        }
        val recorder = Recorder().apply { stalledMethod = "getPackStates" }
        val loader = PlayAssetPackLoader({ manager(recorder, FakeState(AssetPackStatus.DOWNLOADING)) }, allowAll)
        val result = async(start = CoroutineStart.UNDISPATCHED) { loader.load(pack) }
        result.cancel()
        runCurrent()
        assertTrue(result.isCancelled)
        assertEquals(1, recorder.unregisters.get())
        assertEquals(1, recorder.cancels.get())
    }

    @Test
    fun decliningEitherConfirmationStopsDelivery() = runTest {
        for (status in listOf(AssetPackStatus.REQUIRES_USER_CONFIRMATION, AssetPackStatus.WAITING_FOR_WIFI)) {
            val recorder = Recorder().apply { rejectConfirmation = true }
            val loader = PlayAssetPackLoader({ manager(recorder, FakeState(status)) }, allowAll)
            loader.bindActivity(Activity())
            assertNull(loader.load(pack))
            assertEquals(1, recorder.confirmations.get())
            assertEquals(1, recorder.unregisters.get())
            assertEquals(1, recorder.cancels.get())
        }
    }

    @Test
    fun immediateStateQueryFindsMissedCompletionWithoutCancellingSuccessfulDelivery() = runTest {
        val recorder = Recorder().apply { queriedState = FakeState(AssetPackStatus.COMPLETED) }
        val loader = PlayAssetPackLoader({ manager(recorder, FakeState(AssetPackStatus.PENDING)) }, allowAll)
        // This fake has no installed location; only the fetch lifecycle completes successfully.
        assertNull(loader.load(pack))
        assertEquals(ModelPackLoadState.Unavailable(nativeMessage(NativeKey.MODEL_PACK_UNAVAILABLE)), loader.state.value)
        assertEquals(1, recorder.unregisters.get())
        assertEquals(0, recorder.cancels.get())
        assertTrue(recorder.calls.indexOf("registerListener") < recorder.calls.indexOf("fetch"))
        assertTrue(recorder.calls.indexOf("fetch") < recorder.calls.indexOf("getPackStates"))
    }

}
