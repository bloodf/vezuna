package app.vezuna

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.content.res.Configuration
import android.graphics.Color
import androidx.fragment.app.FragmentActivity
import android.view.WindowManager
import app.vezuna.ui.AppLockHost
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.lifecycleScope
import app.vezuna.export.AndroidBackupFiles
import app.vezuna.export.AndroidExportCoordinator
import app.vezuna.export.NativeExport
import app.vezuna.export.UserCancelledExport
import app.vezuna.health.HealthConnectPermissionFlow
import app.vezuna.importer.AndroidSourcePicker
import app.vezuna.importer.NativeOcrExtractionService
import app.vezuna.license.PlayLvlLicenseGate
import app.vezuna.llm.InstalledModelManager
import app.vezuna.llm.LocalHealthChat
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import app.vezuna.llm.loadBundledModelCatalog
import app.vezuna.llm.ModelCatalog
import app.vezuna.llm.PlayAssetPackLoader
import app.vezuna.llm.llamatikLocalLlm
import app.vezuna.security.BackupService
import app.vezuna.security.VaultState
import app.vezuna.ui.AppController
import app.vezuna.ui.BackupRecovery
import app.vezuna.notifications.ReminderPreferences
import app.vezuna.ui.PreviousGenerationNotice
import app.vezuna.ui.ReminderPlatformState
import app.vezuna.ui.VaultGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AndroidMainActivity : FragmentActivity() {
    private val app: VezunaApplication get() = application as VezunaApplication
    internal lateinit var controller: AppController
    private lateinit var extraction: NativeOcrExtractionService
    private lateinit var sourcePicker: AndroidSourcePicker
    private lateinit var exports: AndroidExportCoordinator
    private lateinit var backupFiles: AndroidBackupFiles
    private lateinit var healthPermissions: HealthConnectPermissionFlow
    private lateinit var licenseGate: PlayLvlLicenseGate
    private lateinit var modelLoader: PlayAssetPackLoader
    private var deviceAuthenticator: AndroidDeviceAuthenticator? = null

    private val reminders by lazy {
        val preferences = ReminderPreferences(this)
        ReminderPlatformState(
            hideMedicationNames = preferences.hideMedicationNames,
            persistHideMedicationNames = { preferences.hideMedicationNames = it },
            openExactAlarmSettings = ::openExactAlarmSettings,
        )
    }

    private val mutableMessage = MutableStateFlow<NativeText?>(null)
    private val mutableExport = MutableStateFlow<NativeExport?>(null)
    private val mutableBusy = MutableStateFlow(false)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        mutableMessage.value = nativeMessage(
            if (granted || Build.VERSION.SDK_INT < 33) NativeKey.REMINDERS_ENABLED else NativeKey.NOTIFICATION_PERMISSION_DENIED,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val darkTheme = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
        )
        val chromeColor = if (darkTheme) Color.rgb(12, 32, 39) else Color.rgb(245, 250, 249)
        window.statusBarColor = chromeColor
        window.navigationBarColor = chromeColor
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
        // App lock: the prompt needs this activity; FLAG_SECURE (app switcher, screenshots) follows the setting.
        deviceAuthenticator = AndroidDeviceAuthenticator(this)
        app.deviceAuthenticator = deviceAuthenticator
        app.appLock.refresh()
        lifecycleScope.launch {
            app.appLock.state.map { it.enabled }.distinctUntilChanged().collect { enabled ->
                if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        extraction = NativeOcrExtractionService(this)
        sourcePicker = AndroidSourcePicker(this, extraction)
        exports = AndroidExportCoordinator(this)
        backupFiles = AndroidBackupFiles(this, exports)
        licenseGate = PlayLvlLicenseGate(this)
        modelLoader = PlayAssetPackLoader(this, licenseGate)
        healthPermissions = HealthConnectPermissionFlow(this) { granted ->
            if (granted) synchronizeHealthConnect() else mutableMessage.value = nativeMessage(NativeKey.HEALTH_CONNECT_PERMISSION_DENIED)
        }
        val llm = llamatikLocalLlm()
        val modelManager = InstalledModelManager(ModelCatalog.unconfigured(), app.modelFiles, llm)
        controller = AppController(
            app.repository,
            modelManager,
            LocalHealthChat(app.repository, llm),
            backup = BackupService(app.vault),
        )
        // Only after the vault is readable: initialize() may create a default profile.
        lifecycleScope.launch {
            app.vaultState.first { it is VaultState.Ready }
            // Same bundled catalog file as iOS, read off the main thread.
            withContext(Dispatchers.IO) { modelManager.configureCatalog(::loadBundledModelCatalog) }
            controller.initialize()
        }
        setContent {
            val vaultState by app.vaultState.collectAsState()
            val openedFromPrevious by app.repository.openedFromPreviousGeneration.collectAsState()
            AppLockHost(app.appLock) {
            VaultGate(
                vaultState,
                onRetry = app::retryVault,
                onReset = app::resetVault,
                backup = BackupRecovery(BackupService(), backupFiles.access, app::restoreVaultFromBackup),
            ) {
            PreviousGenerationNotice(openedFromPrevious, onDismiss = app.repository::acknowledgePreviousGeneration)
            VezunaAndroidApp(
                controller = controller,
                sourcePicker = sourcePicker,
                modelLoader = modelLoader,
                messageFlow = mutableMessage.asStateFlow(),
                exportFlow = mutableExport.asStateFlow(),
                busyFlow = mutableBusy.asStateFlow(),
                onHealthConnect = ::openHealthConnect,
                onReminders = ::requestReminders,
                backupFiles = backupFiles.access,
                reminders = reminders,
                onPresentExport = { mutableExport.value = it },
                onDismissExport = { mutableExport.value = null },
                onShareExport = ::shareExport,
                onSaveExport = ::saveExport,
                onDismissMessage = { mutableMessage.value = null },
            )
            }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val wasDenied = reminders.exactAlarmsDenied.value
        val denied = !app.notifications.canScheduleExactAlarms()
        reminders.setExactAlarmsDenied(denied)
        // Returning from the system screen with the permission now granted: re-arm alarms as exact.
        if (wasDenied && !denied) lifecycleScope.launch { app.notifications.rescheduleAll(app.repository) }
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        } catch (missing: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    override fun onStart() {
        super.onStart()
        app.appLock.refresh()
        app.appLock.onForeground()
    }

    override fun onStop() {
        app.appLock.onBackground()
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) app.appLock.lockNow()
        if (app.deviceAuthenticator === deviceAuthenticator) app.deviceAuthenticator = null
        sourcePicker.clearExtraction()
        extraction.close()
        licenseGate.close()
        super.onDestroy()
    }

    private fun openHealthConnect() {
        when (app.healthConnect.sdkStatus()) {
            HealthConnectClient.SDK_AVAILABLE -> lifecycleScope.launch {
                if (app.healthConnect.hasPermissions()) synchronizeHealthConnect() else healthPermissions.request()
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                mutableMessage.value = nativeMessage(NativeKey.HEALTH_CONNECT_UPDATE_REQUIRED)
            else -> mutableMessage.value = nativeMessage(NativeKey.HEALTH_CONNECT_UNAVAILABLE)
        }
    }

    private fun synchronizeHealthConnect() {
        if (mutableBusy.value) return
        mutableBusy.value = true
        lifecycleScope.launch {
            try {
                val imported = app.healthSync.synchronize()
                controller.refreshDashboard()
                mutableMessage.value = nativePlural(NativeKey.HEALTH_CONNECT_IMPORT_COMPLETE, imported)
            } catch (error: Throwable) {
                mutableMessage.value = error.toNativeText(NativeKey.HEALTH_CONNECT_IMPORT_FAILED)
            } finally {
                mutableBusy.value = false
            }
        }
    }

    private fun requestReminders() {
        lifecycleScope.launch { app.notifications.rescheduleAll(app.repository) }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            mutableMessage.value = nativeMessage(NativeKey.REMINDER_SCHEDULES_ACTIVE)
        }
    }

    private fun shareExport(export: NativeExport) {
        lifecycleScope.launch {
            exports.share(export).onSuccess {
                mutableExport.value = null
            }.onFailure { mutableMessage.value = it.toNativeText(NativeKey.SHARE_EXPORT_FAILED) }
        }
    }

    private fun saveExport(export: NativeExport) {
        lifecycleScope.launch {
            val result = exports.save(export)
            result.onSuccess {
                mutableExport.value = null
                mutableMessage.value = nativeMessage(NativeKey.EXPORT_SAVED)
            }.onFailure {
                if (it !is UserCancelledExport) mutableMessage.value = it.toNativeText(NativeKey.EXPORT_SAVE_FAILED)
            }
        }
    }

    private fun String.safeFilePart(): String = map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("").take(40).ifBlank { "profile" }
}
