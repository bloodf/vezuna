package app.vezuna.ui.records

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.vezuna.domain.LabComparator
import app.vezuna.domain.LabObservation
import app.vezuna.domain.LabReport
import app.vezuna.domain.ProfileData
import app.vezuna.domain.RangeAssessment
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.vitaText
import app.vezuna.ui.Category
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.LabeledField
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.components.StatusKind
import app.vezuna.ui.components.StatusTag
import app.vezuna.ui.components.TertiaryButton
import app.vezuna.ui.observationValueText
import app.vezuna.ui.pickedDateToInstant
import app.vezuna.ui.reportDisplayName
import app.vezuna.ui.tabular
import kotlinx.datetime.Instant
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_chevron_right
import vezuna.shared.generated.resources.ic_lab

/** Report-range wording: textual, never a diagnosis. Qualitative results are never assessed numerically. */
internal fun rangeKey(assessment: RangeAssessment): L10nKey = when (assessment) {
    RangeAssessment.BELOW -> L10nKey.RECORDS_RANGE_BELOW
    RangeAssessment.WITHIN -> L10nKey.RECORDS_RANGE_WITHIN
    RangeAssessment.ABOVE -> L10nKey.RECORDS_RANGE_ABOVE
    RangeAssessment.UNKNOWN -> L10nKey.RANGE_UNKNOWN
}

internal fun rangeKind(assessment: RangeAssessment): StatusKind = when (assessment) {
    RangeAssessment.WITHIN -> StatusKind.CONFIRMED
    RangeAssessment.BELOW, RangeAssessment.ABOVE -> StatusKind.WARNING
    RangeAssessment.UNKNOWN -> StatusKind.INFO
}

/**
 * One confirmed result: name, original value and unit in one text node, collection day and report. Out-of-range
 * results also carry the range as words, so the meaning never depends on color.
 */
@Composable
internal fun ResultListRow(observation: LabObservation, data: ProfileData, onClick: () -> Unit) {
    val assessment = LabComparator.assess(observation)
    val report = data.reports.firstOrNull { it.reportId == observation.reportId }
    val source = report?.let { reportDisplayName(it, data, vitaText(app.vezuna.localization.L10nKey.MANUAL_ENTRY_REPORT)) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = VezunaTokens.RowHeight)
            .padding(horizontal = VezunaTokens.SpaceMd, vertical = VezunaTokens.SpaceSmd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(VezunaTokens.ControlRadius)).background(Category.RECORDS.fill), contentAlignment = Alignment.Center) {
            Icon(painterResource(Res.drawable.ic_lab), contentDescription = null, tint = Category.RECORDS.onFill, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(observation.displayName, style = MaterialTheme.typography.titleMedium)
            Text(observationValueText(observation), style = MaterialTheme.typography.headlineSmall.tabular())
            Text(
                listOfNotNull(formatUiDate(observation.collectedAt), source).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (assessment == RangeAssessment.ABOVE || assessment == RangeAssessment.BELOW) {
                StatusTag(vitaText(rangeKey(assessment)), rangeKind(assessment), Modifier.padding(top = VezunaTokens.SpaceXs))
            }
        }
        Icon(painterResource(Res.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

/** A date button: the picker yields UTC midnight, stored as noon UTC like every calendar day in the app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilterDateButton(label: String, date: Instant?, onDate: (Instant?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SecondaryButton(label, { open = true })
    if (open) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = date?.toEpochMilliseconds())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TertiaryButton(vitaText(L10nKey.CHOOSE_DATE), { picker.selectedDateMillis?.let { onDate(pickedDateToInstant(it)) }; open = false }, enabled = picker.selectedDateMillis != null)
            },
            dismissButton = { TertiaryButton(vitaText(L10nKey.CLEAR_DATE), { onDate(null); open = false }) },
        ) { DatePicker(picker) }
    }
}

/** Laboratory name and order date as the reviewer reads them on the paper report; both optional. */
@Composable
internal fun ReportDetailsDialog(report: LabReport, onSave: (String, Instant?) -> Unit, onDismiss: () -> Unit) {
    var name by remember(report.reportId) { mutableStateOf(report.laboratoryName.orEmpty()) }
    var date by remember(report.reportId) { mutableStateOf(report.orderedAt) }
    val notSet = vitaText(L10nKey.REPORT_ORDER_DATE_NOT_SET)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(vitaText(L10nKey.EDIT_REPORT_DETAILS)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
                LabeledField(vitaText(L10nKey.REPORT_LABORATORY_NAME), name, { name = it })
                FilterDateButton(vitaText(L10nKey.REPORT_ORDER_DATE, date?.let { formatUiDate(it) } ?: notSet), date) { date = it }
            }
        },
        confirmButton = { PrimaryButton(vitaText(L10nKey.SAVE), { onSave(name, date) }) },
        dismissButton = { TertiaryButton(vitaText(L10nKey.CANCEL), onDismiss) },
    )
}
