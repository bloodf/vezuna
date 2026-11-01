package app.vezuna

import app.vezuna.security.AppLock
import app.vezuna.security.AppLockPolicy
import app.vezuna.security.AppLockProblem
import app.vezuna.security.AppLockStore
import app.vezuna.security.DeviceAuthenticator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeStore(var stored: Boolean = false) : AppLockStore {
    override fun isEnabled() = stored
    override fun setEnabled(enabled: Boolean) { stored = enabled }
}

private class FakeAuthenticator(var available: Boolean? = true, var succeeds: Boolean = true, var throws: Boolean = false) : DeviceAuthenticator {
    var prompts = 0
    override fun canAuthenticate() = available
    override suspend fun authenticate(): Boolean {
        prompts++
        if (throws) error("boom")
        return succeeds
    }
}

class AppLockTest {
    private val minute = 60_000L
    private var now = 1_000_000L

    private fun lock(store: FakeStore = FakeStore(), auth: FakeAuthenticator = FakeAuthenticator()) = AppLock(store, auth) { now }

    @Test
    fun policyLocksOnlyAfterFiveMinutesOrClockRollback() {
        assertFalse(AppLockPolicy.shouldLock(null, now))
        assertFalse(AppLockPolicy.shouldLock(now, now + 5 * minute - 1))
        assertTrue(AppLockPolicy.shouldLock(now, now + 5 * minute))
        assertTrue(AppLockPolicy.shouldLock(now, now - 1))
    }

    @Test
    fun disabledByDefaultAndNeverLocks() {
        val lock = lock()
        assertFalse(lock.state.value.enabled)
        assertFalse(lock.state.value.locked)
        lock.onBackground(); now += 10 * minute; lock.onForeground()
        assertFalse(lock.state.value.locked)
    }

    @Test
    fun enabledLocksOnLaunch() {
        assertTrue(lock(FakeStore(stored = true)).state.value.locked)
    }

    @Test
    fun briefBackgroundDoesNotLockButLongOneDoes() = runTest {
        val lock = lock(FakeStore(stored = true))
        lock.unlock()
        lock.onBackground(); now += 4 * minute; lock.onForeground()
        assertFalse(lock.state.value.locked)
        lock.onBackground(); now += 5 * minute; lock.onForeground()
        assertTrue(lock.state.value.locked)
    }

    @Test
    fun repeatedBackgroundCallsKeepTheFirstTimestamp() = runTest {
        val lock = lock(FakeStore(stored = true)); lock.unlock()
        lock.onBackground(); now += 3 * minute; lock.onBackground(); now += 3 * minute; lock.onForeground()
        assertTrue(lock.state.value.locked)
    }

    @Test
    fun unlockNeedsSuccessfulAuthentication() = runTest {
        val auth = FakeAuthenticator(succeeds = false)
        val lock = lock(FakeStore(stored = true), auth)
        lock.unlock()
        assertTrue(lock.state.value.locked)
        assertEquals(AppLockProblem.FAILED, lock.state.value.problem)
        auth.succeeds = true
        lock.unlock()
        assertFalse(lock.state.value.locked)
        assertNull(lock.state.value.problem)
    }

    @Test
    fun authenticatorErrorsKeepTheAppLocked() = runTest {
        val lock = lock(FakeStore(stored = true), FakeAuthenticator(throws = true))
        lock.unlock()
        assertTrue(lock.state.value.locked)
    }

    @Test
    fun enablingRequiresAuthentication() = runTest {
        val store = FakeStore(); val auth = FakeAuthenticator(succeeds = false)
        val lock = lock(store, auth)
        lock.setEnabled(true)
        assertFalse(store.stored)
        assertFalse(lock.state.value.enabled)
        assertEquals(AppLockProblem.FAILED, lock.state.value.problem)
        auth.succeeds = true
        lock.setEnabled(true)
        assertTrue(store.stored)
        assertTrue(lock.state.value.enabled)
        assertFalse(lock.state.value.locked)
    }

    @Test
    fun enablingIsRefusedWithoutDeviceCredential() = runTest {
        val store = FakeStore(); val auth = FakeAuthenticator(available = false)
        val lock = lock(store, auth)
        lock.setEnabled(true)
        assertEquals(0, auth.prompts)
        assertFalse(store.stored)
        assertEquals(AppLockProblem.UNAVAILABLE, lock.state.value.problem)
    }

    @Test
    fun disablingRequiresAuthentication() = runTest {
        val store = FakeStore(stored = true); val auth = FakeAuthenticator()
        val lock = lock(store, auth); lock.unlock()
        auth.succeeds = false
        lock.setEnabled(false)
        assertTrue(store.stored)
        assertTrue(lock.state.value.enabled)
        assertEquals(AppLockProblem.FAILED, lock.state.value.problem)
        auth.succeeds = true
        lock.setEnabled(false)
        assertFalse(store.stored)
        assertFalse(lock.state.value.enabled)
    }

    @Test
    fun clockRollbackInsideTheWindowStillLocks() = runTest {
        val lock = lock(FakeStore(stored = true)); lock.unlock()
        lock.onBackground(); now -= 2 * minute; lock.onForeground()
        assertTrue(lock.state.value.locked)
    }

    @Test
    fun lockNowEndsTheSession() = runTest {
        val lock = lock(FakeStore(stored = true)); lock.unlock()
        lock.lockNow()
        assertTrue(lock.state.value.locked)
    }

    @Test
    fun refreshSuspendsButKeepsSettingWhenDeviceLostItsScreenLock() {
        val store = FakeStore(stored = true); val auth = FakeAuthenticator(available = false)
        val lock = lock(store, auth)
        assertTrue(lock.state.value.locked)
        lock.refresh()
        assertTrue(store.stored)
        assertFalse(lock.state.value.locked)
        assertFalse(lock.state.value.enabled)
        assertTrue(lock.state.value.suspended)
        auth.available = true
        lock.refresh()
        assertTrue(lock.state.value.enabled)
        assertTrue(lock.state.value.locked)
    }

    @Test
    fun failedEnableWhileSuspendedDoesNotPreventResume() = runTest {
        val store = FakeStore(stored = true); val auth = FakeAuthenticator(available = false)
        val lock = lock(store, auth); lock.refresh()
        lock.setEnabled(true)
        assertEquals(AppLockProblem.UNAVAILABLE, lock.state.value.problem)
        assertTrue(lock.state.value.suspended)
        auth.available = true
        lock.refresh()
        assertTrue(lock.state.value.enabled)
        assertTrue(lock.state.value.locked)
        assertFalse(lock.state.value.suspended)
    }

    @Test
    fun suspendedLockCanBeSwitchedOffForGood() = runTest {
        val store = FakeStore(stored = true)
        val lock = lock(store, FakeAuthenticator(available = false)); lock.refresh()
        lock.setEnabled(false)
        assertFalse(store.stored)
        assertNull(lock.state.value.problem)
    }

    @Test
    fun unknownCapabilityChangesNothing() {
        val store = FakeStore(stored = true)
        val lock = lock(store, FakeAuthenticator(available = null))
        lock.refresh()
        assertTrue(store.stored)
        assertTrue(lock.state.value.enabled)
        assertTrue(lock.state.value.locked)
    }

    @Test
    fun enablingIsRefusedWhenCapabilityUnknown() = runTest {
        val lock = lock(FakeStore(), FakeAuthenticator(available = null))
        lock.setEnabled(true)
        assertFalse(lock.state.value.enabled)
    }
}
