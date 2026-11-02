package app.vezuna.ui.today

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vezuna.domain.ProfileAge
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.vitaText
import app.vezuna.platform.currentInstant
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.TertiaryButton
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_calendar

@OptIn(ExperimentalMaterial3Api::class)
private object NoFutureBirthDates : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= ProfileAge.today(currentInstant(), TimeZone.currentSystemDefault()).toPickerMillis()
}

/** The picker yields UTC midnight, which read as UTC is exactly the chosen calendar day. */
internal fun pickedDateToLocalDate(utcMidnightMillis: Long): LocalDate =
    Instant.fromEpochMilliseconds(utcMidnightMillis).toLocalDateTime(TimeZone.UTC).date

internal fun LocalDate.toPickerMillis(): Long = atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

/**
 * Optional birth date: a persistent label, a field-like button that opens the system date picker (no future
 * days), the resulting age, and a clear action. [error] stays next to the field and is announced.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BirthDateField(date: LocalDate?, onDate: (LocalDate?) -> Unit, error: String? = null) {
    var open by remember { mutableStateOf(false) }
    val label = vitaText(L10nKey.BIRTH_DATE_OPTIONAL)
    // Calendar days display as noon UTC, like every other date-only value in the UI.
    val shown = date?.let { formatUiDate(it.atTime(12, 0).toInstant(TimeZone.UTC)) }
    val description = "$label: ${shown ?: vitaText(L10nKey.BIRTH_DATE_NOT_SET)}"
    val age = date?.let { ProfileAge.years(it, ProfileAge.today(currentInstant(), TimeZone.currentSystemDefault())) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceXs)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Surface(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = VezunaTokens.RowHeight).clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                if (error != null) error(error)
            },
            shape = RoundedCornerShape(VezunaTokens.ControlRadius),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline),
        ) {
            Row(Modifier.padding(horizontal = VezunaTokens.SpaceMd, vertical = VezunaTokens.SpaceSmd), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    shown ?: vitaText(L10nKey.BIRTH_DATE_SELECT),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (shown != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(painterResource(Res.drawable.ic_calendar), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            }
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (age != null) Text(vitaText(L10nKey.PROFILE_AGE, age), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (date != null) TertiaryButton(vitaText(L10nKey.BIRTH_DATE_CLEAR), { onDate(null) })
    }
    if (open) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = date?.toPickerMillis(), selectableDates = NoFutureBirthDates)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    onClick = { picker.selectedDateMillis?.let { onDate(pickedDateToLocalDate(it)) }; open = false },
                    enabled = picker.selectedDateMillis != null,
                ) { Text(vitaText(L10nKey.CHOOSE_DATE)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(vitaText(L10nKey.CANCEL)) } },
        ) { DatePicker(picker) }
    }
}
