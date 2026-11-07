package app.vezuna

import androidx.compose.runtime.snapshotFlow
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
import app.vezuna.ui.more.MorePlatform
import app.vezuna.ui.AppLockHost
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import app.vezuna.export.AndroidBackupFiles
import app.vezuna.export.AndroidExportCoordinator
import app.vezuna.export.NativeExport
import app.vezuna.export.UserCancelledExport
import app.vezuna.ui.export.ExportPlatform
import app.vezuna.ui.export.SaveOutcome
import app.vezuna.health.HealthConnectPermissionFlow
import app.vezuna.importer.AndroidSourcePicker
import app.vezuna.importer.NativeOcrExtractionService
import app.vezuna.llm.PlayAssetPackLoader
import app.vezuna.security.BackupService
import app.vezuna.ui.AppController
import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.llm.InstalledModelManager
import app.vezuna.llm.LocalHealthChat
import app.vezuna.llm.ModelCatalog
import app.vezuna.llm.llamatikLocalLlm
import app.vezuna.ui.DesignFixture
import app.vezuna.ui.nav.routeFromName
import app.vezuna.ui.BackupRecovery
import app.vezuna.notifications.ReminderPreferences
import app.vezuna.ui.NotificationAccess
import androidx.core.app.NotificationManagerCompat
import app.vezuna.ui.PreviousGenerationNotice
import app.vezuna.ui.ReminderPlatformState
import app.vezuna.ui.VaultGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AndroidMainActivity : FragmentActivity() {
    private val app: VezunaApplication get() = application as VezunaApplication
    private val session by viewModels<AndroidSessionViewModel> {
        viewModelFactory { initializer { AndroidSessionViewModel(app) } }
    }
    internal val controller: AppController get() = designFixture ?: session.controller

    /**
     * Debug only: `--ez fixture true` shows the synthetic DATA_AND_COPY household from an in-memory repository,
     * and `--es route <Name>` opens a route, for the screenshot harness. Fixture screens skip the vault gate and
     * never read or write records or model files; the app process may still open the vault in the background.
     */
    private val designFixture: AppController? by lazy {
        if (!BuildConfig.DEBUG || !intent.getBooleanExtra("fixture", false)) return@lazy null
        val repository = InMemoryVezunaRepository()
        val llm = llamatikLocalLlm()
        AppController(
            repository,
            InstalledModelManager(ModelCatalog.unconfigured(), DesignFixture.NoModelFiles, llm),
            LocalHealthChat(repository, llm),
        ).also { fixture ->
            lifecycleScope.launch {
                DesignFixture.installForPreview(repository)
                fixture.initialize()
            }
        }
    }
    private val debugStartRoute get() = if (BuildConfig.DEBUG) intent.getStringExtra("route")?.let(::routeFromName) else null
    private lateinit var extraction: NativeOcrExtractionService
    private lateinit var sourcePicker: AndroidSourcePicker
    private lateinit var exports: AndroidExportCoordinator
    private lateinit var backupFiles: AndroidBackupFiles
    private lateinit var pendingHealthRequest: PendingHealthPermissionRequest
    private lateinit var healthPermissions: HealthConnectPermissionFlow
    private val modelLoader: PlayAssetPackLoader get() = session.modelLoader
    private var deviceAuthenticator: AndroidDeviceAuthenticator? = null

    private val reminderPreferences by lazy { ReminderPreferences(this) }

    private val reminders by lazy {
        ReminderPlatformState(
            hideMedicationNames = reminderPreferences.hideMedicationNames,
            persistHideMedicationNames = { reminderPreferences.hideMedicationNames = it },
            openExactAlarmSettings = ::openExactAlarmSettings,
            openNotificationSettings = ::openNotificationSettings,
        )
    }

    private val mutableMessage = MutableStateFlow<NativeText?>(null)
    private val mutableBusy = MutableStateFlow(false)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        reminderPreferences.notificationPermissionRequested = true
        refreshNotificationAccess()
        mutableMessage.value = nativeMessage(
            if (granted || Build.VERSION.SDK_INT < 33) NativeKey.REMINDERS_ENABLED else NativeKey.NOTIFICATION_PERMISSION_DENIED,
        )
    }

    /** Status and navigation bars follow the app theme (System, Light or Dark), not only the OS night mode. */
    private fun applySystemBars(darkTheme: Boolean) {
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installMorePlatform(this)
        val systemDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        lifecycleScope.launch {
            snapshotFlow { MorePlatform.themeMode }.collect { applySystemBars(it.isDark(systemDark)) }
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
        installExportPlatform()
        pendingHealthRequest = PendingHealthPermissionRequest()
        healthPermissions = HealthConnectPermissionFlow(this) { granted ->
            mutableBusy.value = false
            if (!pendingHealthRequest.consume(granted, ::synchronizeHealthConnect)) {
                mutableMessage.value = nativeMessage(NativeKey.HEALTH_CONNECT_PERMISSION_DENIED)
            }
        }
        val messageFlow = mutableMessage.asStateFlow()
        val busyFlow = mutableBusy.asStateFlow()
        setContent {
            val vaultState by app.vaultState.collectAsState()
            val openedFromPrevious by app.repository.openedFromPreviousGeneration.collectAsState()
            val shell: @Composable () -> Unit = {
                VezunaAndroidApp(
                    controller = controller,
                    scope = session.viewModelScope,
                    sourcePicker = sourcePicker,
                    modelLoader = modelLoader,
                    messageFlow = messageFlow,
                    busyFlow = busyFlow,
                    onHealthConnect = ::openHealthConnect,
                    onboardingHealthAvailable = app.healthConnect.sdkStatus() == HealthConnectClient.SDK_AVAILABLE,
                    onReminders = ::requestReminders,
                    backupFiles = backupFiles.access,
                    reminders = reminders,
                    onDismissMessage = { mutableMessage.value = null },
                    startRoute = debugStartRoute,
                )
            }
            if (designFixture != null) {
                shell()
            } else {
                AppLockHost(app.appLock) {
                    VaultGate(
                        vaultState,
                        onRetry = app::retryVault,
                        onReset = app::resetVault,
                        backup = BackupRecovery(BackupService(), backupFiles.access, app::restoreVaultFromBackup),
                    ) {
                        PreviousGenerationNotice(openedFromPrevious, onDismiss = app.repository::acknowledgePreviousGeneration)
                        shell()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val wasDenied = reminders.exactAlarmsDenied.value
        val denied = !app.notifications.canScheduleExactAlarms()
        reminders.setExactAlarmsDenied(denied)
        refreshNotificationAccess()
        // Returning from the system screen with the permission now granted: re-arm alarms as exact.
        if (wasDenied && !denied) lifecycleScope.launch { app.notifications.rescheduleAll(app.repository) }
    }

    /** Allowed, never asked (Android 13+ only), or denied: what Care shows about reminders appearing. */
    private fun refreshNotificationAccess() {
        val enabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val neverAsked = Build.VERSION.SDK_INT >= 33 && !reminderPreferences.notificationPermissionRequested &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        val access = when {
            enabled -> NotificationAccess.ALLOWED
            neverAsked -> NotificationAccess.NOT_REQUESTED
            else -> NotificationAccess.DENIED
        }
        val becameAllowed = access == NotificationAccess.ALLOWED && reminders.notificationAccess.value != NotificationAccess.ALLOWED
        reminders.setNotificationAccess(access)
        // A reminder that came due while notifications were off is armed again, and shown, as soon as they are allowed.
        if (becameAllowed) lifecycleScope.launch { app.notifications.rescheduleAll(app.repository) }
    }

    private fun openNotificationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } catch (missing: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
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
        modelLoader.bindActivity(this)
    }

    override fun onStop() {
        modelLoader.bindActivity(null)
        app.appLock.onBackground()
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) app.appLock.lockNow()
        if (app.deviceAuthenticator === deviceAuthenticator) app.deviceAuthenticator = null
        sourcePicker.close()
        extraction.close()
        clearExportPlatform()
        super.onDestroy()
    }

    private fun openHealthConnect(expected: app.vezuna.data.VitalsSyncContext?) {
        if (mutableBusy.value) return
        when (app.healthConnect.sdkStatus()) {
            HealthConnectClient.SDK_AVAILABLE -> {
                mutableBusy.value = true
                lifecycleScope.launch {
                    try {
                        val captured = expected ?: app.repository.captureVitalsSync()
                        if (app.healthConnect.hasPermissions()) {
                            mutableBusy.value = false
                            synchronizeHealthConnect(captured)
                        } else {
                            pendingHealthRequest.store(captured)
                            healthPermissions.request()
                        }
                    } catch (error: Throwable) {
                        pendingHealthRequest.consume(false) {}
                        mutableBusy.value = false
                        mutableMessage.value = error.toNativeText(NativeKey.HEALTH_CONNECT_IMPORT_FAILED)
                    }
                }
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                mutableMessage.value = nativeMessage(NativeKey.HEALTH_CONNECT_UPDATE_REQUIRED)
                openHealthConnectUpdate()
            }
            else -> mutableMessage.value = nativeMessage(NativeKey.HEALTH_CONNECT_UNAVAILABLE)
        }
    }

    /** Hands off to the store page that installs or updates the Health Connect provider (system-owned UI). */
    private fun openHealthConnectUpdate() {
        val uri = Uri.parse("market://details?id=$HEALTH_CONNECT_PROVIDER&url=healthconnect%3A%2F%2Fonboarding")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending").putExtra("overlay", true).putExtra("callerId", packageName))
        } catch (missing: ActivityNotFoundException) {
            // No store app: the message already explains how to update from the device.
        }
    }

    private fun synchronizeHealthConnect(expected: app.vezuna.data.VitalsSyncContext) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        lifecycleScope.launch {
            try {
                val imported = app.healthSync.synchronizeOutcome(expected)
                imported.presentHealthConnectOutcome(
                    refresh = { controller.refreshDashboard() },
                    publish = { mutableMessage.value = it },
                )
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

    /** The shared export preview hands its exact bytes to the system save (SAF) and share sheets through these hooks. */
    private fun installExportPlatform() {
        val save: suspend (String, String, ByteArray) -> SaveOutcome = { fileName, mimeType, bytes ->
            exports.save(NativeExport(fileName, mimeType, bytes)).fold(
                onSuccess = { SaveOutcome.SAVED },
                onFailure = { if (it is UserCancelledExport) SaveOutcome.CANCELLED else throw it },
            )
        }
        val share: suspend (String, String, ByteArray) -> Unit = { fileName, mimeType, bytes -> exports.share(NativeExport(fileName, mimeType, bytes)).getOrThrow() }
        exportSave = save
        exportShare = share
        ExportPlatform.save = save
        ExportPlatform.share = share
    }

    // The hooks capture this activity; onDestroy clears them unless a newer activity already replaced them.
    private var exportSave: (suspend (String, String, ByteArray) -> SaveOutcome)? = null
    private var exportShare: (suspend (String, String, ByteArray) -> Unit)? = null

    private fun clearExportPlatform() {
        if (ExportPlatform.save === exportSave) ExportPlatform.save = null
        if (ExportPlatform.share === exportShare) ExportPlatform.share = null
    }

    private fun String.safeFilePart(): String = map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("").take(40).ifBlank { "profile" }
}

private const val HEALTH_CONNECT_PROVIDER = "com.google.android.apps.healthdata"

internal class PendingHealthPermissionRequest {
    private var pending: app.vezuna.data.VitalsSyncContext? = null

    fun store(context: app.vezuna.data.VitalsSyncContext) {
        pending = context
    }

    fun consume(granted: Boolean, synchronize: (app.vezuna.data.VitalsSyncContext) -> Unit): Boolean {
        val expected = pending
        pending = null
        if (!granted || expected == null) return false
        synchronize(expected)
        return true
    }
}
