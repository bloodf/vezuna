package app.vezuna.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vezuna.domain.QualitativeTimelines
import app.vezuna.domain.TrendPreparation
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatLabValue
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.vitaPlural
import app.vezuna.localization.vitaText

@Composable
fun TrendsScreen(state: AppUiState, controller: AppController) {
    val data = state.data ?: return EmptyState(vitaText(L10nKey.NO_TRENDS), vitaText(L10nKey.SELECT_PROFILE_FIRST))
    val choices = TrendPreparation.choices(data.observations)
    val timelines = QualitativeTimelines.forProfile(data.profile.profileId, data.observations)
    var selected by remember(data.profile.profileId) { mutableStateOf(choices.firstOrNull()?.code.orEmpty()) }
    if (choices.none { it.code == selected }) selected = choices.firstOrNull()?.code.orEmpty()
    ScreenColumn(vitaText(L10nKey.LAB_TRENDS), vitaText(L10nKey.LAB_TRENDS_SUBTITLE)) {
        if (choices.isEmpty() && timelines.isEmpty()) {
            EmptyState(
                vitaText(L10nKey.NO_CONFIRMED_TREND_DATA), vitaText(L10nKey.TRENDS_EMPTY_EXPLANATION), Icons.Rounded.ShowChart,
                vitaText(L10nKey.IMPORT_REVIEW), { controller.navigate(AppScreen.ImportReview) },
            )
        } else if (choices.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                choices.forEach { choice ->
                    FilterChip(selected = selected == choice.code, onClick = { selected = choice.code }, label = { Text(choice.displayName) }, leadingIcon = { Icon(Icons.Rounded.Science, null, Modifier.size(17.dp)) })
                }
            }
            val series = TrendPreparation.forTest(data.profile.profileId, selected, data.observations)
            val motionEnabled = LocalVitaAnimationsEnabled.current
            AnimatedContent(
                targetState = series,
                transitionSpec = {
                    if (motionEnabled) androidx.compose.animation.fadeIn() togetherWith androidx.compose.animation.fadeOut()
                    else androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                },
                label = "trend-selection",
            ) { current ->
                if (current != null) TrendSeriesContent(current)
            }
        }
        if (timelines.isNotEmpty()) {
            SectionHeading(vitaText(L10nKey.QUALITATIVE_TIMELINE))
            timelines.forEach { timeline ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(timeline.displayName, style = MaterialTheme.typography.titleMedium)
                        timeline.entries.forEach { entry ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(formatUiDate(entry.collectedAt), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Text(entry.qualitativeValue.orEmpty(), style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            }
        }
        LinkRow(vitaText(L10nKey.BACK_TO_LABS)) { controller.navigate(AppScreen.Labs) }
    }
}

@Composable
private fun TrendSeriesContent(series: app.vezuna.domain.TrendSeries) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(series.points.last().observation.displayName, style = MaterialTheme.typography.titleLarge)
                        Text(vitaPlural(L10nKey.CONFIRMED_POINTS, series.points.size, series.unit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Rounded.Insights, vitaText(L10nKey.TREND_CHART), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(12.dp))
                TrendChart(series, Modifier.fillMaxWidth().height(210.dp))
                Row {
                    Text(formatUiDate(series.points.first().observation.collectedAt), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text(formatUiDate(series.points.last().observation.collectedAt), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (series.points.any { it.converted }) InlineNotice(vitaText(L10nKey.TREND_CONVERTED_NOTE))
        if (series.droppedUnits.isNotEmpty()) InlineNotice(vitaText(L10nKey.TREND_UNITS_DROPPED, series.droppedUnits.joinToString(", "), series.unit))
        SectionHeading(vitaText(L10nKey.CONFIRMED_DATA), vitaText(L10nKey.CONFIRMED_DATA_SUBTITLE))
        series.points.forEach { point ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.History, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(formatUiDate(point.observation.collectedAt), style = MaterialTheme.typography.bodyMedium)
                    Text(vitaText(L10nKey.REFERENCE, uiRangeText(point.observation.referenceRange)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(observationValueText(point.observation), style = MaterialTheme.typography.titleSmall)
                    if (point.converted) {
                        Text("≈ ${formatLabValue(point.y)} ${series.unit}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
