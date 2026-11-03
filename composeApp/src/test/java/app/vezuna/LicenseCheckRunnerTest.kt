package app.vezuna

import app.vezuna.license.LicenseCheckRunner
import app.vezuna.license.LicenseProbe
import com.google.android.vending.licensing.LicenseCheckerCallback
import com.google.android.vending.licensing.Policy
import com.google.android.vending.licensing.ResponseData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LicenseCheckRunnerTest {
    private class FakePolicy(var allow: Boolean) : Policy {
        override fun processServerResponse(response: Int, rawData: ResponseData?) = Unit
        override fun allowAccess() = allow
        override fun getLicensingUrl(): String? = null
    }

    private class FakeProbe(private val onCheck: (LicenseCheckerCallback) -> Unit) : LicenseProbe {
        var destroyed = 0
        override fun checkAccess(callback: LicenseCheckerCallback) = onCheck(callback)
        override fun onDestroy() { destroyed++ }
    }

    private fun runner(policy: Policy, probes: MutableList<FakeProbe>, onCheck: (LicenseCheckerCallback) -> Unit) =
        LicenseCheckRunner(policy) { FakeProbe(onCheck).also { probes += it } }

    @Test
    fun allowedUsesPolicyAndDestroysProbe() = runTest {
        val probes = mutableListOf<FakeProbe>()
        val r = runner(FakePolicy(true), probes) { it.allow(Policy.LICENSED) }
        assertTrue(r.check())
        assertEquals(listOf(1), probes.map { it.destroyed })
    }

    @Test
    fun allowCallbackDeniedWhenPolicyRefuses() = runTest {
        val probes = mutableListOf<FakeProbe>()
        val r = runner(FakePolicy(false), probes) { it.allow(Policy.LICENSED) }
        assertFalse(r.check())
    }

    @Test
    fun retryGraceAllowsOnlyWhilePolicyAllowsOffline() = runTest {
        val probes = mutableListOf<FakeProbe>()
        assertTrue(runner(FakePolicy(true), probes) { it.dontAllow(Policy.RETRY) }.check())
        assertFalse(runner(FakePolicy(false), probes) { it.dontAllow(Policy.RETRY) }.check())
        assertFalse(runner(FakePolicy(true), probes) { it.dontAllow(Policy.NOT_LICENSED) }.check())
        assertTrue(probes.all { it.destroyed == 1 })
    }

    @Test
    fun applicationErrorAndThrowDenyAndDestroy() = runTest {
        val probes = mutableListOf<FakeProbe>()
        assertFalse(runner(FakePolicy(true), probes) { it.applicationError(LicenseCheckerCallback.ERROR_NOT_MARKET_MANAGED) }.check())
        assertFalse(runner(FakePolicy(true), probes) { error("bind failed") }.check())
        assertTrue(probes.all { it.destroyed == 1 })
    }

    @Test
    fun everyCheckGetsItsOwnDestroyedProbe() = runTest {
        val probes = mutableListOf<FakeProbe>()
        val r = runner(FakePolicy(true), probes) { it.allow(Policy.LICENSED) }
        repeat(3) { r.check() }
        assertEquals(3, probes.size)
        assertTrue(probes.all { it.destroyed == 1 })
    }

    @Test
    fun cancellationDestroysProbe() = runTest {
        val probes = mutableListOf<FakeProbe>()
        val never = CompletableDeferred<Unit>()
        val r = runner(FakePolicy(true), probes) { never.complete(Unit) }
        val job = launch { r.check() }
        advanceUntilIdle()
        job.cancel()
        advanceUntilIdle()
        assertEquals(1, probes.size)
        assertEquals(1, probes[0].destroyed)
    }

    @Test
    fun probeConstructionFailureDenies() = runTest {
        assertFalse(LicenseCheckRunner(FakePolicy(true)) { error("bad key") }.check())
    }

    @Test
    fun probeConstructionFailureIsReportedToTheLogger() = runTest {
        val reported = mutableListOf<Throwable>()
        assertFalse(LicenseCheckRunner(FakePolicy(true), { reported += it }) { error("bad key") }.check())
        assertEquals(1, reported.size)
    }
}
