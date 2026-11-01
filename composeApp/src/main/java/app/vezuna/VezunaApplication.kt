package app.vezuna

import android.app.Application
import app.vezuna.data.PersistentVezunaRepository
import app.vezuna.health.AndroidHealthConnectImporter
import app.vezuna.health.HealthConnectSyncCoordinator
import app.vezuna.llm.AndroidModelFileStore
import app.vezuna.llm.ModelCatalog
import app.vezuna.notifications.MedicationNotificationScheduler
import app.vezuna.security.AndroidSecureVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    lateinit var healthSync: HealthConnectSyncCoordinator
        private set

    val modelCatalog: ModelCatalog = ModelCatalog.unconfigured()

    override fun onCreate() {
        super.onCreate()
        vault = AndroidSecureVault(File(noBackupFilesDir, "vault"))
        repository = PersistentVezunaRepository(vault)
        // Restore before AppController checks profiles; otherwise a fresh default profile could race restoration.
        runBlocking { repository.load() }
        modelFiles = AndroidModelFileStore(this)
        notifications = MedicationNotificationScheduler(this)
        notifications.createChannel()
        healthConnect = AndroidHealthConnectImporter(this)
        healthSync = HealthConnectSyncCoordinator(healthConnect, vault)
        applicationScope.launch {
            repository.revision.collect {
                notifications.rescheduleAll(repository)
            }
        }
    }
}
