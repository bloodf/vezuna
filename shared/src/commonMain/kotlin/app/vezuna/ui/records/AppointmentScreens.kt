package app.vezuna.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import app.vezuna.domain.Appointment
import app.vezuna.domain.pastAppointments
import app.vezuna.domain.upcomingAppointments
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDateTime
import app.vezuna.localization.vitaPlural
import app.vezuna.localization.vitaText
import app.vezuna.platform.currentInstant
import app.vezuna.ui.AppScreen
import app.vezuna.ui.Category
import app.vezuna.ui.CollectionDateField
import app.vezuna.ui.TimePickerDialog
import app.vezuna.ui.NotificationAccess
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.ConfirmDialog
import app.vezuna.ui.components.DestructiveButton
import app.vezuna.ui.components.EmptyState
import app.vezuna.ui.components.FocusedTaskScaffold
import app.vezuna.ui.components.GroupedRows
import app.vezuna.ui.components.LabeledField
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.TertiaryButton
import app.vezuna.ui.entry.FixFieldsNotice
import app.vezuna.ui.entry.FormColumn
import app.vezuna.ui.entry.guardedBack
import app.vezuna.ui.nav.NativeAction
import app.vezuna.ui.nav.RouteContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_calendar
import vezuna.shared.generated.resources.ic_plus
import vezuna.shared.generated.resources.ill_file_error
import vezuna.shared.generated.resources.ill_record_empty

@Composable
internal fun AppointmentListScreen(ctx: RouteContext) {
    FocusedTaskScaffold(title = vitaText(L10nKey.APPT_TITLE), onBack = ctx::back) {
        val data = ctx.state.data ?: return@FocusedTaskScaffold
        val now = remember(data.appointments) { currentInstant() }
        val upcoming = data.upcomingAppointments(now)
        val past = data.pastAppointments(now)
        val add = { ctx.navigate(AppScreen.AppointmentForm()) }
        FormColumn {
            Text(vitaText(L10nKey.APPT_INTRO), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (data.appointments.isEmpty()) {
                EmptyState(Res.drawable.ill_record_empty, vitaText(L10nKey.APPT_EMPTY_TITLE), vitaText(L10nKey.APPT_EMPTY_BODY), actionLabel = vitaText(L10nKey.APPT_ADD), onAction = add)
            } else {
                val access by (ctx.reminders?.notificationAccess ?: remember { MutableStateFlow(NotificationAccess.UNKNOWN) }).collectAsState()
                if (access == NotificationAccess.DENIED && upcoming.any { it.reminderMinutesBefore != null }) {
                    Text(vitaText(L10nKey.APPT_NOTIFICATIONS_OFF_BODY), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    ctx.reminders?.openNotificationSettings?.let { TertiaryButton(vitaText(L10nKey.CARE_OPEN_DEVICE_SETTINGS), it) }
                }
                if (upcoming.isNotEmpty()) AppointmentRows(ctx, vitaText(L10nKey.APPT_UPCOMING), upcoming)
                if (past.isNotEmpty()) AppointmentRows(ctx, vitaText(L10nKey.APPT_PAST), past)
                PrimaryButton(vitaText(L10nKey.APPT_ADD), add, Modifier.fillMaxWidth(), icon = Res.drawable.ic_plus)
            }
        }
    }
}

@Composable
private fun AppointmentRows(ctx: RouteContext, heading: String, items: List<Appointment>) {
    GroupedRows(title = heading) {
        items.forEach { appointment ->
            ListRow(
                appointment.clinician,
                onClick = { ctx.navigate(AppScreen.AppointmentForm(appointment.appointmentId)) },
                supporting = summary(appointment),
                leadingIcon = Res.drawable.ic_calendar,
                category = Category.RECORDS,
            )
        }
    }
}

@Composable
private fun summary(appointment: Appointment): String = listOfNotNull(
    formatUiDateTime(appointment.at),
    appointment.reminderMinutesBefore?.let { vitaText(L10nKey.APPT_REMINDER_SUMMARY, reminderLabel(it)) },
    appointment.notes,
).joinToString(" · ")

@Composable
private fun reminderLabel(minutes: Int): String = when (minutes) {
    15 -> vitaText(L10nKey.APPT_REMINDER_15M)
    60 -> vitaText(L10nKey.APPT_REMINDER_1H)
    24 * 60 -> vitaText(L10nKey.APPT_REMINDER_1D)
    else -> vitaPlural(L10nKey.APPT_REMINDER_MINUTES, minutes)
}

/** Add ([appointmentId] null) or edit, with delete on an existing visit. */
@Composable
internal fun AppointmentFormScreen(ctx: RouteContext, appointmentId: String?) {
    val title = vitaText(if (appointmentId == null) L10nKey.APPT_ADD else L10nKey.APPT_EDIT)
    val data = ctx.state.data ?: return
    val existing = appointmentId?.let { id -> data.appointments.firstOrNull { it.appointmentId == id } }
    if (appointmentId != null && existing == null) {
        FocusedTaskScaffold(title = title, onBack = ctx::back) {
            EmptyState(Res.drawable.ill_file_error, vitaText(L10nKey.APPT_NOT_FOUND), vitaText(L10nKey.APPT_INTRO), Modifier.padding(VezunaTokens.Gutter))
        }
        return
    }
    val zone = remember { TimeZone.currentSystemDefault() }
    val key = data.profile.profileId to appointmentId
    val initial = remember(key) { existing?.toDraft(zone) ?: AppointmentDraft() }
    var draft by remember(key) { mutableStateOf(AppointmentFormSession.restore(key) ?: initial) }
    var showErrors by remember(key) { mutableStateOf(false) }
    var busy by remember(key) { mutableStateOf(false) }
    var confirmDelete by remember(key) { mutableStateOf(false) }
    var pickingTime by remember(key) { mutableStateOf(false) }
    SideEffect { AppointmentFormSession.keep(key, draft.takeIf { it != initial }) }
    val leave = { AppointmentFormSession.keep(key, null); ctx.back() }
    val back = guardedBack(draft != initial, leave)
    val now = currentInstant()
    FocusedTaskScaffold(title = title, onBack = back) {
        FormColumn {
            Text(vitaText(L10nKey.ENTRY_FOR_PROFILE, data.profile.displayName), style = MaterialTheme.typography.titleMedium)
            LabeledField(
                vitaText(L10nKey.APPT_FIELD_CLINICIAN), draft.clinician, { draft = draft.copy(clinician = it) },
                error = vitaText(L10nKey.APPT_CLINICIAN_REQUIRED).takeIf { showErrors && draft.clinicianMissing },
            )
            CollectionDateField(
                draft.date, { date, _ -> draft = draft.copy(date = date) },
                error = vitaText(L10nKey.APPT_DATE_REQUIRED).takeIf { showErrors && draft.dateMissing },
                label = vitaText(L10nKey.APPT_FIELD_DATE), help = vitaText(L10nKey.APPT_DATE_HELP), allowFutureDates = true,
            )
            val timeText = vitaText(L10nKey.APPT_TIME_LABEL, draft.time)
            OutlinedButton({ pickingTime = true }, Modifier.fillMaxWidth().heightIn(min = VezunaTokens.TouchTarget).semantics { contentDescription = timeText }) { Text(timeText) }
            LabeledField(vitaText(L10nKey.APPT_FIELD_NOTES), draft.notes, { draft = draft.copy(notes = it) }, singleLine = false)
            ReminderPicker(ctx, draft, error = showErrors && draft.reminderPassed(now, zone, existing)) { draft = draft.copy(reminderMinutesBefore = it) }
            FixFieldsNotice(showErrors && !draft.isValid(now, zone, existing))
            PrimaryButton(
                vitaText(L10nKey.SAVE),
                {
                    if (!draft.isValid(now, zone, existing)) {
                        showErrors = true
                    } else {
                        busy = true
                        val saving = draft
                        ctx.scope.launch {
                            ctx.controller.saveAppointment(appointmentId, saving)
                            busy = false
                            if (ctx.controller.state.value.error == null) leave()
                        }
                    }
                },
                Modifier.fillMaxWidth(),
                loading = busy,
            )
            existing?.let { visit -> DestructiveButton(vitaText(L10nKey.APPT_DELETE, visit.clinician), { confirmDelete = true }, Modifier.fillMaxWidth()) }
        }
    }
    if (pickingTime) {
        TimePickerDialog(draft.time, onDismiss = { pickingTime = false }) { chosen ->
            pickingTime = false
            draft = draft.copy(time = chosen)
        }
    }
    if (confirmDelete && existing != null) {
        ConfirmDialog(
            title = vitaText(L10nKey.APPT_DELETE, existing.clinician),
            body = vitaText(L10nKey.APPT_DELETE_BODY),
            confirmLabel = vitaText(L10nKey.DELETE),
            onConfirm = {
                confirmDelete = false
                ctx.scope.launch {
                    ctx.controller.deleteAppointment(existing.appointmentId)
                    if (ctx.controller.state.value.error == null) leave()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Off by default: a reminder exists only when the owner picks a lead time for this visit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReminderPicker(ctx: RouteContext, draft: AppointmentDraft, error: Boolean, onSelect: (Int?) -> Unit) {
    val reminders = ctx.reminders
    val access by (reminders?.notificationAccess ?: remember { MutableStateFlow(NotificationAccess.UNKNOWN) }).collectAsState()
    val exactDenied by (reminders?.exactAlarmsDenied ?: remember { MutableStateFlow(false) }).collectAsState()
    val options = (listOf<Int?>(null) + Appointment.REMINDER_CHOICES + listOfNotNull(draft.reminderMinutesBefore?.takeIf { it !in Appointment.REMINDER_CHOICES })).distinct()
    Column(verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
        Text(vitaText(L10nKey.APPT_REMINDER), style = MaterialTheme.typography.titleMedium)
        Text(vitaText(L10nKey.APPT_REMINDER_NOTE), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
            options.forEach { option ->
                val isSelected = option == draft.reminderMinutesBefore
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        onSelect(option)
                        // The system dialog is the only way to allow notifications; ask when a reminder is first wanted, not only from Care.
                        if (option != null && access == NotificationAccess.NOT_REQUESTED && NativeAction.REMINDERS in ctx.nativeActions) ctx.nativeActions.perform(NativeAction.REMINDERS)
                    },
                    label = { Text(option?.let { reminderLabel(it) } ?: vitaText(L10nKey.APPT_REMINDER_OFF)) },
                    modifier = Modifier.heightIn(min = VezunaTokens.TouchTarget).semantics { role = Role.RadioButton; this.selected = isSelected },
                )
            }
        }
        if (error) Text(vitaText(L10nKey.APPT_REMINDER_PASSED), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        if (draft.reminderMinutesBefore != null) {
            if (access == NotificationAccess.DENIED) {
                Text(vitaText(L10nKey.APPT_NOTIFICATIONS_OFF_BODY), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                reminders?.openNotificationSettings?.let { TertiaryButton(vitaText(L10nKey.CARE_OPEN_DEVICE_SETTINGS), it) }
            }
            if (exactDenied) {
                Text(vitaText(L10nKey.EXACT_ALARMS_OFF_EXPLANATION), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                reminders?.openExactAlarmSettings?.let { TertiaryButton(vitaText(L10nKey.OPEN_ALARM_SETTINGS), it) }
            }
        }
    }
}
