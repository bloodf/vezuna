package app.vezuna.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.vezuna.domain.Recurrence
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.vitaText
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

private val WEEKDAY_LABELS = listOf(
    DayOfWeek.MONDAY to L10nKey.WEEKDAY_MON, DayOfWeek.TUESDAY to L10nKey.WEEKDAY_TUE, DayOfWeek.WEDNESDAY to L10nKey.WEEKDAY_WED,
    DayOfWeek.THURSDAY to L10nKey.WEEKDAY_THU, DayOfWeek.FRIDAY to L10nKey.WEEKDAY_FRI, DayOfWeek.SATURDAY to L10nKey.WEEKDAY_SAT,
    DayOfWeek.SUNDAY to L10nKey.WEEKDAY_SUN,
)

/** One or more schedules, each with recurrence, several times, and start/end dates, validated inline. */
@Composable
internal fun ScheduleListEditor(drafts: List<ScheduleDraft>, today: LocalDate, onChange: (List<ScheduleDraft>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        drafts.forEachIndexed { index, draft ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (drafts.size > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(vitaText(L10nKey.SCHEDULE_NUMBER, index + 1), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            IconButton({ onChange(drafts.filterIndexed { i, _ -> i != index }) }, Modifier.size(48.dp)) {
                                Icon(Icons.Rounded.Delete, vitaText(L10nKey.REMOVE_SCHEDULE) + " — " + vitaText(L10nKey.SCHEDULE_NUMBER, index + 1))
                            }
                        }
                    }
                    ScheduleEditor(draft) { updated -> onChange(drafts.mapIndexed { i, old -> if (i == index) updated else old }) }
                }
            }
        }
        TextButton({ onChange(drafts + ScheduleDraft(startDate = today)) }, Modifier.defaultMinSize(minHeight = 48.dp)) {
            Icon(Icons.Rounded.Add, null)
            Text(vitaText(L10nKey.ADD_SCHEDULE), Modifier.padding(start = 8.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleEditor(draft: ScheduleDraft, onChange: (ScheduleDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(vitaText(L10nKey.SCHEDULE_REPEAT), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Recurrence.entries.forEach { recurrence ->
                FilterChip(
                    selected = draft.recurrence == recurrence,
                    onClick = { onChange(draft.copy(recurrence = recurrence)) },
                    label = { Text(recurrenceLabel(recurrence, draft.intervalDays.toIntOrNull()?.coerceAtLeast(1) ?: 2)) },
                )
            }
        }
        if (draft.recurrence == Recurrence.WEEKLY) {
            Text(vitaText(L10nKey.SCHEDULE_WEEKDAYS), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WEEKDAY_LABELS.forEach { (day, label) ->
                    FilterChip(
                        selected = day in draft.daysOfWeek,
                        onClick = { onChange(draft.copy(daysOfWeek = if (day in draft.daysOfWeek) draft.daysOfWeek - day else draft.daysOfWeek + day)) },
                        label = { Text(vitaText(label)) },
                    )
                }
            }
        }
        if (draft.recurrence == Recurrence.EVERY_N_DAYS) {
            OutlinedTextField(
                draft.intervalDays,
                { onChange(draft.copy(intervalDays = it.filter(Char::isDigit).take(3))) },
                Modifier.fillMaxWidth(),
                label = { Text(vitaText(L10nKey.SCHEDULE_INTERVAL_DAYS)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        }
        TimesEditor(draft.times) { onChange(draft.copy(times = it)) }
        DateRow(L10nKey.SCHEDULE_START_DATE, draft.startDate, onClear = null) { onChange(draft.copy(startDate = it)) }
        DateRow(L10nKey.SCHEDULE_END_DATE, draft.endDate, onClear = { onChange(draft.copy(endDate = null)) }) { onChange(draft.copy(endDate = it)) }
        draft.errors().forEach { Text(vitaText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun TimesEditor(times: List<String>, onChange: (List<String>) -> Unit) {
    var picking by remember { mutableStateOf<Int?>(null) }
    Text(vitaText(L10nKey.REMINDER_TIMES), style = MaterialTheme.typography.labelLarge)
    times.forEachIndexed { index, time ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ picking = index }, Modifier.defaultMinSize(minHeight = 48.dp).weight(1f)) {
                Icon(Icons.Rounded.Schedule, null, Modifier.size(18.dp))
                Text(time.ifBlank { vitaText(L10nKey.CHOOSE_TIME) }, Modifier.padding(start = 8.dp))
            }
            if (times.size > 1) {
                IconButton({ onChange(times.filterIndexed { i, _ -> i != index }) }, Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Close, vitaText(L10nKey.REMOVE_TIME, time))
                }
            }
        }
    }
    TextButton({ picking = times.size }, Modifier.defaultMinSize(minHeight = 48.dp)) {
        Icon(Icons.Rounded.Add, null)
        Text(vitaText(L10nKey.ADD_TIME), Modifier.padding(start = 8.dp))
    }
    picking?.let { index ->
        TimePickerDialog(times.getOrNull(index), onDismiss = { picking = null }) { chosen ->
            picking = null
            onChange(if (index < times.size) times.mapIndexed { i, old -> if (i == index) chosen else old } else times + chosen)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(initial: String?, onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    val parts = initial?.split(":")?.mapNotNull { it.toIntOrNull() }
    val state = rememberTimePickerState(parts?.getOrNull(0) ?: 8, parts?.getOrNull(1) ?: 0, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(vitaText(L10nKey.CHOOSE_TIME)) },
        text = { TimePicker(state) },
        confirmButton = { TextButton({ onPicked(formatTime(state.hour, state.minute)) }) { Text(vitaText(L10nKey.SAVE)) } },
        dismissButton = { TextButton(onDismiss) { Text(vitaText(L10nKey.CANCEL)) } },
    )
}

internal fun formatTime(hour: Int, minute: Int): String = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRow(label: L10nKey, date: LocalDate?, onClear: (() -> Unit)?, onPicked: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val title = vitaText(label)
    val value = date?.let { formatUiDate(it.atStartOfDayIn(TimeZone.UTC).plusHalfDay()) } ?: vitaText(L10nKey.SCHEDULE_NO_END_DATE)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton({ open = true }, Modifier.defaultMinSize(minHeight = 48.dp).weight(1f).semantics { contentDescription = "$title: $value" }) {
            Text("$title: $value")
        }
        if (onClear != null && date != null) {
            IconButton(onClear, Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, vitaText(L10nKey.SCHEDULE_NO_END_DATE)) }
        }
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton({
                    open = false
                    state.selectedDateMillis?.let { onPicked(Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date) }
                }) { Text(vitaText(L10nKey.SAVE)) }
            },
            dismissButton = { TextButton({ open = false }) { Text(vitaText(L10nKey.CANCEL)) } },
        ) { DatePicker(state) }
    }
}

/** Formatting a UTC-midnight date at noon keeps the same calendar day in every local zone. */
private fun Instant.plusHalfDay(): Instant = Instant.fromEpochMilliseconds(toEpochMilliseconds() + 12L * 60 * 60 * 1000)
