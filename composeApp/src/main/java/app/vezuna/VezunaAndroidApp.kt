package app.vezuna

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.provider.Settings
import app.vezuna.export.NativeExport
import app.vezuna.importer.AndroidSourcePicker
import app.vezuna.llm.ModelPackLoadState
import app.vezuna.llm.PlayAssetPackLoader
import app.vezuna.ui.AppController
import app.vezuna.ui.BackupFileAccess
import app.vezuna.ui.VezunaApp
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
    onPresentExport: (NativeExport) -> Unit,
    onDismissExport: () -> Unit,
    onShareExport: (NativeExport) -> Unit,
    onSaveExport: (NativeExport) -> Unit,
    onDismissMessage: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val message by messageFlow.collectAsState()
    val pendingExport by exportFlow.collectAsState()
    val nativeBusy by busyFlow.collectAsState()
    val extraction by sourcePicker.lastExtraction.collectAsState()
    val modelLoadState by modelLoader.state.collectAsState()
    var deviceMenu by remember { mutableStateOf(false) }

    LaunchedEffect(state.csvExport) {
        state.csvExport?.let { onPresentExport(NativeExport("vezuna-doctor-export.csv", "text/csv", it.encodeToByteArray())) }
    }
    LaunchedEffect(state.pdfExport) {
        state.pdfExport?.let { onPresentExport(NativeExport(it.fileName, "application/pdf", it.bytes)) }
    }
    MaterialTheme {
        val context = LocalContext.current
        val animationsEnabled = remember(context) {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }
        Scaffold { padding ->
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(padding)) {
                VezunaApp(
                    controller = controller,
                    scope = scope,
                    sourcePicker = sourcePicker::pick,
                    modelPackLoader = modelLoader::load,
                    onDevice = { deviceMenu = true },
                    animationsEnabled = animationsEnabled,
                    backupFiles = backupFiles,
                )
                DropdownMenu(expanded = deviceMenu, onDismissRequest = { deviceMenu = false }) {
                    DropdownMenuItem(text = { Text(nativeMessage(NativeKey.HEALTH_CONNECT_IMPORT_MENU).resolve(context)) }, onClick = { deviceMenu = false; onHealthConnect() }, enabled = !nativeBusy)
                    DropdownMenuItem(
                        text = { Text(nativeMessage(NativeKey.PRIVACY_POLICY).resolve(context)) },
                        onClick = {
                            deviceMenu = false
                            context.startActivity(android.content.Intent(context, PermissionsRationaleActivity::class.java))
                        },
                    )
                    DropdownMenuItem(text = { Text(nativeMessage(NativeKey.MEDICATION_REMINDERS_MENU).resolve(context)) }, onClick = { deviceMenu = false; onReminders() })
                    DropdownMenuItem(
                        text = { Text(nativeMessage(NativeKey.THIRD_PARTY_NOTICES).resolve(context)) },
                        onClick = {
                            deviceMenu = false
                            context.startActivity(android.content.Intent(context, ThirdPartyNoticesActivity::class.java))
                        },
                    )
                }
            }
        }

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

        extraction?.let { result ->
            AlertDialog(
                onDismissRequest = sourcePicker::clearExtraction,
                title = { Text(nativeMessage(NativeKey.OFFLINE_SOURCE_EXTRACTION).resolve(context)) },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text(nativeMessage(if (result.usedEmbeddedText) NativeKey.EXTRACTION_EMBEDDED else NativeKey.EXTRACTION_OCR).resolve(context))
                        result.pages.forEach { page ->
                            val sourceLabel = nativeMessage(if (page.source.name == "EMBEDDED_PDF_TEXT") NativeKey.EMBEDDED_TEXT else NativeKey.OCR).resolve(context)
                            Text(nativeMessage(NativeKey.EXTRACTION_PAGE, page.pageNumber, sourceLabel).resolve(context), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                            Text(page.text.ifBlank { nativeMessage(NativeKey.NO_TEXT_PAGE).resolve(context) })
                        }
                    }
                },
                confirmButton = { TextButton(onClick = sourcePicker::clearExtraction) { Text(nativeMessage(NativeKey.CLOSE).resolve(context)) } },
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
