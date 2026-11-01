package app.vezuna.security

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

/** Platform prompt: biometrics with the device passcode as fallback. Implementations return false on any failure. */
interface DeviceAuthenticator {
    /** False when the device has no enrolled biometrics and no screen lock. */
    fun canAuthenticate(): Boolean
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

data class AppLockState(val enabled: Boolean, val locked: Boolean, val problem: AppLockProblem? = null)

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
     * Drops a stale setting when the device lost its screen lock (restored or changed device): otherwise the
     * user would be locked out of their own records with no way to authenticate.
     */
    fun refresh() {
        if (mutableState.value.enabled && !authenticator.canAuthenticate()) {
            store.setEnabled(false)
            mutableState.value = AppLockState(enabled = false, locked = false)
        }
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

    /** Enabling requires a successful authentication first; disabling happens while unlocked. */
    suspend fun setEnabled(enabled: Boolean) {
        if (enabled == mutableState.value.enabled) return
        if (!enabled) {
            store.setEnabled(false)
            mutableState.value = AppLockState(enabled = false, locked = false)
            return
        }
        if (!authenticator.canAuthenticate()) {
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

    fun clearProblem() = mutableState.update { it.copy(problem = null) }

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
