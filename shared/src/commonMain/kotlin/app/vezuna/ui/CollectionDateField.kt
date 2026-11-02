package app.vezuna.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.vitaText
import app.vezuna.platform.currentInstant
import app.vezuna.ui.components.TertiaryButton
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_calendar
import vezuna.shared.generated.resources.ic_error

private const val HALF_DAY_MILLIS = 12L * 60 * 60 * 1000

/** A collection date cannot be after today's local calendar day (the picker works in UTC calendar days). */
@OptIn(ExperimentalMaterial3Api::class)
private object NoFutureDates : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
        utcTimeMillis <= currentInstant().toLocalDateTime(TimeZone.currentSystemDefault()).date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
}

/**
 * The picker yields UTC midnight; noon UTC keeps the same calendar day in every
 * local time zone from UTC-12 to UTC+11 and in the UTC-dated PDF exports.
 */
internal fun pickedDateToInstant(utcMidnightMillis: Long): Instant = Instant.fromEpochMilliseconds(utcMidnightMillis + HALF_DAY_MILLIS)

/**
 * Collection date as a labeled field (image 043). Opens the date picker (calendar or typed entry), which never
 * offers a future day. [applyToAllLabel] adds the "apply to every value in this report" switch to the picker;
 * [onDate] then receives whether it was on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectionDateField(
    date: Instant?,
    onDate: (Instant, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    applyToAllLabel: String? = null,
    focusRequester: FocusRequester? = null,
    label: String = vitaText(L10nKey.COLLECTION_DATE),
    help: String = vitaText(L10nKey.ENTRY_COLLECTION_DATE_HELP),
) {
    var open by remember { mutableStateOf(false) }
    val shown = date?.let { formatUiDate(it) }
    val notSet = vitaText(L10nKey.COLLECTION_DATE_NOT_SET)
    val chooseLabel = vitaText(L10nKey.CHOOSE_DATE)
    Box(
        modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = label
            stateDescription = shown ?: notSet
            role = Role.Button
            onClick(chooseLabel) { open = true; true }
            if (error != null) error(error)
        },
    ) {
        OutlinedTextField(
            value = shown.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text(vitaText(L10nKey.CHOOSE_DATE)) },
            isError = error != null,
            singleLine = true,
            shape = RoundedCornerShape(VezunaTokens.ControlRadius),
            trailingIcon = {
                Icon(
                    painterResource(if (error != null) Res.drawable.ic_error else Res.drawable.ic_calendar),
                    contentDescription = null,
                    tint = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            supportingText = error?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        // The read-only field is only the visual; the whole area opens the picker.
        Box(Modifier.matchParentSize().then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier).clickable(role = Role.Button) { open = true })
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date?.toEpochMilliseconds(), selectableDates = NoFutureDates)
        var applyToAll by remember { mutableStateOf(false) }
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TertiaryButton(
                    chooseLabel,
                    {
                        state.selectedDateMillis?.let { onDate(pickedDateToInstant(it), applyToAll) }
                        open = false
                    },
                    enabled = state.selectedDateMillis != null,
                )
            },
            dismissButton = { TertiaryButton(vitaText(L10nKey.CANCEL), { open = false }) },
        ) {
            DatePicker(state, title = { Text(help, Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) })
            if (applyToAllLabel != null) {
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(applyToAll, role = Role.Switch) { applyToAll = it }
                        .heightIn(min = VezunaTokens.TouchTarget)
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd),
                ) {
                    Text(applyToAllLabel, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = applyToAll, onCheckedChange = null)
                }
            }
        }
    }
}
