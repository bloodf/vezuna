package app.vezuna.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vezuna.domain.DeviceVitals
import app.vezuna.domain.ProfileData
import app.vezuna.domain.VitalMetric
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaPlural
import app.vezuna.localization.vitaText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

private data class MetricCopy(val title: L10nKey, val unit: L10nKey, val aggregate: L10nKey?)

private fun VitalMetric.copy() = when (this) {
    VitalMetric.HEART_RATE_BPM -> MetricCopy(L10nKey.VITAL_HEART_RATE, L10nKey.VITAL_UNIT_BPM, L10nKey.VITAL_DAILY_AVERAGE)
    VitalMetric.STEPS -> MetricCopy(L10nKey.VITAL_STEPS, L10nKey.VITAL_UNIT_STEPS, L10nKey.VITAL_DAILY_TOTAL)
    VitalMetric.WEIGHT_KG -> MetricCopy(L10nKey.VITAL_WEIGHT, L10nKey.VITAL_UNIT_KG, null)
}

@Composable
fun DeviceHealthSettings(state: AppUiState, data: ProfileData, controller: AppController, scope: CoroutineScope) {
    val linkedId = state.healthLinkedProfileId
    val linked = state.profiles.firstOrNull { it.profileId == linkedId }
    SectionHeading(vitaText(L10nKey.SECTION_DEVICE_HEALTH))
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(vitaText(L10nKey.LINKED_PROFILE_EXPLANATION), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            linked?.let { Text(vitaText(L10nKey.LINKED_PROFILE_CURRENT, it.displayName), style = MaterialTheme.typography.titleSmall) }
            if (data.profile.profileId != linkedId) {
                VitaButton(vitaText(L10nKey.LINK_THIS_PROFILE), Icons.Rounded.Link, { scope.launch { controller.setLinkedHealthProfile(data.profile.profileId) } }, outlined = true)
            }
            LinkRow(vitaText(L10nKey.VIEW_VITALS)) { controller.navigate(AppScreen.Vitals) }
        }
    }
}

@Composable
fun VitalsScreen(state: AppUiState, controller: AppController, scope: CoroutineScope) {
    val data = state.data ?: return EmptyState(vitaText(L10nKey.NO_VITALS), vitaText(L10nKey.SELECT_PROFILE_FIRST))
    val linked = state.profiles.firstOrNull { it.profileId == state.healthLinkedProfileId }
    ScreenColumn(vitaText(L10nKey.VITALS_TITLE), vitaText(L10nKey.VITALS_SUBTITLE, data.profile.displayName)) {
        if (linked != null && linked.profileId != data.profile.profileId) {
            InlineNotice(vitaText(L10nKey.VITALS_OTHER_PROFILE, linked.displayName), Icons.Rounded.Link)
            VitaButton(vitaText(L10nKey.SWITCH_TO_PROFILE, linked.displayName), Icons.Rounded.Favorite, { scope.launch { controller.switchProfile(linked.profileId) } })
        } else if (data.vitals.isEmpty) {
            EmptyState(vitaText(L10nKey.NO_VITALS), vitaText(L10nKey.NO_VITALS_EXPLANATION), Icons.Rounded.MonitorHeart)
        } else {
            VitalMetric.entries.forEach { metric -> MetricCard(data, metric) }
            Text(vitaText(L10nKey.VITALS_RETENTION_NOTE), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinkRow(vitaText(L10nKey.SETTINGS)) { controller.navigate(AppScreen.Settings) }
    }
}

@Composable
private fun MetricCard(data: ProfileData, metric: VitalMetric) {
    val points = DeviceVitals.series(data.vitals, metric, TimeZone.currentSystemDefault())
    if (points.isEmpty()) return
    val copy = metric.copy()
    val unit = vitaText(copy.unit)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(vitaText(copy.title), style = MaterialTheme.typography.titleMedium)
            copy.aggregate?.let { Text(vitaText(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(vitaText(L10nKey.VITAL_LATEST, "${formatVital(points.last().second)} $unit"), style = MaterialTheme.typography.bodyMedium)
            val values = points.map { it.second }
            LineChart(
                points, "${data.profile.profileId}|$metric",
                vitaPlural(
                    L10nKey.VITALS_CHART_ACCESSIBILITY, points.size, unit,
                    formatVital(values.min()), formatVital(values.max()), formatVital(values.last()),
                ),
                Modifier.fillMaxWidth().height(160.dp),
                unit = unit,
            )
        }
    }
}

private fun formatVital(value: Double): String {
    val tenths = kotlin.math.round(value * 10).toLong()
    return if (tenths % 10 == 0L) (tenths / 10).toString() else "${tenths / 10}.${kotlin.math.abs(tenths % 10)}"
}
