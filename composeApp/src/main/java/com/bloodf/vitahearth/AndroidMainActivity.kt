package com.bloodf.vitahearth

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.res.Configuration
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.lifecycleScope
import com.bloodf.vitahearth.export.AndroidExportCoordinator
import com.bloodf.vitahearth.export.NativeExport
import com.bloodf.vitahearth.export.UserCancelledExport
import com.bloodf.vitahearth.health.HealthConnectPermissionFlow
import com.bloodf.vitahearth.importer.AndroidSourcePicker
import com.bloodf.vitahearth.importer.NativeOcrExtractionService
import com.bloodf.vitahearth.license.PlayLvlLicenseGate
import com.bloodf.vitahearth.llm.InstalledModelManager
import com.bloodf.vitahearth.llm.LocalHealthChat
import com.bloodf.vitahearth.llm.PlayAssetPackLoader
import com.bloodf.vitahearth.llm.llamatikLocalLlm
import com.bloodf.vitahearth.security.BackupService
import com.bloodf.vitahearth.security.CipherEnvelope
import com.bloodf.vitahearth.ui.AppController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class AndroidMainActivity : ComponentActivity() {
    private val app: VitaHearthApplication get() = application as VitaHearthApplication
    internal lateinit var controller: AppController
    private lateinit var extraction: NativeOcrExtractionService
    private lateinit var sourcePicker: AndroidSourcePicker
    private lateinit var exports: AndroidExportCoordinator
    private lateinit var healthPermissions: HealthConnectPermissionFlow
    private lateinit var licenseGate: PlayLvlLicenseGate
    private lateinit var modelLoader: PlayAssetPackLoader

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
        extraction = NativeOcrExtractionService(this)
        sourcePicker = AndroidSourcePicker(this, extraction)
        exports = AndroidExportCoordinator(this)
        licenseGate = PlayLvlLicenseGate(this)
        modelLoader = PlayAssetPackLoader(this, licenseGate)
        healthPermissions = HealthConnectPermissionFlow(this) { granted ->
            if (granted) synchronizeHealthConnect() else mutableMessage.value = nativeMessage(NativeKey.HEALTH_CONNECT_PERMISSION_DENIED)
        }
        val llm = llamatikLocalLlm()
        controller = AppController(
            app.repository,
            InstalledModelManager(app.modelCatalog, app.modelFiles, llm),
            LocalHealthChat(app.repository, llm),
        )
        lifecycleScope.launch { controller.initialize() }
        setContent {
            VitaHearthAndroidApp(
                controller = controller,
                sourcePicker = sourcePicker,
                modelLoader = modelLoader,
                messageFlow = mutableMessage.asStateFlow(),
                exportFlow = mutableExport.asStateFlow(),
                busyFlow = mutableBusy.asStateFlow(),
                onHealthConnect = ::openHealthConnect,
                onReminders = ::requestReminders,
                onBackup = ::prepareBackup,
                onPresentExport = { mutableExport.value = it },
                onDismissExport = { mutableExport.value = null },
                onShareExport = ::shareExport,
                onSaveExport = ::saveExport,
                onDismissMessage = { mutableMessage.value = null },
            )
        }
    }

    override fun onDestroy() {
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

    private fun prepareBackup() {
        val data = controller.state.value.data
        if (data == null) {
            mutableMessage.value = nativeMessage(NativeKey.BACKUP_SELECT_PROFILE)
            return
        }
        mutableBusy.value = true
        lifecycleScope.launch {
            try {
                val envelope = BackupService(app.vault).create(data)
                val bytes = Json.encodeToString(CipherEnvelope.serializer(), envelope).encodeToByteArray()
                mutableExport.value = NativeExport("vitahearth-${data.profile.displayName.safeFilePart()}.vhbak", "application/octet-stream", bytes)
            } catch (error: Throwable) {
                mutableMessage.value = error.toNativeText(NativeKey.BACKUP_CREATE_FAILED)
            } finally {
                mutableBusy.value = false
            }
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
