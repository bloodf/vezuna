package com.bloodf.vitahearth

import android.app.Application
import com.bloodf.vitahearth.data.PersistentVitaHearthRepository
import com.bloodf.vitahearth.health.AndroidHealthConnectImporter
import com.bloodf.vitahearth.health.HealthConnectSyncCoordinator
import com.bloodf.vitahearth.llm.AndroidModelFileStore
import com.bloodf.vitahearth.llm.ModelCatalog
import com.bloodf.vitahearth.notifications.MedicationNotificationScheduler
import com.bloodf.vitahearth.security.AndroidSecureVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

class VitaHearthApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var vault: AndroidSecureVault
        private set
    lateinit var repository: PersistentVitaHearthRepository
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
        repository = PersistentVitaHearthRepository(vault)
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
