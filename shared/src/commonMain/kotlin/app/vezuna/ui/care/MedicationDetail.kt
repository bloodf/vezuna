package app.vezuna.ui.care

import app.vezuna.ui.components.clearOfArt
import app.vezuna.ui.components.returnFocusAfter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.Medication
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.vitaText
import app.vezuna.ui.Category
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.CategoryPanel
import app.vezuna.ui.components.ConfirmDialog
import app.vezuna.ui.components.GroupedRows
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.components.SectionHeading
import app.vezuna.ui.nav.RouteContext
import app.vezuna.ui.recurrenceLabel
import app.vezuna.ui.AppScreen
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_calendar
import vezuna.shared.generated.resources.ic_edit
import vezuna.shared.generated.resources.ic_history
import vezuna.shared.generated.resources.ic_pause
import vezuna.shared.generated.resources.ic_play
import vezuna.shared.generated.resources.ill_capsule

private const val RECENT_DOSES = 5

/**
 * Medication detail (057, 061-063). Routine actions (edit, pause/resume) sit apart from the consequential ones
 * (stop, delete); stop and delete keep their confirmations and dose history semantics.
 */
@Composable
internal fun MedicationDetailBody(ctx: RouteContext, medication: Medication) {
    var confirmStop by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var actionFor by remember { mutableStateOf<CareDose?>(null) }
    val data = ctx.state.data ?: return
    val status = medication.status
    val name = medication.displayName
    val busy = ctx.state.loading
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = VezunaTokens.GutterNarrow),
        verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
    ) {
        Text(name, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        CategoryPanel(Category.CARE, illustration = Res.drawable.ill_capsule) {
            medication.strength?.let { Text(it, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.clearOfArt(VezunaTokens.ArtClearanceWide)) }
            medication.instructions?.let { Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clearOfArt(VezunaTokens.ArtClearanceWide)) }
            MedicationStatusTag(status)
            when (status) {
                MedicationStatus.PAUSED -> Text(vitaText(L10nKey.CARE_PAUSED_NOTE), style = MaterialTheme.typography.bodyLarge)
                MedicationStatus.STOPPED -> medication.schedules.mapNotNull { it.endDate }.maxOrNull()?.let { end ->
                    Text(vitaText(L10nKey.CARE_STOPPED_NOTE, formatUiDate(end.atStartOfDayIn(TimeZone.UTC).plusHalfDay())), style = MaterialTheme.typography.bodyLarge)
                }
                MedicationStatus.ACTIVE -> Unit
            }
        }
        GroupedRows {
            if (medication.schedules.isEmpty()) {
                ListRow(vitaText(L10nKey.SCHEDULE), supporting = vitaText(L10nKey.CARE_NO_SCHEDULE), leadingIcon = Res.drawable.ic_calendar, category = Category.CARE)
            }
            medication.schedules.forEach { schedule ->
                ListRow(vitaText(L10nKey.SCHEDULE), supporting = scheduleText(schedule), leadingIcon = Res.drawable.ic_calendar, category = Category.CARE)
            }
        }
        PrimaryButton(vitaText(L10nKey.EDIT_MEDICATION), { ctx.navigate(AppScreen.MedicationForm(medication.medicationId)) }, Modifier.fillMaxWidth(), enabled = !busy, icon = Res.drawable.ic_edit)
        val resume = status != MedicationStatus.ACTIVE
        SecondaryButton(
            vitaText(if (resume) L10nKey.CARE_RESUME_REMINDERS else L10nKey.CARE_PAUSE_REMINDERS),
            { ctx.scope.launch { ctx.controller.setMedicationActive(medication.medicationId, resume) } },
            Modifier.fillMaxWidth(),
            enabled = !busy,
            icon = if (resume) Res.drawable.ic_play else Res.drawable.ic_pause,
        )
        Spacer(Modifier.height(VezunaTokens.SpaceSm))
        if (status != MedicationStatus.STOPPED) {
            DangerOutlineButton(vitaText(L10nKey.STOP_MEDICATION), { confirmStop = true }, enabled = !busy, modifier = Modifier.returnFocusAfter(confirmStop))
        }
        DangerOutlineButton(vitaText(L10nKey.DELETE_MEDICATION), { confirmDelete = true }, enabled = !busy, modifier = Modifier.returnFocusAfter(confirmDelete))
        RecentDoses(ctx, CareDoses.recorded(data, medication.medicationId).take(RECENT_DOSES), medication) { actionFor = it }
        Spacer(Modifier.height(VezunaTokens.SpaceLg))
    }
    if (confirmStop) {
        ConfirmDialog(
            title = vitaText(L10nKey.CARE_STOP_TITLE, name),
            body = vitaText(L10nKey.CARE_STOP_BODY),
            confirmLabel = vitaText(L10nKey.STOP_MEDICATION),
            onConfirm = { confirmStop = false; ctx.scope.launch { ctx.controller.stopMedication(medication.medicationId) } },
            onDismiss = { confirmStop = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = vitaText(L10nKey.CARE_DELETE_TITLE, name),
            body = vitaText(L10nKey.CARE_DELETE_BODY),
            confirmLabel = vitaText(L10nKey.DELETE_MEDICATION),
            onConfirm = {
                confirmDelete = false
                ctx.runThenAnnounce(UserText.Key(L10nKey.CARE_MEDICATION_DELETED, listOf(name))) {
                    ctx.controller.deleteMedication(medication.medicationId)
                    if (ctx.controller.state.value.error == null) ctx.back()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
    actionFor?.let { DoseActionSheet(ctx, it) { actionFor = null } }
}

@Composable
private fun RecentDoses(ctx: RouteContext, doses: List<CareDose>, medication: Medication, onDose: (CareDose) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
        SectionHeading(vitaText(L10nKey.CARE_RECENT_DOSES))
        GroupedRows {
            if (doses.isEmpty()) ListRow(vitaText(L10nKey.CARE_NO_RECORDED_DOSES))
            doses.forEach { dose ->
                ListRow(
                    formatUiDate(dose.dose.scheduledFor) + " · " + dose.dose.scheduledFor.localTime(),
                    onClick = { onDose(dose) },
                    supporting = doseStateText(dose),
                )
            }
            ListRow(vitaText(L10nKey.CARE_VIEW_HISTORY), onClick = { ctx.navigate(AppScreen.DoseHistory(medication.medicationId)) }, leadingIcon = Res.drawable.ic_history, category = Category.CARE)
        }
    }
}

@Composable
internal fun scheduleText(schedule: DoseSchedule): String =
    "${schedule.timesLocal.joinToString(", ")} · ${recurrenceLabel(schedule.recurrence, schedule.intervalDays)}"

/** Consequential but not the main action: outlined in the danger color, never filled, never next to Pause. */
@Composable
private fun DangerOutlineButton(text: String, onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val danger = MaterialTheme.colorScheme.error
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = VezunaTokens.RowHeight),
        shape = RoundedCornerShape(VezunaTokens.ControlRadius),
        border = BorderStroke(1.dp, if (enabled) danger else MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = danger, disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant, containerColor = Color.Transparent),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Formatting a UTC-midnight date at noon keeps the same calendar day in every local zone. */
private fun kotlinx.datetime.Instant.plusHalfDay() = kotlinx.datetime.Instant.fromEpochSeconds(epochSeconds + 12 * 60 * 60)
