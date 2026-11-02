package app.vezuna.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vezuna.domain.AnalyteConversions
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabComparator
import app.vezuna.domain.LabObservation
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatConfidence
import app.vezuna.localization.formatLabValue
import app.vezuna.localization.localizeDecimalText
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.vitaPlural
import app.vezuna.localization.vitaText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
private fun changeLabel(direction: app.vezuna.domain.ChangeDirection): String = vitaText(when (direction) {
    app.vezuna.domain.ChangeDirection.INCREASE -> L10nKey.CHANGE_INCREASE
    app.vezuna.domain.ChangeDirection.DECREASE -> L10nKey.CHANGE_DECREASE
    app.vezuna.domain.ChangeDirection.UNCHANGED -> L10nKey.CHANGE_UNCHANGED
    app.vezuna.domain.ChangeDirection.NOT_COMPARABLE -> L10nKey.CHANGE_NOT_COMPARABLE
})

@Composable
private fun rangeLabel(assessment: app.vezuna.domain.RangeAssessment): String = vitaText(when (assessment) {
    app.vezuna.domain.RangeAssessment.BELOW -> L10nKey.RANGE_BELOW
    app.vezuna.domain.RangeAssessment.WITHIN -> L10nKey.RANGE_WITHIN
    app.vezuna.domain.RangeAssessment.ABOVE -> L10nKey.RANGE_ABOVE
    app.vezuna.domain.RangeAssessment.UNKNOWN -> L10nKey.RANGE_UNKNOWN
})

/** UI-only reference label: the report's own text wins, bounds use the language's number format. */
@Composable
internal fun uiRangeText(range: app.vezuna.domain.ReferenceRange): String {
    if (range.text != null) return range.text
    if (range.lower == null && range.upper == null) return vitaText(L10nKey.REFERENCE_NOT_PROVIDED)
    val unit = range.unit.orEmpty()
    val lower = range.lower?.let { formatLabValue(it) }
    val upper = range.upper?.let { formatLabValue(it) }
    return when {
        lower == null -> "≤ $upper $unit"
        upper == null -> "≥ $lower $unit"
        else -> "$lower–$upper $unit"
    }.trim()
}

@Composable
internal fun observationValueText(observation: LabObservation): String =
    observation.qualitativeValue ?: "${formatLabValue(observation.originalValue, observation.valueDecimals)} ${observation.originalUnit}".trim()

@Composable
internal fun ResultRow(observation: LabObservation, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().vitaAnimateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Rounded.Science, vitaText(L10nKey.CONFIRMED_RESULT) + ": ${observation.displayName}", Modifier.padding(8.dp).size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(observation.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(observation.testCode, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(vitaText(L10nKey.CONFIRMED), Icons.Rounded.CheckCircle, MaterialTheme.vita.successContainer, MaterialTheme.vita.onSuccessContainer)
            }
            Text(observationValueText(observation), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(vitaText(L10nKey.REFERENCE, uiRangeText(observation.referenceRange)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(5.dp))
                Text(formatUiDate(observation.collectedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                TextButton(onClick, Modifier.defaultMinSize(minHeight = 48.dp)) {
                    Text(vitaText(L10nKey.RESULT_DETAILS))
                    Icon(Icons.Rounded.ArrowForward, null, Modifier.size(17.dp))
                }
            }
        }
    }
}
