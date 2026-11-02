package app.vezuna.ui.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.vezuna.domain.LabObservation
import app.vezuna.domain.ReferenceRange
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.formatUiDateTime
import app.vezuna.localization.vitaText
import app.vezuna.ui.Category
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.EmptyState
import app.vezuna.ui.components.FocusedTaskScaffold
import app.vezuna.ui.nav.RouteContext
import app.vezuna.ui.tabular
import app.vezuna.ui.uiRangeText
import kotlinx.datetime.Instant
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ill_file_error
import vezuna.shared.generated.resources.ill_record_empty

/** One correction: the state before it and the state it produced. */
internal data class Correction(
    val revisedAt: Instant,
    val before: String,
    val after: String,
    val beforeRange: ReferenceRange,
    val beforeDate: Instant,
)

/** Every correction of a result, newest first. Earlier states are history, never extra measurements. */
internal fun LabObservation.corrections(): List<Correction> {
    val current = stateValue(qualitativeValue, originalValue, valueDecimals, originalUnit)
    return revisions.mapIndexed { index, revision ->
        val next = revisions.getOrNull(index + 1)
        Correction(
            revisedAt = revision.revisedAt,
            before = stateValue(revision.previousQualitativeValue, revision.previousValue, revision.previousValueDecimals, revision.previousUnit),
            after = next?.let { stateValue(it.previousQualitativeValue, it.previousValue, it.previousValueDecimals, it.previousUnit) } ?: current,
            beforeRange = revision.previousReferenceRange,
            beforeDate = revision.previousCollectedAt,
        )
    }.asReversed()
}

/** Correction history (028): the current value, then each earlier state with what it changed to. */
@Composable
internal fun ResultHistoryScreen(ctx: RouteContext, observationId: String) {
    val observation = ctx.state.data?.observations?.firstOrNull { it.observationId == observationId }
    FocusedTaskScaffold(title = vitaText(L10nKey.ENTRY_HISTORY_TITLE), onBack = ctx::back) {
        if (observation == null) {
            EmptyState(Res.drawable.ill_file_error, vitaText(L10nKey.RESULT_UNAVAILABLE), vitaText(L10nKey.RESULT_UNAVAILABLE_EXPLANATION), Modifier.padding(VezunaTokens.Gutter))
            return@FocusedTaskScaffold
        }
        val corrections = observation.corrections()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = VezunaTokens.Gutter, vertical = VezunaTokens.SpaceMd),
            verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
        ) {
            Text(observation.displayName, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Surface(shape = RoundedCornerShape(VezunaTokens.CardRadius), color = Category.RECORDS.fill, contentColor = Category.RECORDS.onFill) {
                Column(Modifier.fillMaxWidth().padding(VezunaTokens.SpaceMd).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceXs)) {
                    Text(vitaText(L10nKey.ENTRY_HISTORY_CURRENT), style = MaterialTheme.typography.labelLarge)
                    Text(stateValue(observation.qualitativeValue, observation.originalValue, observation.valueDecimals, observation.originalUnit), style = MaterialTheme.typography.headlineMedium.tabular())
                    Text(vitaText(L10nKey.REFERENCE, uiRangeText(observation.referenceRange)), style = MaterialTheme.typography.bodyMedium)
                    Text(vitaText(L10nKey.ENTRY_COLLECTION_DATE_VALUE, formatUiDate(observation.collectedAt)), style = MaterialTheme.typography.bodyMedium)
                    corrections.firstOrNull()?.let { Text(vitaText(L10nKey.ENTRY_HISTORY_CORRECTED, formatUiDateTime(it.revisedAt)), style = MaterialTheme.typography.bodyMedium) }
                }
            }
            if (corrections.isEmpty()) {
                EmptyState(Res.drawable.ill_record_empty, vitaText(L10nKey.ENTRY_HISTORY_EMPTY_TITLE), vitaText(L10nKey.ENTRY_HISTORY_EMPTY_BODY))
            }
            corrections.forEach { correction ->
                Surface(shape = RoundedCornerShape(VezunaTokens.CardRadius), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(VezunaTokens.SpaceMd).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceXs)) {
                        Text(vitaText(L10nKey.ENTRY_HISTORY_CHANGED_ON, formatUiDateTime(correction.revisedAt)), style = MaterialTheme.typography.titleMedium)
                        Text(vitaText(L10nKey.ENTRY_HISTORY_OLD_NEW, correction.before, correction.after), style = MaterialTheme.typography.titleLarge.tabular())
                        Text(vitaText(L10nKey.REFERENCE, uiRangeText(correction.beforeRange)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(vitaText(L10nKey.ENTRY_COLLECTION_DATE_VALUE, formatUiDate(correction.beforeDate)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
