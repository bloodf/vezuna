package app.vezuna

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import androidx.compose.ui.platform.LocalContext
import android.provider.Settings
import app.vezuna.ui.more.MorePlatform
import app.vezuna.export.NativeExport
import app.vezuna.importer.AndroidSourcePicker
import app.vezuna.llm.ModelPackLoadState
import app.vezuna.llm.PlayAssetPackLoader
import app.vezuna.ui.AppController
import app.vezuna.ui.BackupFileAccess
import app.vezuna.ui.ReminderPlatformState
import app.vezuna.ui.AppScreen
import app.vezuna.ui.VezunaApp
import app.vezuna.ui.nav.NativeAction
import app.vezuna.ui.nav.NativeActions
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VezunaAndroidApp(
    controller: AppController,
    scope: CoroutineScope,
    sourcePicker: AndroidSourcePicker,
    modelLoader: PlayAssetPackLoader,
    messageFlow: StateFlow<NativeText?>,
    exportFlow: StateFlow<NativeExport?>,
    busyFlow: StateFlow<Boolean>,
    onHealthConnect: () -> Unit,
    onReminders: () -> Unit,
    backupFiles: BackupFileAccess,
    reminders: ReminderPlatformState,
    onPresentExport: (NativeExport) -> Unit,
    onDismissExport: () -> Unit,
    onShareExport: (NativeExport) -> Unit,
    onSaveExport: (NativeExport) -> Unit,
    onDismissMessage: () -> Unit,
    startRoute: AppScreen? = null,
) {
    val state by controller.state.collectAsState()
    val message by messageFlow.collectAsState()
    val pendingExport by exportFlow.collectAsState()
    val nativeBusy by busyFlow.collectAsState()
    val modelLoadState by modelLoader.state.collectAsState()

    LaunchedEffect(state.csvExport) {
        state.csvExport?.let { onPresentExport(NativeExport("vezuna-doctor-export.csv", "text/csv", it.encodeToByteArray())) }
    }
    LaunchedEffect(state.pdfExport) {
        state.pdfExport?.let { onPresentExport(NativeExport(it.fileName, "application/pdf", it.bytes)) }
    }
    MaterialTheme {
        val context = LocalContext.current
        remember(context) { installMorePlatform(context); true }
        // Followed live: turning animations off in system settings applies without restarting the app.
        val animationsEnabled by produceState(animationScale(context) > 0f, context) {
            val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    value = animationScale(context) > 0f
                }
            }
            context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
            awaitDispose { context.contentResolver.unregisterContentObserver(observer) }
        }
        // The shared shell owns the window insets: its pine header bands reach under the status bar, so the
        // status bar icons are light while the shell shows, and follow the theme again when it leaves (lock).
        val view = LocalView.current
        val darkTheme = MorePlatform.themeMode.isDark(isSystemInDarkTheme())
        DisposableEffect(view, darkTheme) {
            val window = (view.context as? android.app.Activity)?.window
            val insets = window?.let { WindowCompat.getInsetsController(it, view) }
            insets?.isAppearanceLightStatusBars = false
            onDispose { insets?.isAppearanceLightStatusBars = !darkTheme }
        }
        VezunaApp(
            controller = controller,
            scope = scope,
            sourcePicker = { sourcePicker.pick(controller.importSession) },
            modelPackLoader = modelLoader::load,
            animationsEnabled = animationsEnabled,
            backupFiles = backupFiles,
            reminders = reminders,
            nativeActions = NativeActions(
                available = androidNativeActions,
                // Health stays listed but disabled while a device import runs, as the old menu item was.
                enabled = if (nativeBusy) androidNativeActions - NativeAction.HEALTH else androidNativeActions,
            ) { action ->
                when (action) {
                    NativeAction.HEALTH -> onHealthConnect()
                    NativeAction.REMINDERS -> onReminders()
                    NativeAction.PRIVACY -> context.startActivity(android.content.Intent(context, PermissionsRationaleActivity::class.java))
                    NativeAction.NOTICES -> context.startActivity(android.content.Intent(context, ThirdPartyNoticesActivity::class.java))
                    else -> Unit
                }
            },
            startRoute = startRoute,
        )

        when (val current = modelLoadState) {
            is ModelPackLoadState.Failed,
            is ModelPackLoadState.Unavailable,
            ModelPackLoadState.WaitingForWifi,
            is ModelPackLoadState.Downloading,
            -> AlertDialog(
                onDismissRequest = modelLoader::clearState,
                title = { Text(nativeMessage(NativeKey.PLAY_MODEL_DELIVERY).resolve(context)) },
                text = {
                    Text(when (current) {
                        is ModelPackLoadState.Failed -> current.message.resolve(context)
                        is ModelPackLoadState.Unavailable -> current.reason.resolve(context)
                        ModelPackLoadState.WaitingForWifi -> nativeMessage(NativeKey.PLAY_WAITING_WIFI).resolve(context)
                        is ModelPackLoadState.Downloading -> nativeMessage(NativeKey.PLAY_DOWNLOADING, current.progressPercent).resolve(context)
                        else -> ""
                    })
                },
                confirmButton = { TextButton(onClick = modelLoader::clearState) { Text(nativeMessage(NativeKey.CLOSE).resolve(context)) } },
            )
            else -> Unit
        }

        pendingExport?.let { export ->
            AlertDialog(
                onDismissRequest = onDismissExport,
                title = { Text(nativeMessage(NativeKey.EXPORT_READY).resolve(context)) },
                text = { Text(nativeMessage(NativeKey.EXPORT_READY_MESSAGE, export.fileName, export.bytes.size).resolve(context)) },
                confirmButton = { TextButton(onClick = { onShareExport(export) }) { Text(nativeMessage(NativeKey.SHARE).resolve(context)) } },
                dismissButton = {
                    TextButton(onClick = { onSaveExport(export) }) { Text(nativeMessage(NativeKey.SAVE).resolve(context)) }
                    TextButton(onClick = onDismissExport) { Text(nativeMessage(NativeKey.CLOSE).resolve(context)) }
                },
            )
        }

        message?.let {
            AlertDialog(
                onDismissRequest = onDismissMessage,
                title = { Text(nativeMessage(NativeKey.DEVICE_INTEGRATION).resolve(context)) },
                text = { Text(it.resolve(context)) },
                confirmButton = { TextButton(onClick = onDismissMessage) { Text(nativeMessage(NativeKey.OK).resolve(context)) } },
            )
        }
    }
}

private fun animationScale(context: android.content.Context): Float =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

private val androidNativeActions = setOf(NativeAction.HEALTH, NativeAction.REMINDERS, NativeAction.PRIVACY, NativeAction.NOTICES)
