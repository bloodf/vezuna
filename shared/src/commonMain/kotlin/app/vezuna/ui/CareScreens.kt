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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import kotlinx.coroutines.Job
import app.vezuna.export.ExportOptions
import app.vezuna.platform.currentInstant
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
import androidx.compose.material3.FilterChip
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
import app.vezuna.localization.ContentLanguage
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.formatUiDateTime
import app.vezuna.localization.resolve
import app.vezuna.localization.vitaText
import app.vezuna.platform.currentInstant
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.coroutines.launch

@Composable
fun MedicationsScreen(state: AppUiState, controller: AppController, scope: CoroutineScope, reminders: ReminderPlatformState? = null) {
    val data = state.data ?: return EmptyState(vitaText(L10nKey.NO_MEDICATIONS), vitaText(L10nKey.SELECT_PROFILE_FIRST))
    var name by remember { mutableStateOf("") }
    var strength by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    val today = currentInstant().toLocalDateTime(TimeZone.currentSystemDefault()).date
    var drafts by remember { mutableStateOf(listOf(ScheduleDraft(startDate = today))) }
    var editTarget by remember { mutableStateOf<MedicationRecord?>(null) }
    var stopTarget by remember { mutableStateOf<MedicationRecord?>(null) }
    var deleteTarget by remember { mutableStateOf<MedicationRecord?>(null) }
    val nextDose = state.dashboard?.nextDose
    ScreenColumn(vitaText(L10nKey.MEDICATIONS), vitaText(L10nKey.MEDICATIONS_SUBTITLE)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Schedule, vitaText(L10nKey.NEXT_MEDICATION_SCHEDULE), Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.size(14.dp))
                Column {
                    Text(if (nextDose == null) vitaText(L10nKey.NO_UPCOMING_SCHEDULE) else vitaText(L10nKey.NEXT_SCHEDULE, "${nextDose.medicationName} · ${formatUiDateTime(nextDose.scheduledFor)}"), style = MaterialTheme.typography.titleLarge)
                    Text(vitaText(L10nKey.RECORDED_REMINDERS_NOTE), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
        reminders?.let { ReminderStatus(it) }
        DoseHistoryCard(data)
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
                today,
                onSave = { n, st, ins, schedules -> editTarget = null; scope.launch { controller.updateMedication(medication.medicationId, n, st, ins, schedules) } },
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
        ScheduleListEditor(drafts, today) { drafts = it }
        VitaButton(vitaText(L10nKey.SAVE_MEDICATION), Icons.Rounded.Medication, {
            scope.launch {
                controller.addMedication(name, strength, instructions, drafts)
                if (controller.state.value.error == null) { name = ""; strength = ""; instructions = ""; drafts = listOf(ScheduleDraft(startDate = today)) }
            }
        }, enabled = name.isNotBlank() && drafts.all { it.errors().isEmpty() })
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
private fun EditMedicationDialog(medication: MedicationRecord, today: LocalDate, onSave: (String, String, String, List<ScheduleDraft>) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(medication.displayName) }
    var strength by remember { mutableStateOf(medication.strength.orEmpty()) }
    var instructions by remember { mutableStateOf(medication.instructions.orEmpty()) }
    var drafts by remember { mutableStateOf(medication.schedules.map(ScheduleDraft::from).ifEmpty { listOf(ScheduleDraft(startDate = today)) }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(vitaText(L10nKey.EDIT_MEDICATION)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.MEDICATION_NAME)) }, singleLine = true)
                OutlinedTextField(strength, { strength = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.STRENGTH_AS_PRESCRIBED)) }, singleLine = true)
                OutlinedTextField(instructions, { instructions = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.PRESCRIBER_INSTRUCTIONS)) })
                ScheduleListEditor(drafts, today) { drafts = it }
            }
        },
        confirmButton = { TextButton({ onSave(name, strength, instructions, drafts) }, enabled = name.isNotBlank() && drafts.all { it.errors().isEmpty() }) { Text(vitaText(L10nKey.SAVE)) } },
        dismissButton = { TextButton(onDismiss) { Text(vitaText(L10nKey.CANCEL)) } },
    )
}

@Composable
internal fun recurrenceLabel(recurrence: Recurrence, intervalDays: Int): String = vitaText(when (recurrence) {
    Recurrence.DAILY -> L10nKey.RECURRENCE_DAILY
    Recurrence.WEEKLY -> L10nKey.RECURRENCE_WEEKLY
    Recurrence.EVERY_N_DAYS -> L10nKey.RECURRENCE_EVERY_N_DAYS
}, intervalDays)

@Composable
fun SettingsScreen(state: AppUiState, controller: AppController, scope: CoroutineScope, backupFiles: BackupFileAccess? = null, reminders: ReminderPlatformState? = null) {
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
        var exportLanguage by remember { mutableStateOf<ContentLanguage?>(null) }
        Text(vitaText(L10nKey.EXPORT_LANGUAGE), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = exportLanguage == null, onClick = { exportLanguage = null }, label = { Text(vitaText(L10nKey.EXPORT_LANGUAGE_DEVICE)) })
            ContentLanguage.entries.forEach { language ->
                FilterChip(selected = exportLanguage == language, onClick = { exportLanguage = language }, label = { Text(language.nativeName) })
            }
        }
        Text(vitaText(L10nKey.EXPORT_LANGUAGE_EXPLANATION), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        var exportOptions by remember { mutableStateOf<ExportOptions?>(ExportOptions()) }
        ExportOptionsPanel(data) { exportOptions = it }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VitaButton(vitaText(L10nKey.PREPARE_CSV), Icons.Rounded.TableChart, { exportOptions?.let { scope.launch { controller.exportCsv(exportLanguage, it) } } }, Modifier.weight(1f), enabled = exportOptions != null)
            VitaButton(vitaText(L10nKey.PREPARE_PDF), Icons.Rounded.PictureAsPdf, { exportOptions?.let { scope.launch { controller.exportPdf(exportLanguage, it) } } }, Modifier.weight(1f), enabled = exportOptions != null)
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
        reminders?.let {
            SectionHeading(vitaText(L10nKey.SECTION_REMINDERS))
            ReminderPrivacySetting(it)
        }
        BackupSettingsSection(state, controller, backupFiles)
        DeviceHealthSettings(state, data, controller, scope)
        SectionHeading(vitaText(L10nKey.SECTION_STORAGE_SECURITY))
        AppLockSettings()
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
