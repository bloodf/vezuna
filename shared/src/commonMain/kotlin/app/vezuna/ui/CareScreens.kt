package app.vezuna.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.vezuna.domain.ChatMessage
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.Medication as MedicationRecord
import app.vezuna.domain.Recurrence
import app.vezuna.llm.ModelPack
import app.vezuna.llm.ModelPackSource
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.resolve
import app.vezuna.localization.vitaText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun MedicationsScreen(state: AppUiState, controller: AppController, scope: CoroutineScope) {
    val data = state.data ?: return EmptyState(vitaText(L10nKey.NO_MEDICATIONS), vitaText(L10nKey.SELECT_PROFILE_FIRST))
    var name by remember { mutableStateOf("") }
    var strength by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("08:00") }
    var editTarget by remember { mutableStateOf<MedicationRecord?>(null) }
    var stopTarget by remember { mutableStateOf<MedicationRecord?>(null) }
    var deleteTarget by remember { mutableStateOf<MedicationRecord?>(null) }
    val nextTime = data.medications.filter { it.active }.flatMap { it.schedules }.flatMap { it.timesLocal }.minOrNull()
    ScreenColumn(vitaText(L10nKey.MEDICATIONS), vitaText(L10nKey.MEDICATIONS_SUBTITLE)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Schedule, vitaText(L10nKey.NEXT_MEDICATION_SCHEDULE), Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.size(14.dp))
                Column {
                    Text(if (nextTime == null) vitaText(L10nKey.NO_UPCOMING_SCHEDULE) else vitaText(L10nKey.NEXT_SCHEDULE, nextTime), style = MaterialTheme.typography.titleLarge)
                    Text(vitaText(L10nKey.RECORDED_REMINDERS_NOTE), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
        if (data.medications.isEmpty()) {
            EmptyState(vitaText(L10nKey.NO_MEDICATIONS_RECORDED), vitaText(L10nKey.NO_MEDICATIONS_RECORDED_EXPLANATION), Icons.Rounded.Medication)
        }
        data.medications.forEach { medication ->
            MedicationCard(
                medication,
                busy = state.loading,
                onEdit = { editTarget = medication },
                onTogglePause = { scope.launch { controller.setMedicationActive(medication.medicationId, !medication.active) } },
                onStop = { stopTarget = medication },
                onDelete = { deleteTarget = medication },
            )
        }
        editTarget?.let { medication ->
            EditMedicationDialog(
                medication,
                onSave = { n, st, ins, t -> editTarget = null; scope.launch { controller.updateMedication(medication.medicationId, n, st, ins, t) } },
                onDismiss = { editTarget = null },
            )
        }
        stopTarget?.let { medication ->
            ConfirmDialog(
                title = vitaText(L10nKey.STOP_MEDICATION),
                message = vitaText(L10nKey.STOP_MEDICATION_WARNING, medication.displayName),
                confirmLabel = vitaText(L10nKey.STOP_MEDICATION),
                onConfirm = { stopTarget = null; scope.launch { controller.stopMedication(medication.medicationId) } },
                onDismiss = { stopTarget = null },
            )
        }
        deleteTarget?.let { medication ->
            ConfirmDialog(
                title = vitaText(L10nKey.DELETE_MEDICATION),
                message = vitaText(L10nKey.DELETE_MEDICATION_WARNING, medication.displayName),
                confirmLabel = vitaText(L10nKey.DELETE),
                onConfirm = { deleteTarget = null; scope.launch { controller.deleteMedication(medication.medicationId) } },
                onDismiss = { deleteTarget = null },
            )
        }
        SectionHeading(vitaText(L10nKey.SECTION_ADD_MEDICATION), vitaText(L10nKey.SECTION_ADD_MEDICATION_SUBTITLE))
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.MEDICATION_NAME)) }, leadingIcon = { Icon(Icons.Rounded.Medication, null) }, singleLine = true)
        OutlinedTextField(strength, { strength = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.STRENGTH_AS_PRESCRIBED)) }, singleLine = true)
        OutlinedTextField(instructions, { instructions = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.PRESCRIBER_INSTRUCTIONS)) })
        OutlinedTextField(time, { time = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.DAILY_REMINDER_TIME)) }, leadingIcon = { Icon(Icons.Rounded.Schedule, null) }, singleLine = true)
        VitaButton(vitaText(L10nKey.SAVE_MEDICATION), Icons.Rounded.Medication, {
            scope.launch { controller.addMedication(name, strength, instructions, time); name = ""; strength = ""; instructions = "" }
        }, enabled = name.isNotBlank())
    }
}

@Composable
private fun MedicationCard(
    medication: MedicationRecord,
    busy: Boolean,
    onEdit: () -> Unit,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().vitaAnimateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(Icons.Rounded.Medication, null, Modifier.padding(8.dp).size(22.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(medication.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(listOfNotNull(medication.strength, medication.instructions).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (medication.active) StatusPill(vitaText(L10nKey.STATUS_ACTIVE), Icons.Rounded.CheckCircle, MaterialTheme.vita.successContainer, MaterialTheme.vita.onSuccessContainer)
                else StatusPill(
                    vitaText(if (medication.schedules.any { it.endDate != null }) L10nKey.STATUS_STOPPED else L10nKey.STATUS_PAUSED),
                    Icons.Rounded.Pause, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            medication.schedules.forEach { schedule ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Schedule, vitaText(L10nKey.SCHEDULE), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(8.dp))
                    Text("${recurrenceLabel(schedule.recurrence, schedule.intervalDays)} · ${schedule.timesLocal.joinToString()}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onEdit, enabled = !busy, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Edit, vitaText(L10nKey.EDIT_MEDICATION) + " — ${medication.displayName}") }
                IconButton(onTogglePause, enabled = !busy, modifier = Modifier.size(48.dp)) {
                    val label = vitaText(if (medication.active) L10nKey.PAUSE else L10nKey.RESUME)
                    Icon(if (medication.active) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "$label — ${medication.displayName}")
                }
                IconButton(onStop, enabled = !busy && medication.active, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Stop, vitaText(L10nKey.STOP_MEDICATION) + " — ${medication.displayName}") }
                IconButton(onDelete, enabled = !busy, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Delete, vitaText(L10nKey.DELETE_MEDICATION) + " — ${medication.displayName}") }
            }
        }
    }
}

@Composable
private fun EditMedicationDialog(medication: MedicationRecord, onSave: (String, String, String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(medication.displayName) }
    var strength by remember { mutableStateOf(medication.strength.orEmpty()) }
    var instructions by remember { mutableStateOf(medication.instructions.orEmpty()) }
    var time by remember { mutableStateOf(medication.schedules.firstOrNull()?.timesLocal?.firstOrNull().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(vitaText(L10nKey.EDIT_MEDICATION)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.MEDICATION_NAME)) }, singleLine = true)
                OutlinedTextField(strength, { strength = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.STRENGTH_AS_PRESCRIBED)) }, singleLine = true)
                OutlinedTextField(instructions, { instructions = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.PRESCRIBER_INSTRUCTIONS)) })
                OutlinedTextField(time, { time = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.DAILY_REMINDER_TIME)) }, singleLine = true)
            }
        },
        confirmButton = { TextButton({ onSave(name, strength, instructions, time) }, enabled = name.isNotBlank()) { Text(vitaText(L10nKey.SAVE)) } },
        dismissButton = { TextButton(onDismiss) { Text(vitaText(L10nKey.CANCEL)) } },
    )
}

@Composable
private fun recurrenceLabel(recurrence: Recurrence, intervalDays: Int): String = vitaText(when (recurrence) {
    Recurrence.DAILY -> L10nKey.RECURRENCE_DAILY
    Recurrence.WEEKLY -> L10nKey.RECURRENCE_WEEKLY
    Recurrence.EVERY_N_DAYS -> L10nKey.RECURRENCE_EVERY_N_DAYS
}, intervalDays)

@Composable
fun AiChatScreen(state: AppUiState, loadedModelId: String?, selectedModelId: String?, controller: AppController, scope: CoroutineScope) {
    val data = state.data ?: return EmptyState(vitaText(L10nKey.NO_CHAT), vitaText(L10nKey.SELECT_PROFILE_FIRST))
    var question by remember { mutableStateOf("") }
    val confirmed = data.observations.filter { it.confirmation == ConfirmationStatus.CONFIRMED }
    ScreenColumn(vitaText(L10nKey.ON_DEVICE_AI), vitaText(L10nKey.ON_DEVICE_AI_SUBTITLE)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.vita.ink)) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, vitaText(L10nKey.LOCAL_AI_MODEL), Modifier.size(30.dp), tint = MaterialTheme.vita.accentOnInk)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(when {
                        loadedModelId != null -> vitaText(L10nKey.MODEL_READY)
                        selectedModelId != null -> vitaText(L10nKey.MODEL_SELECTED_NOT_LOADED)
                        else -> vitaText(L10nKey.NO_MODEL_LOADED)
                    }, color = MaterialTheme.vita.onInk, style = MaterialTheme.typography.titleLarge)
                    Text(loadedModelId ?: selectedModelId ?: vitaText(L10nKey.CHOOSE_LOCAL_MODEL), color = MaterialTheme.vita.onInk.copy(alpha = 0.72f), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { controller.navigate(AppScreen.Models) }, Modifier.defaultMinSize(minHeight = 48.dp)) { Text(vitaText(L10nKey.MANAGE_LOCAL_MODELS), color = MaterialTheme.vita.accentOnInk) }
            }
        }
        SectionHeading(vitaText(L10nKey.RECORDS_IN_CONTEXT), vitaText(L10nKey.RECORDS_SELECTED, state.selectedObservationIds.size))
        if (confirmed.isEmpty()) {
            InlineNotice(vitaText(L10nKey.NO_CONFIRMED_RECORDS_CONTEXT), Icons.Rounded.Science)
        }
        confirmed.forEach { observation ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                Checkbox(
                    checked = observation.observationId in state.selectedObservationIds,
                    onCheckedChange = { controller.toggleObservation(observation.observationId) },
                )
                Column {
                    Text(observation.displayName, style = MaterialTheme.typography.bodyMedium)
                    Text(formatUiDate(observation.collectedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (data.chat.isEmpty()) {
            EmptyState(vitaText(L10nKey.START_PRIVATE_CONVERSATION), vitaText(L10nKey.START_PRIVATE_CONVERSATION_EXPLANATION), Icons.Rounded.AutoAwesome)
        }
        data.chat.forEach { message -> ChatBubble(message) }
        OutlinedTextField(
            value = question,
            onValueChange = { question = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(vitaText(L10nKey.ASK_RECORD_EDUCATION)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                if (question.isNotBlank() && loadedModelId != null && !state.loading) scope.launch { if (controller.ask(question)) question = "" }
            }),
        )
        VitaButton(vitaText(L10nKey.ASK_ON_DEVICE_MODEL), Icons.Rounded.AutoAwesome, {
            scope.launch { if (controller.ask(question)) question = "" }
        }, enabled = question.isNotBlank() && loadedModelId != null && !state.loading)
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val user = message.role == ChatMessage.Role.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.88f),
            shape = MaterialTheme.shapes.medium,
            color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(vitaText(if (user) L10nKey.YOU else L10nKey.VEZUNA), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(message.content, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun ModelsScreen(state: AppUiState, controller: AppController, scope: CoroutineScope, modelPackLoader: (suspend (ModelPack) -> ModelPackSource?)?) {
    val modelState by controller.modelManager.state.collectAsState()
    ScreenColumn(vitaText(L10nKey.LOCAL_GGUF_MODELS), vitaText(L10nKey.MODELS_SUBTITLE)) {
        InlineNotice(vitaText(L10nKey.MODEL_SECURITY_NOTICE), Icons.Rounded.Lock)
        modelState.error?.let { EmptyState(vitaText(L10nKey.MODEL_ACTION_FAILED), it.resolve(), Icons.Rounded.SmartToy) }
        if (modelState.busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp))
                Text(vitaText(L10nKey.VERIFYING_OR_LOADING_MODEL))
            }
        }
        if (controller.modelManager.catalog.packs.isEmpty()) {
            EmptyState(vitaText(L10nKey.NO_STORE_MODEL_PACKS), vitaText(L10nKey.NO_STORE_MODEL_PACKS_EXPLANATION), Icons.Rounded.SmartToy)
        }
        controller.modelManager.catalog.packs.forEach { pack ->
            val installed = modelState.installed.firstOrNull { it.pack.modelId == pack.modelId }
            Card(Modifier.fillMaxWidth().vitaAnimateContentSize()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.SmartToy, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.size(10.dp))
                        Text(pack.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (modelState.loadedModelId == pack.modelId) {
                            StatusPill(vitaText(L10nKey.LOADED), Icons.Rounded.CheckCircle, MaterialTheme.vita.successContainer, MaterialTheme.vita.onSuccessContainer)
                        }
                    }
                    Text(vitaText(L10nKey.MODEL_SIZE_SPEC, pack.sizeBytes / 1_000_000, pack.minimumRamBytes / 1_000_000, pack.maximumContextLength), style = MaterialTheme.typography.bodySmall)
                    Text(vitaText(L10nKey.MODEL_ASSET_IDS, pack.androidAssetPackId, pack.iosAssetPackId), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when {
                        installed == null && modelPackLoader != null -> VitaButton(vitaText(L10nKey.INSTALL_VERIFIED_STORE_PACK), Icons.Rounded.Download, {
                            scope.launch {
                                val source = modelPackLoader(pack)
                                if (source == null) controller.navigate(AppScreen.Models) else try {
                                    controller.installModel(pack, source.path)
                                } finally {
                                    source.release()
                                }
                            }
                        }, enabled = !modelState.busy)
                        installed == null -> InlineNotice(vitaText(L10nKey.AWAITING_NATIVE_STORE_LOADER), Icons.Rounded.Download)
                        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (modelState.loadedModelId != pack.modelId) {
                                VitaButton(vitaText(if (modelState.selectedModelId == pack.modelId) L10nKey.LOAD_SELECTED_MODEL else L10nKey.SWITCH_MODEL), Icons.Rounded.SwapHoriz, {
                                    scope.launch { controller.modelManager.select(pack.modelId) }
                                }, enabled = !modelState.busy)
                            }
                            VitaButton(vitaText(L10nKey.DELETE), Icons.Rounded.Delete, {
                                scope.launch { controller.modelManager.delete(pack.modelId) }
                            }, enabled = !modelState.busy, outlined = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(state: AppUiState, controller: AppController, scope: CoroutineScope, backupFiles: BackupFileAccess? = null) {
    val data = state.data ?: return EmptyState(vitaText(L10nKey.NO_SETTINGS), vitaText(L10nKey.SELECT_PROFILE_FIRST))
    ScreenColumn(vitaText(L10nKey.SETTINGS_EXPORT), vitaText(L10nKey.SETTINGS_SUBTITLE, data.profile.displayName)) {
        SectionHeading(vitaText(L10nKey.SECTION_OFFLINE_BOUNDARY))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CloudOff, vitaText(L10nKey.OFFLINE_HEALTH_RECORDS), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.size(10.dp))
                    Text(vitaText(L10nKey.NO_APP_SERVER), style = MaterialTheme.typography.titleMedium)
                }
                Text(vitaText(L10nKey.OFFLINE_RECORDS_DESCRIPTION), style = MaterialTheme.typography.bodyMedium)
                Text(vitaText(L10nKey.EXPORT_NEVER_GATED), style = MaterialTheme.typography.bodySmall)
            }
        }
        SectionHeading(vitaText(L10nKey.SECTION_DOCTOR_EXPORT))
        Text(vitaText(L10nKey.DOCTOR_EXPORT_EXPLANATION), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VitaButton(vitaText(L10nKey.PREPARE_CSV), Icons.Rounded.TableChart, { scope.launch { controller.exportCsv() } }, Modifier.weight(1f))
            VitaButton(vitaText(L10nKey.PREPARE_PDF), Icons.Rounded.PictureAsPdf, { scope.launch { controller.exportPdf() } }, Modifier.weight(1f))
        }
        state.csvExport?.let { csv ->
            Card {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.FileDownload, vitaText(L10nKey.CSV_READY), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(8.dp))
                        Text(vitaText(L10nKey.CSV_READY_BYTES, csv.encodeToByteArray().size), style = MaterialTheme.typography.titleSmall)
                    }
                    Text(csv.take(1200), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        state.pdfExport?.let { pdf ->
            StatusPill(vitaText(L10nKey.PDF_READY_BYTES, pdf.bytes.size), Icons.Rounded.PictureAsPdf, MaterialTheme.vita.successContainer, MaterialTheme.vita.onSuccessContainer)
        }
        BackupSettingsSection(state, controller, backupFiles)
        DeviceHealthSettings(state, data, controller, scope)
        SectionHeading(vitaText(L10nKey.SECTION_STORAGE_SECURITY))
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.VerifiedUser, vitaText(L10nKey.ENCRYPTED_VAULT), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(10.dp))
                    Text(vitaText(L10nKey.EXPORT_SECURITY_DESCRIPTION), style = MaterialTheme.typography.bodyMedium)
                }
                Text(vitaText(L10nKey.STORAGE_BACKUP_DESCRIPTION), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { controller.navigate(AppScreen.Models) }, Modifier.defaultMinSize(minHeight = 48.dp)) {
                    Icon(Icons.Rounded.SmartToy, null)
                    Spacer(Modifier.size(8.dp))
                    Text(vitaText(L10nKey.MODEL_STORAGE_AND_SWITCHING))
                }
            }
        }
    }
}
