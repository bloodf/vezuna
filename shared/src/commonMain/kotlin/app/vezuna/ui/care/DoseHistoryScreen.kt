package app.vezuna.ui.care

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import app.vezuna.domain.DoseCounts
import app.vezuna.domain.DoseHistoryFactory
import app.vezuna.domain.Medication
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.vitaText
import app.vezuna.platform.currentInstant
import app.vezuna.ui.Category
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.CategoryPanel
import app.vezuna.ui.components.GroupedRows
import app.vezuna.ui.components.HistoryRange
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.RangeChips
import app.vezuna.ui.components.SectionHeading
import app.vezuna.ui.nav.RouteContext
import kotlinx.datetime.TimeZone

/**
 * Dose history (067, #56): plain counts of recorded doses with the denominator named, for 7 and 30 days and the
 * chosen range, then every recorded dose. No score, no judgement; MISSED reads "recorded as missed". A row opens
 * the dose sheet, where the record can be changed (latest action wins).
 */
@Composable
internal fun DoseHistoryBody(ctx: RouteContext, medication: Medication?) {
    val data = ctx.state.data ?: return
    var range by rememberSaveable { mutableStateOf(HistoryRange.ALL) }
    var actionFor by remember { mutableStateOf<CareDose?>(null) }
    val now = currentInstant()
    val zone = TimeZone.currentSystemDefault()
    val history = DoseHistoryFactory.create(data, now, zone)
    val windows = medication?.let { m -> history.medications.firstOrNull { it.medicationId == m.medicationId } }
        ?.let { it.last7Days to it.last30Days } ?: (history.last7Days to history.last30Days)
    val all = CareDoses.recorded(data, medication?.medicationId)
    val shown = CareDoses.within(all, range.months, now, zone)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = VezunaTokens.GutterNarrow).padding(top = VezunaTokens.SpaceMd),
        verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
    ) {
        CategoryPanel(Category.CARE) {
            Text(medication?.displayName ?: vitaText(L10nKey.CARE_ALL_MEDICATIONS), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            medication?.let { m -> listOfNotNull(m.strength, m.instructions).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(", "), style = MaterialTheme.typography.bodyLarge) } }
            WindowSummary(7, windows.first)
            WindowSummary(30, windows.second)
            Text(vitaText(L10nKey.CARE_HISTORY_NOTE), style = MaterialTheme.typography.bodyMedium)
        }
        RangeChips(range, { range = it })
        if (shown.isEmpty()) {
            Text(vitaText(L10nKey.CARE_HISTORY_EMPTY), style = MaterialTheme.typography.bodyLarge)
        } else {
            Text(
                vitaText(L10nKey.CARE_HISTORY_PERIOD, formatUiDate(shown.last().dose.scheduledFor), formatUiDate(shown.first().dose.scheduledFor)),
                style = MaterialTheme.typography.titleMedium,
            )
            CountsText(CareDoses.counts(shown))
            Column(verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
                SectionHeading(vitaText(L10nKey.CARE_RECENT_DOSES))
                GroupedRows {
                    shown.forEach { dose ->
                        val state = doseStateText(dose)
                        ListRow(
                            formatUiDate(dose.dose.scheduledFor) + " · " + dose.dose.scheduledFor.localTime(),
                            onClick = { actionFor = dose },
                            supporting = if (medication == null) "${dose.dose.medicationName} · $state" else state,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(VezunaTokens.SpaceLg))
    }
    actionFor?.let { DoseActionSheet(ctx, it) { actionFor = null } }
}

@Composable
private fun WindowSummary(days: Int, counts: DoseCounts) {
    Column {
        Text(vitaText(L10nKey.CARE_HISTORY_WINDOW, days), style = MaterialTheme.typography.labelLarge)
        if (counts.completed == 0) {
            Text(vitaText(L10nKey.CARE_HISTORY_EMPTY), style = MaterialTheme.typography.bodyLarge)
        } else {
            CountsText(counts)
        }
    }
}

@Composable
private fun CountsText(counts: DoseCounts) {
    Text(vitaText(L10nKey.CARE_HISTORY_SHARE, counts.taken, counts.completed), style = MaterialTheme.typography.titleMedium)
    Text(vitaText(L10nKey.CARE_HISTORY_COUNTS, counts.taken, counts.skipped, counts.missed), style = MaterialTheme.typography.bodyLarge)
}
