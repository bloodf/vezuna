package app.vezuna.ui.trends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import app.vezuna.domain.ProfileData
import app.vezuna.domain.trends.ComparisonStatus
import app.vezuna.domain.trends.DateWindow
import app.vezuna.domain.trends.LabTrends
import app.vezuna.domain.trends.TestTrend
import app.vezuna.domain.trends.TrendRange
import app.vezuna.domain.trends.window
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaPlural
import app.vezuna.localization.vitaText
import app.vezuna.ui.AppScreen
import app.vezuna.ui.Category
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.CategoryPanel
import app.vezuna.ui.components.EmptyState
import app.vezuna.ui.components.HistoryRange
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.components.SectionHeading
import app.vezuna.ui.components.SegmentedControl
import app.vezuna.ui.nav.RouteContext
import app.vezuna.ui.tabular
import app.vezuna.platform.currentInstant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ill_trend_empty

internal fun localToday(): LocalDate = currentInstant().toLocalDateTime(TimeZone.currentSystemDefault()).date

/** First through latest day across every series and text result of every test. */
private fun overallAvailable(trends: List<TestTrend>): DateWindow? {
    val dates = trends.flatMap { t -> t.series.flatMap { s -> s.allPoints.map { it.date } } + t.textEventsAll.map { it.date } }
    return if (dates.isEmpty()) null else DateWindow(dates.min(), dates.max())
}

/** Image 050 / 053: one card per confirmed test in the chosen period. */
@Composable
internal fun ColumnScope.LabsOverview(ctx: RouteContext, data: ProfileData, onShowDevice: () -> Unit, today: LocalDate = localToday()) {
    var range by remember(data.profile.profileId) { mutableStateOf(HistoryRange.ALL) }
    val trendRange = range.toTrendRange()
    val trends = LabTrends.forProfile(data.profile.profileId, data.observations, data.reports, data.documents, trendRange, today)
    if (trends.isEmpty()) {
        CategoryPanel(Category.TRENDS) {
            EmptyState(Res.drawable.ill_trend_empty, vitaText(L10nKey.NO_CONFIRMED_RESULTS_YET), vitaText(L10nKey.TR_EMPTY_BODY))
            PrimaryButton(vitaText(L10nKey.TR_REVIEW_IMPORTS), { ctx.navigate(AppScreen.ImportReview) }, Modifier.fillMaxWidth(), container = VezunaTokens.Pine, content = androidx.compose.ui.graphics.Color.White)
            SecondaryButton(vitaText(L10nKey.TR_TAB_DEVICE), onShowDevice, Modifier.fillMaxWidth())
        }
        return
    }
    val available = overallAvailable(trends)
    RangeSection(range, { range = it }, trendRange.window(today, available), available)
    SectionHeading(vitaText(L10nKey.TR_RESULTS))
    val inPeriod = trends.filter { t -> t.series.any { it.points.isNotEmpty() } || t.textEvents.isNotEmpty() }
    if (inPeriod.isEmpty()) NoReadingInPeriod { range = HistoryRange.ALL }
    inPeriod.forEach { trend -> TestCard(trend) { ctx.navigate(AppScreen.TrendDetail(trend.testCode)) } }
}

/** One test: name, count, latest value with unit and date, change since the previous result (or why there is none). */
@Composable
private fun TestCard(trend: TestTrend, onOpen: () -> Unit) {
    val series = trend.series.firstOrNull { it.points.isNotEmpty() }
    val count = trend.series.sumOf { it.points.size } + trend.textEvents.size
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { role = Role.Button },
        shape = RoundedCornerShape(VezunaTokens.CardRadius),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(VezunaTokens.SpaceMd), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceXs)) {
            Text(trend.displayName, style = MaterialTheme.typography.titleLarge)
            MutedText(vitaPlural(L10nKey.TR_RESULT_COUNT, count))
            if (series != null) {
                val latest = series.comparison.latest!!
                Text("${labValue(latest.value, latest.valueDecimals)} ${series.unit}".trim(), style = MaterialTheme.typography.headlineMedium.tabular())
                Text(dateText(latest.date), style = MaterialTheme.typography.bodyMedium)
                val previous = series.comparison.sincePrevious
                if (series.comparison.status == ComparisonStatus.COMPARABLE && previous != null) {
                    Text(changeText(previous.delta, previous.fromDate, previous.sameDay, series.decimals(), series.unit), style = MaterialTheme.typography.titleMedium.tabular())
                } else {
                    MutedText(vitaText(L10nKey.TR_NO_TREND_YET))
                }
                if (trend.series.size > 1) MutedText(vitaText(L10nKey.TR_UNITS_SEPARATE, trend.series.size))
            } else {
                trend.textEvents.lastOrNull()?.let { event ->
                    Text(event.text, style = MaterialTheme.typography.headlineSmall)
                    Text(dateText(event.date), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Images 051 / 052 / 054: one test, one unit at a time, chart + table, text results as a timeline. */
@Composable
internal fun TrendDetailBody(ctx: RouteContext, data: ProfileData, testCode: String, today: LocalDate = localToday()) {
    var range by remember(data.profile.profileId, testCode) { mutableStateOf(HistoryRange.ALL) }
    var unitIndex by remember(data.profile.profileId, testCode) { mutableStateOf(0) }
    val trendRange: TrendRange = range.toTrendRange()
    val trend = LabTrends.forTest(data.profile.profileId, testCode, data.observations, data.reports, data.documents, trendRange, today)
    TrendColumn {
        if (trend == null) {
            EmptyState(Res.drawable.ill_trend_empty, vitaText(L10nKey.NO_CONFIRMED_RESULTS_YET), vitaText(L10nKey.TR_EMPTY_BODY))
            return@TrendColumn
        }
        Text(trend.displayName, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        if (trend.series.size > 1) {
            CategoryPanel(Category.TRENDS) {
                CardHeading(vitaText(L10nKey.TR_UNITS_TITLE))
                Text(vitaText(L10nKey.TR_UNITS_BODY), style = MaterialTheme.typography.bodyMedium)
                SegmentedControl(trend.series.map { it.unit }, unitIndex.coerceAtMost(trend.series.lastIndex), { unitIndex = it })
            }
        }
        val series = trend.series.getOrNull(unitIndex.coerceAtMost(trend.series.lastIndex))
        val available = series?.available ?: trend.textEventsAll.firstOrNull()?.let { DateWindow(it.date, trend.textEventsAll.last().date) }
        RangeSection(range, { range = it }, series?.window ?: trendRange.window(today, available), available)
        if (series != null) {
            var selected by remember(series.key, range) { mutableStateOf<Int?>(null) }
            if (trend.series.size > 1) CardHeading(vitaPlural(L10nKey.TR_UNIT_COUNT, series.points.size, series.unit))
            if (series.points.isEmpty()) {
                NoReadingInPeriod(if (range != HistoryRange.ALL) ({ range = HistoryRange.ALL }) else null)
            } else {
                SeriesSummary(series, series.unit, { v, d -> labValue(v, d) }, vitaText(L10nKey.TR_LATEST_RESULT))
                SeriesChart(series, trend.displayName, series.unit, selected, { selected = it }, Category.TRENDS) { v, d -> labValue(v, d) }
                ReadingTable(series, vitaText(L10nKey.TR_RESULTS), series.unit, selected, { selected = it }) { v, d -> labValue(v, d) }
            }
        } else if (trend.textEvents.isEmpty()) {
            NoReadingInPeriod(if (range != HistoryRange.ALL) ({ range = HistoryRange.ALL }) else null)
        }
        TextTimeline(trend.textEvents)
        if (trend.textEventsAll.isNotEmpty() && trend.textEvents.isEmpty() && series != null) {
            MutedText(vitaPlural(L10nKey.TR_TEXT_OUTSIDE_PERIOD, trend.textEventsAll.size))
        }
    }
}
