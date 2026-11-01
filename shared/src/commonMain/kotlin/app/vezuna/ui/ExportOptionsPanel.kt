package app.vezuna.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.ProfileData
import app.vezuna.domain.UnitNormalizer
import app.vezuna.export.ExportOptions
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText

/** Edits doctor-export options; [onOptionsChange] receives null while a date field holds an invalid or reversed range. */
@Composable
fun ExportOptionsPanel(data: ProfileData, onOptionsChange: (ExportOptions?) -> Unit) {
    val profileId = data.profile.profileId
    var fromText by remember(profileId) { mutableStateOf("") }
    var toText by remember(profileId) { mutableStateOf("") }
    var selectedTests by remember(profileId) { mutableStateOf(emptySet<String>()) }
    var activeOnly by remember(profileId) { mutableStateOf(false) }
    var medicationsCsv by remember(profileId) { mutableStateOf(false) }
    val tests = remember(profileId, data.observations) {
        data.observations.filter { it.confirmation == ConfirmationStatus.CONFIRMED }
            .groupBy { UnitNormalizer.canonicalTestCode(it.testCode) }
            .map { (code, rows) -> code to rows.first().displayName }
            .sortedBy { it.first }
    }
    val from = ExportOptions.parseDate(fromText)
    val to = ExportOptions.parseDate(toText)
    val fromInvalid = fromText.isNotBlank() && from == null
    val toInvalid = toText.isNotBlank() && to == null
    val reversed = from != null && to != null && from > to
    val options = if (fromInvalid || toInvalid || reversed) null else ExportOptions(from, to, selectedTests.filterTo(mutableSetOf()) { code -> tests.any { it.first == code } }, activeOnly, medicationsCsv)
    SideEffect { onOptionsChange(options) }
    val hasConfirmed = data.observations.any { it.confirmation == ConfirmationStatus.CONFIRMED }
    val noMatches = options != null && hasConfirmed && options.apply(data).observations.none { it.confirmation == ConfirmationStatus.CONFIRMED }
    val dateError = when {
        fromInvalid || toInvalid -> vitaText(L10nKey.EXPORT_DATE_INVALID)
        reversed -> vitaText(L10nKey.EXPORT_DATE_RANGE_REVERSED)
        else -> null
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(vitaText(L10nKey.EXPORT_OPTIONS), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                fromText, { fromText = it }, Modifier.weight(1f), singleLine = true, isError = fromInvalid || reversed,
                label = { Text(vitaText(L10nKey.EXPORT_FROM_DATE)) }, supportingText = { Text(if (fromInvalid || reversed) dateError.orEmpty() else vitaText(L10nKey.EXPORT_DATE_HINT)) },
            )
            OutlinedTextField(
                toText, { toText = it }, Modifier.weight(1f), singleLine = true, isError = toInvalid || reversed,
                label = { Text(vitaText(L10nKey.EXPORT_TO_DATE)) }, supportingText = { Text(if (toInvalid || reversed) dateError.orEmpty() else vitaText(L10nKey.EXPORT_DATE_HINT)) },
            )
        }
        if (tests.isNotEmpty()) {
            Text(vitaText(L10nKey.EXPORT_TESTS), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tests.forEach { test ->
                    val code = test.first
                    FilterChip(
                        selected = code in selectedTests,
                        onClick = { selectedTests = if (code in selectedTests) selectedTests - code else selectedTests + code },
                        label = { Text(test.second) },
                    )
                }
            }
        }
        if (noMatches) Text(vitaText(L10nKey.EXPORT_NO_MATCHING_RESULTS), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        ToggleRow(vitaText(L10nKey.EXPORT_ACTIVE_MEDICATIONS_ONLY), activeOnly) { activeOnly = it }
        ToggleRow(vitaText(L10nKey.EXPORT_INCLUDE_MEDICATIONS_CSV), medicationsCsv) { medicationsCsv = it }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange)
    }
}
