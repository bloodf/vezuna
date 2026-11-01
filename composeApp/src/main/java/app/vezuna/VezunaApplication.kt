package app.vezuna

import android.app.Application
import android.os.SystemClock
import app.vezuna.data.PersistentVezunaRepository
import app.vezuna.data.restoreWithVitals
import app.vezuna.health.AndroidHealthConnectImporter
import app.vezuna.health.DeviceVitalsSync
import app.vezuna.health.LegacyHealthConnectMigration
import app.vezuna.llm.AndroidModelFileStore
import app.vezuna.llm.ModelCatalog
import app.vezuna.domain.ProfileData
import app.vezuna.notifications.MedicationNotificationScheduler
import app.vezuna.platform.currentInstant
import app.vezuna.security.AndroidSecureVault
import app.vezuna.security.AppLock
import app.vezuna.security.DeviceAuthenticator
import app.vezuna.security.VaultFailure
import app.vezuna.security.VaultState
import app.vezuna.security.VaultUnreadableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class VezunaApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var vault: AndroidSecureVault
        private set
    lateinit var repository: PersistentVezunaRepository
        private set
    lateinit var modelFiles: AndroidModelFileStore
        private set
    lateinit var notifications: MedicationNotificationScheduler
        private set
    lateinit var healthConnect: AndroidHealthConnectImporter
        private set
    lateinit var healthSync: DeviceVitalsSync
        private set

    val modelCatalog: ModelCatalog = ModelCatalog.unconfigured()

    /** The visible activity supplies the prompt; the lock itself outlives activity recreation. */
    @Volatile var deviceAuthenticator: DeviceAuthenticator? = null
    val appLock: AppLock by lazy {
        AppLock(
            SharedPrefsAppLockStore(this),
            object : DeviceAuthenticator {
                override fun canAuthenticate() = deviceAuthenticator?.canAuthenticate() ?: false
                override suspend fun authenticate() = deviceAuthenticator?.authenticate() ?: false
            },
            SystemClock::elapsedRealtime,
        )
    }

    private val mutableVaultState = MutableStateFlow<VaultState>(VaultState.Loading)
    /** Nothing may touch the repository (or create a default profile) until this reaches [VaultState.Ready]. */
    val vaultState: StateFlow<VaultState> = mutableVaultState.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        vault = AndroidSecureVault(File(noBackupFilesDir, "vault"))
        repository = PersistentVezunaRepository(vault)
        loadVault { repository.load() }
        modelFiles = AndroidModelFileStore(this)
        notifications = MedicationNotificationScheduler(this)
        notifications.createChannel()
        healthConnect = AndroidHealthConnectImporter(this)
        healthSync = DeviceVitalsSync(
            healthConnect, repository, { currentInstant() },
            beforeSync = { LegacyHealthConnectMigration(vault, repository, { currentInstant() }).run() },
        )
        applicationScope.launch {
            vaultState.first { it is VaultState.Ready }
            repository.revision.collect {
                notifications.rescheduleAll(repository)
            }
        }
    }

    fun retryVault() = loadVault { repository.load() }

    /** Explicit user reset from the recovery screen; the unreadable files are quarantined, not deleted. */
    fun resetVault() = loadVault { repository.resetAfterFailure() }

    /**
     * Restore from the recovery screen: the unreadable vault is quarantined (never deleted), then the backup is
     * stored into the fresh vault. Both steps run in one load so the app never sees a half-restored state.
     */
    fun restoreVaultFromBackup(data: ProfileData) {
        var restoreStarted = false
        loadVault(restoreFailed = { restoreStarted }) {
            repository.resetAfterFailure()
            restoreStarted = true
            repository.restoreWithVitals(data)
        }
    }

    /** True once the repository is usable; false when the vault needs recovery. Safe from receivers. */
    suspend fun awaitRepository(): Boolean =
        withTimeoutOrNull(RECEIVER_WAIT_MS) { vaultState.first { it !is VaultState.Loading } } is VaultState.Ready

    private fun loadVault(restoreFailed: () -> Boolean = { false }, action: suspend () -> Unit) {
        mutableVaultState.value = VaultState.Loading
        applicationScope.launch {
            mutableVaultState.value = try {
                action()
                VaultState.Ready
            } catch (error: CancellationException) {
                throw error
            } catch (error: VaultUnreadableException) {
                VaultState.Recovery(error.failure, restoreFailed())
            } catch (error: Exception) {
                VaultState.Recovery(VaultFailure.UNAVAILABLE, restoreFailed())
            }
        }
    }

    private companion object {
        /** Receivers run inside goAsync(), which the system cuts off after roughly ten seconds. */
        const val RECEIVER_WAIT_MS = 8_000L
    }
}
