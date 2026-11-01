package app.vezuna.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vezuna.domain.LabObservation
import app.vezuna.domain.ObservationRevision
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatLabValue
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.vitaText
import kotlinx.datetime.Instant

internal data class ResultEdit(
    val value: String,
    val unit: String,
    val lower: String,
    val upper: String,
    val rangeText: String,
    val date: Instant?,
)

/** Edit form for a confirmed result: value (or text), unit, reference range and collection date. */
@Composable
internal fun ResultEditDialog(observation: LabObservation, onSave: (ResultEdit) -> Unit, onDismiss: () -> Unit) {
    val id = observation.observationId
    val range = observation.referenceRange
    var value by remember(id) {
        mutableStateOf(observation.qualitativeValue ?: formatLabValue(observation.originalValue, observation.valueDecimals))
    }
    var unit by remember(id) { mutableStateOf(observation.originalUnit) }
    var lower by remember(id) { mutableStateOf(range.lower?.let { formatLabValue(it) }.orEmpty()) }
    var upper by remember(id) { mutableStateOf(range.upper?.let { formatLabValue(it) }.orEmpty()) }
    var rangeText by remember(id) { mutableStateOf(range.text.orEmpty()) }
    var date by remember(id) { mutableStateOf<Instant?>(observation.collectedAt) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(vitaText(L10nKey.EDIT_RESULT)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val valueLabel = if (observation.isQualitative) L10nKey.CONFIRMED_QUALITATIVE_VALUE else L10nKey.CONFIRMED_NUMERIC_VALUE
                OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(valueLabel)) }, singleLine = true)
                if (!observation.isQualitative) {
                    OutlinedTextField(unit, { unit = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.CONFIRMED_ORIGINAL_UNIT)) }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(lower, { lower = it }, Modifier.weight(1f), label = { Text(vitaText(L10nKey.REFERENCE_LOWER)) }, singleLine = true)
                        OutlinedTextField(upper, { upper = it }, Modifier.weight(1f), label = { Text(vitaText(L10nKey.REFERENCE_UPPER)) }, singleLine = true)
                    }
                }
                OutlinedTextField(rangeText, { rangeText = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.REFERENCE_TEXT_OPTIONAL)) }, singleLine = true)
                CollectionDateField(date, { date = it }, onApplyToAll = {}, showApplyToAll = false)
            }
        },
        confirmButton = {
            TextButton({ onSave(ResultEdit(value, unit, lower, upper, rangeText, date)) }, enabled = value.isNotBlank() && date != null) {
                Text(vitaText(L10nKey.SAVE))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(vitaText(L10nKey.CANCEL)) } },
    )
}

/** Earlier states of an edited result, newest first, so nothing the user once recorded is lost from view. */
@Composable
internal fun ResultHistory(observation: LabObservation) {
    if (observation.revisions.isEmpty()) return
    SectionHeading(vitaText(L10nKey.RESULT_HISTORY))
    observation.revisions.asReversed().forEach { revision ->
        Text(
            vitaText(
                L10nKey.RESULT_REVISION,
                formatUiDate(revision.revisedAt),
                previousValueText(revision),
                uiRangeText(revision.previousReferenceRange),
                formatUiDate(revision.previousCollectedAt),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun previousValueText(revision: ObservationRevision): String =
    revision.previousQualitativeValue ?: "${formatLabValue(revision.previousValue, revision.previousValueDecimals)} ${revision.previousUnit}".trim()
