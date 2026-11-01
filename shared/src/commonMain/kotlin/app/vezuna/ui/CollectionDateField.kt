package app.vezuna.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.vitaText
import app.vezuna.platform.currentInstant
import kotlinx.datetime.Instant

private const val HALF_DAY_MILLIS = 12L * 60 * 60 * 1000

private const val MAX_UTC_OFFSET_MILLIS = 14L * 60 * 60 * 1000

/** A collection date cannot be in the future; the 14h allowance covers the UTC+14 calendar day. */
@OptIn(ExperimentalMaterial3Api::class)
private object NoFutureDates : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= currentInstant().toEpochMilliseconds() + MAX_UTC_OFFSET_MILLIS
}

/**
 * The picker yields UTC midnight; noon UTC keeps the same calendar day in every
 * local time zone from UTC-12 to UTC+11 and in the UTC-dated PDF exports.
 */
internal fun pickedDateToInstant(utcMidnightMillis: Long): Instant = Instant.fromEpochMilliseconds(utcMidnightMillis + HALF_DAY_MILLIS)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectionDateField(date: Instant?, onDate: (Instant) -> Unit, onApplyToAll: () -> Unit, showApplyToAll: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val label = "${vitaText(L10nKey.COLLECTION_DATE)}: ${date?.let { formatUiDate(it) } ?: vitaText(L10nKey.COLLECTION_DATE_NOT_SET)}"
        val action = vitaText(L10nKey.CHOOSE_DATE)
        OutlinedButton(onClick = { open = true }, Modifier.defaultMinSize(minHeight = 48.dp).semantics { contentDescription = "$label. $action" }) {
            Text(label)
        }
        if (date != null && showApplyToAll) {
            TextButton(onClick = onApplyToAll, Modifier.defaultMinSize(minHeight = 48.dp)) { Text(vitaText(L10nKey.APPLY_DATE_TO_ALL)) }
        }
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date?.toEpochMilliseconds(), selectableDates = NoFutureDates)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { onDate(pickedDateToInstant(it)) }
                        open = false
                    },
                    enabled = state.selectedDateMillis != null,
                ) { Text(vitaText(L10nKey.CHOOSE_DATE)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(vitaText(L10nKey.CANCEL)) } },
        ) { DatePicker(state) }
    }
}
