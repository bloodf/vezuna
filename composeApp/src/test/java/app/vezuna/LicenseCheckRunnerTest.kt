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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    fun concurrentCallbacksCompleteOnceAndDestroyOnce() = runTest {
        val arrived = CountDownLatch(2)
        val policy = object : Policy {
            override fun processServerResponse(response: Int, rawData: ResponseData?) = Unit
            override fun getLicensingUrl(): String? = null
            override fun allowAccess(): Boolean {
                arrived.countDown()
                check(arrived.await(5, TimeUnit.SECONDS))
                return true
            }
        }
        val probes = mutableListOf<FakeProbe>()
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val r = runner(policy, probes) { callback ->
                val results = List(2) {
                    executor.submit {
                        runCatching { callback.allow(Policy.LICENSED) }.exceptionOrNull()?.let { failures += it }
                    }
                }
                results.forEach { it.get(5, TimeUnit.SECONDS) }
            }
            assertTrue(r.check())
            assertEquals(emptyList(), failures.toList())
            assertEquals(listOf(1), probes.map { it.destroyed })
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun cancelledCheckIgnoresLateCallbacksAndNextCheckStillWorks() = runTest {
        val probes = mutableListOf<FakeProbe>()
        lateinit var callback: LicenseCheckerCallback
        val r = runner(FakePolicy(true), probes) { callback = it }
        val job = launch { r.check() }
        advanceUntilIdle()
        job.cancel()
        advanceUntilIdle()
        callback.allow(Policy.LICENSED)
        callback.applicationError(LicenseCheckerCallback.ERROR_NOT_MARKET_MANAGED)
        assertEquals(listOf(1), probes.map { it.destroyed })
        var allowed = false
        val next = launch { allowed = r.check() }
        advanceUntilIdle()
        callback.allow(Policy.LICENSED)
        next.join()
        assertTrue(allowed)
        assertEquals(listOf(1, 1), probes.map { it.destroyed })
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
