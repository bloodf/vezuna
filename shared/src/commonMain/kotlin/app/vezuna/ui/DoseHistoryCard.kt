package app.vezuna.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.vezuna.domain.DoseCounts
import app.vezuna.domain.DoseHistoryFactory
import app.vezuna.domain.ProfileData
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import app.vezuna.platform.currentInstant
import kotlinx.datetime.TimeZone

/** Descriptive dose history: adherence over 7 and 30 days and per-medication taken/skipped/missed counts. */
@Composable
fun DoseHistoryCard(data: ProfileData) {
    if (data.medications.isEmpty()) return
    val history = DoseHistoryFactory.create(data, currentInstant(), TimeZone.currentSystemDefault())
    SectionHeading(vitaText(L10nKey.DOSE_HISTORY))
    if (history.last30Days.completed == 0) {
        Text(vitaText(L10nKey.NO_DOSE_EVENTS), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    AdherenceLine(L10nKey.ADHERENCE_7_DAY, history.last7Days)
    AdherenceLine(L10nKey.ADHERENCE_30_DAY, history.last30Days)
    history.medications.filter { it.last30Days.completed > 0 }.forEach { medication ->
        Column {
            Text(medication.medicationName, style = MaterialTheme.typography.titleSmall)
            val counts = medication.last30Days
            Text(
                vitaText(L10nKey.DOSE_HISTORY_COUNTS, counts.taken, counts.skipped, counts.missed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AdherenceLine(label: L10nKey, counts: DoseCounts) {
    val fraction = counts.adherenceFraction ?: return
    Column {
        Text(vitaText(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(vitaText(L10nKey.ADHERENCE_SUMMARY, (fraction * 100).toInt(), counts.taken, counts.completed), style = MaterialTheme.typography.titleMedium)
    }
}
