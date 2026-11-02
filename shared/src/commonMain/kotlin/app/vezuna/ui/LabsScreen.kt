package app.vezuna.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabResultFilter
import app.vezuna.domain.LabResultList
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.vitaPlural
import app.vezuna.localization.vitaText
import kotlinx.datetime.Instant

@Composable
fun LabsScreen(state: AppUiState, controller: AppController) {
    val data = state.data ?: return EmptyState(vitaText(L10nKey.NO_LABS), vitaText(L10nKey.SELECT_PROFILE_FIRST))
    val confirmed = data.observations.filter { it.confirmation == ConfirmationStatus.CONFIRMED }.sortedByDescending { it.collectedAt }
    var filter by remember(data.profile.profileId) { mutableStateOf(LabResultFilter()) }
    var grouped by remember(data.profile.profileId) { mutableStateOf(false) }
    val visible = LabResultList.filter(confirmed, filter)
    ScreenColumn(vitaText(L10nKey.LAB_RECORDS), vitaText(L10nKey.LAB_RECORDS_SUBTITLE)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VitaButton(vitaText(L10nKey.IMPORT_REVIEW), Icons.Rounded.UploadFile, { controller.navigate(AppScreen.ImportReview) }, Modifier.weight(1f))
            VitaButton(vitaText(L10nKey.TRENDS), Icons.Rounded.Insights, { controller.navigate(AppScreen.Trends) }, Modifier.weight(0.7f), outlined = true)
        }
        if (confirmed.isEmpty()) {
            EmptyState(
                vitaText(L10nKey.NO_CONFIRMED_RESULTS), vitaText(L10nKey.LABS_EMPTY_EXPLANATION), Icons.Rounded.Science,
                vitaText(L10nKey.IMPORT_REVIEW), { controller.navigate(AppScreen.ImportReview) },
            )
        } else {
            LabFilterBar(filter, { filter = it }, grouped, { grouped = it })
            Text(vitaPlural(L10nKey.LABS_RESULT_COUNT, visible.size), style = MaterialTheme.typography.labelLarge)
            if (visible.isEmpty()) {
                InlineNotice(vitaText(L10nKey.LABS_NO_MATCHES))
            } else if (grouped) {
                val manualName = vitaText(L10nKey.MANUAL_ENTRY_REPORT)
                LabResultList.groupByReport(visible, data.reports).forEach { group ->
                    SectionHeading(reportDisplayName(group.report, data, manualName), group.report?.let { formatUiDate(reportDate(it)) })
                    group.observations.forEach { observation ->
                        ResultRow(observation) { controller.navigate(AppScreen.ResultDetail(observation.observationId)) }
                    }
                }
            } else visible.forEach { observation ->
                ResultRow(observation) { controller.navigate(AppScreen.ResultDetail(observation.observationId)) }
            }
        }
        ReportList(data, controller)
    }
}

@Composable
private fun LabFilterBar(filter: LabResultFilter, onFilter: (LabResultFilter) -> Unit, grouped: Boolean, onGrouped: (Boolean) -> Unit) {
    OutlinedTextField(
        filter.text, { onFilter(filter.copy(text = it)) }, Modifier.fillMaxWidth(),
        label = { Text(vitaText(L10nKey.LABS_SEARCH_HINT)) }, singleLine = true,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(filter.outOfRangeOnly, { onFilter(filter.copy(outOfRangeOnly = !filter.outOfRangeOnly)) }, { Text(vitaText(L10nKey.LABS_FILTER_OUT_OF_RANGE)) })
        FilterChip(grouped, { onGrouped(!grouped) }, { Text(vitaText(L10nKey.LABS_GROUP_BY_REPORT)) })
    }
    val anyDate = vitaText(L10nKey.LABS_FILTER_ANY_DATE)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterDateButton(vitaText(L10nKey.LABS_FILTER_FROM, filter.from?.let { formatUiDate(it) } ?: anyDate), filter.from) { onFilter(filter.copy(from = it)) }
        FilterDateButton(vitaText(L10nKey.LABS_FILTER_TO, filter.to?.let { formatUiDate(it) } ?: anyDate), filter.to) { onFilter(filter.copy(to = it)) }
        if (filter.isActive) TextButton({ onFilter(LabResultFilter()) }, Modifier.defaultMinSize(minHeight = 48.dp)) { Text(vitaText(L10nKey.LABS_CLEAR_FILTERS)) }
    }
}

/** A date button:  the picker yields UTC midnight, stored as noon UTC like every calendar day in the app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilterDateButton(label: String, date: Instant?, onDate: (Instant?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton({ open = true }, Modifier.defaultMinSize(minHeight = 48.dp)) { Text(label) }
    if (open) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = date?.toEpochMilliseconds())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton({ picker.selectedDateMillis?.let { onDate(pickedDateToInstant(it)) }; open = false }, enabled = picker.selectedDateMillis != null) {
                    Text(vitaText(L10nKey.CHOOSE_DATE))
                }
            },
            dismissButton = { TextButton({ onDate(null); open = false }) { Text(vitaText(L10nKey.CANCEL)) } },
        ) { DatePicker(picker) }
    }
}
