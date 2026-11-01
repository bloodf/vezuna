package app.vezuna.security

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

/** Platform prompt: biometrics with the device passcode as fallback. Implementations return false on any failure. */
interface DeviceAuthenticator {
    /** False when the device has no enrolled biometrics and no screen lock; null when that cannot be known now. */
    fun canAuthenticate(): Boolean?
    suspend fun authenticate(): Boolean
}

/** Per-device setting, deliberately kept outside the encrypted vault so the lock applies before the vault loads. */
interface AppLockStore {
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)
}

object AppLockPolicy {
    const val BACKGROUND_TIMEOUT_MILLIS: Long = 5 * 60 * 1000L

    /** [backgroundedAt] is null when the app never left the foreground. A clock that moved backwards locks. */
    fun shouldLock(backgroundedAt: Long?, now: Long): Boolean =
        backgroundedAt != null && (now < backgroundedAt || now - backgroundedAt >= BACKGROUND_TIMEOUT_MILLIS)
}

enum class AppLockProblem { UNAVAILABLE, FAILED }

/** [suspended]: the setting is kept but inactive because the device lost its screen lock. */
data class AppLockState(val enabled: Boolean, val locked: Boolean, val problem: AppLockProblem? = null, val suspended: Boolean = false)

/** Lock state machine. Starts locked when enabled (launch), relocks after a long background, enabling needs auth. */
class AppLock(
    private val store: AppLockStore,
    private val authenticator: DeviceAuthenticator,
    private val clock: () -> Long,
) {
    private val mutableState = MutableStateFlow(store.isEnabled().let { AppLockState(enabled = it, locked = it) })
    val state: StateFlow<AppLockState> = mutableState.asStateFlow()
    private var backgroundedAt: Long? = null
    private val prompt = Mutex()

    /**
     * Suspends the lock when the device has no screen lock (otherwise the user could never authenticate), keeping
     * the stored setting; resumes it, locked, once a screen lock exists again. Unknown capability changes nothing.
     */
    fun refresh() {
        val available = authenticator.canAuthenticate() ?: return
        val current = mutableState.value
        if (current.enabled && !available) {
            mutableState.value = AppLockState(enabled = false, locked = false, suspended = true)
        } else if (!current.enabled && available && current.suspended && store.isEnabled()) {
            mutableState.value = AppLockState(enabled = true, locked = true)
        }
    }

    /** Ends the session, e.g. when the app is closed. */
    fun lockNow() {
        backgroundedAt = null
        if (mutableState.value.enabled) mutableState.update { it.copy(locked = true, problem = null) }
    }

    fun onBackground() {
        if (backgroundedAt == null) backgroundedAt = clock()
    }

    fun onForeground() {
        val since = backgroundedAt
        backgroundedAt = null
        if (mutableState.value.enabled && AppLockPolicy.shouldLock(since, clock())) {
            mutableState.update { it.copy(locked = true, problem = null) }
        }
    }

    suspend fun unlock() {
        if (!mutableState.value.locked) return
        if (authenticate()) mutableState.update { it.copy(locked = false, problem = null) }
        else mutableState.update { it.copy(problem = AppLockProblem.FAILED) }
    }

    /**
     * Enabling and disabling both require a successful authentication. Without any device credential there is
     * nothing to authenticate with, so disabling is then allowed (and enabling refused).
     */
    suspend fun setEnabled(enabled: Boolean) {
        if (enabled == mutableState.value.enabled) {
            // Suspended (no screen lock): switching off must also clear the kept setting.
            if (!enabled && store.isEnabled()) {
                store.setEnabled(false)
                mutableState.value = AppLockState(enabled = false, locked = false)
            }
            return
        }
        if (!enabled) {
            if (authenticator.canAuthenticate() == false || authenticate()) {
                store.setEnabled(false)
                mutableState.value = AppLockState(enabled = false, locked = false)
            } else {
                mutableState.update { it.copy(problem = AppLockProblem.FAILED) }
            }
            return
        }
        if (authenticator.canAuthenticate() != true) {
            mutableState.update { it.copy(problem = AppLockProblem.UNAVAILABLE) }
            return
        }
        if (authenticate()) {
            store.setEnabled(true)
            mutableState.value = AppLockState(enabled = true, locked = false)
        } else {
            mutableState.update { it.copy(problem = AppLockProblem.FAILED) }
        }
    }

    /** One prompt at a time; a second request while one is showing counts as not authenticated. */
    private suspend fun authenticate(): Boolean {
        if (!prompt.tryLock()) return false
        return try {
            authenticator.authenticate()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        } finally {
            prompt.unlock()
        }
    }
}
