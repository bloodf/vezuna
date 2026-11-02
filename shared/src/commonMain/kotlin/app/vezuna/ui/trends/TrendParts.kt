package app.vezuna.ui.trends

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vezuna.domain.ReferenceRange
import app.vezuna.domain.trends.ComparisonStatus
import app.vezuna.domain.trends.DateWindow
import app.vezuna.domain.trends.Granularity
import app.vezuna.domain.trends.HistorySeries
import app.vezuna.domain.trends.PointSource
import app.vezuna.domain.trends.PointSourceKind
import app.vezuna.domain.trends.SeriesPoint
import app.vezuna.domain.trends.TextEvent
import app.vezuna.domain.trends.noonUtc
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatLabValue
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.vitaPlural
import app.vezuna.localization.vitaText
import app.vezuna.ui.Category
import app.vezuna.ui.LineChart
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.HistoryRange
import app.vezuna.ui.components.RangeChips
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.components.TertiaryButton
import app.vezuna.ui.tabular
import app.vezuna.ui.uiRangeText
import kotlinx.datetime.LocalDate

@Composable
internal fun dateText(date: LocalDate): String = formatUiDate(noonUtc(date))

@Composable
internal fun periodText(window: DateWindow): String =
    if (window.start == window.end) dateText(window.start) else vitaText(L10nKey.TR_PERIOD, dateText(window.start), dateText(window.end))

/** Scrolling body with the standard gutter. */
@Composable
internal fun TrendColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = VezunaTokens.GutterNarrow, vertical = VezunaTokens.SpaceMd),
        verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
        content = content,
    )
}

/** Opaque card for clinical values. */
@Composable
internal fun TrendCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(VezunaTokens.CardRadius), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(VezunaTokens.SpaceMd), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm), content = content)
    }
}

@Composable
internal fun CardHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
}

@Composable
internal fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/**
 * Range chips (own scrollable row, so large text never clips them), the selected period and, when it differs,
 * the dates that actually have data.
 */
@Composable
internal fun RangeSection(range: HistoryRange, onRange: (HistoryRange) -> Unit, window: DateWindow?, available: DateWindow?) {
    Column(verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceXs)) {
        RangeChips(range, onRange)
        window?.let { Text(periodText(it), style = MaterialTheme.typography.titleSmall) }
        if (available != null && available != window) MutedText(vitaText(L10nKey.TR_AVAILABLE, periodText(available)))
    }
}

/** "+0.4 g/dL since 15 Jul 2026", or "same day" when both readings share the date. */
@Composable
internal fun changeText(delta: Double, fromDate: LocalDate, sameDay: Boolean, decimals: Int?, unit: String): String {
    val amount = "${signedNumber(delta, decimals)} $unit".trim()
    return if (sameDay) vitaText(L10nKey.TR_CHANGE_SAME_DAY, amount) else vitaText(L10nKey.TR_CHANGE_SINCE, amount, dateText(fromDate))
}

/** Latest value with unit and date, then change since first and previous (each with its date), or why not. */
@Composable
internal fun SeriesSummary(series: HistorySeries, unit: String, formatValue: @Composable (Double, Int?) -> String, latestLabel: String) {
    val comparison = series.comparison
    TrendCard {
        val latest = comparison.latest
        if (latest == null) {
            Text(vitaText(L10nKey.TR_STATUS_NO_READING), style = MaterialTheme.typography.titleMedium)
            return@TrendCard
        }
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(latestLabel, style = MaterialTheme.typography.labelLarge)
            Text("${formatValue(latest.value, latest.valueDecimals)} $unit".trim(), style = MaterialTheme.typography.displaySmall.tabular(), fontWeight = FontWeight.Bold)
            Text(dateText(latest.date), style = MaterialTheme.typography.bodyLarge)
        }
        when (comparison.status) {
            ComparisonStatus.COMPARABLE -> {
                val decimals = series.decimals()
                comparison.sincePrevious?.let { c ->
                    ChangeLine(vitaText(L10nKey.TR_SINCE_PREVIOUS), changeText(c.delta, c.fromDate, c.sameDay, decimals, unit))
                }
                comparison.sinceFirst?.let { c ->
                    ChangeLine(vitaText(L10nKey.TR_SINCE_FIRST), changeText(c.delta, c.fromDate, c.sameDay, decimals, unit))
                }
                MutedText(vitaText(L10nKey.TR_CHANGE_NOTE))
            }
            ComparisonStatus.NOT_ENOUGH_READINGS -> MutedText(vitaText(L10nKey.TR_STATUS_NOT_ENOUGH))
            ComparisonStatus.NO_READING -> MutedText(vitaText(L10nKey.TR_STATUS_NO_READING))
        }
    }
}

@Composable
private fun ChangeLine(label: String, value: String) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium.tabular())
    }
}

/**
 * Chart with its spoken summary, previous/next controls (the keyboard and switch path to every point), and the
 * selected point's details. Draws nothing when the period has no reading.
 */
@Composable
internal fun SeriesChart(
    series: HistorySeries,
    name: String,
    unit: String,
    selected: Int?,
    onSelect: (Int) -> Unit,
    category: Category,
    formatValue: @Composable (Double, Int?) -> String,
) {
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val points = series.points
    if (points.isEmpty()) return
    val window = series.window ?: return
    val values = points.map { it.value }
    val latest = points.last()
    val description = vitaPlural(
        L10nKey.TR_CHART_DESCRIPTION, points.size, name, unit, dateText(window.start), dateText(window.end),
        formatValue(latest.value, latest.valueDecimals), dateText(latest.date),
        formatValue(values.min(), null), formatValue(values.max(), null),
    )
    val band = series.referenceBand()
    TrendCard {
        LineChart(
            series.chartPoints(), "${series.key}|${window}", description,
            Modifier.fillMaxWidth().height(230.dp),
            unit = unit, band = band, joins = series.lineJoins(),
            startLabel = dateText(window.start), endLabel = dateText(window.end),
            selected = selected, onSelect = onSelect,
            color = category.series(darkTheme),
        )
        MutedText(vitaPlural(L10nKey.TR_RECORDED_COUNT, points.size))
        if (points.any { it.granularity == Granularity.DAILY_SUMMARY }) MutedText(vitaText(L10nKey.TR_DAILY_SUMMARY_NOTE))
        if (!series.canDrawLine) MutedText(vitaText(L10nKey.TR_NO_LINE_NOTE))
        band?.let { b ->
            val text = uiRangeText(ReferenceRange(b.lower, b.upper, null, unit))
            MutedText(vitaText(if (b.lower != null && b.upper != null) L10nKey.TR_BAND_SHADED else L10nKey.TR_BAND_LINE, text))
        }
        if (series.rangesDiffer()) MutedText(vitaText(L10nKey.TR_BAND_DIFFERS))
        if (points.any { it.isConverted(series.unit) }) MutedText(vitaText(L10nKey.TR_CONVERTED_NOTE, series.unit))
        if (points.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
                val current = selected ?: points.lastIndex
                SecondaryButton(vitaText(L10nKey.TR_PREVIOUS_POINT), { onSelect((current - 1).coerceAtLeast(0)) }, enabled = current > 0)
                SecondaryButton(vitaText(L10nKey.TR_NEXT_POINT), { onSelect((current + 1).coerceAtMost(points.lastIndex)) }, enabled = current < points.lastIndex)
            }
        }
        selected?.let { points.getOrNull(it) }?.let { point ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(VezunaTokens.ControlRadius)).background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(VezunaTokens.SpaceSmd).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(vitaText(L10nKey.TR_SELECTED), style = MaterialTheme.typography.labelLarge)
                PointDetails(point, series.unit, unit, formatValue)
            }
        }
    }
}

/** Date, original value and unit, then source and reference: what a person would check against the report. */
@Composable
private fun PointDetails(point: SeriesPoint, seriesUnit: String, displayUnit: String, formatValue: @Composable (Double, Int?) -> String) {
    val converted = point.isConverted(seriesUnit)
    Text(dateText(point.date), style = MaterialTheme.typography.bodyLarge)
    Text(
        "${formatValue(point.originalValue, point.valueDecimals)} ${if (converted) point.originalUnit else displayUnit}".trim(),
        style = MaterialTheme.typography.titleLarge.tabular(),
    )
    if (converted) MutedText(vitaText(L10nKey.TR_PLOTTED_AS, "${formatLabValue(point.value)} $seriesUnit"))
    MutedText(vitaText(L10nKey.TR_SOURCE, sourceText(point.source)))
    point.referenceRange?.let { MutedText(vitaText(L10nKey.REFERENCE, uiRangeText(it))) }
}

@Composable
internal fun sourceText(source: PointSource): String = when (source.kind) {
    PointSourceKind.REPORT -> source.label ?: vitaText(L10nKey.TR_SOURCE_REPORT)
    PointSourceKind.MANUAL -> source.label?.let { vitaText(L10nKey.TR_SOURCE_MANUAL_LAB, it) } ?: vitaText(L10nKey.TR_SOURCE_MANUAL)
    PointSourceKind.DEVICE -> vitaText(L10nKey.TR_SOURCE_DEVICE)
}

/** The readable table paired with every chart: newest first, one focus stop per row, tap to select on the chart. */
@Composable
internal fun ReadingTable(
    series: HistorySeries,
    title: String,
    unit: String,
    selected: Int?,
    onSelect: (Int) -> Unit,
    formatValue: @Composable (Double, Int?) -> String,
) {
    if (series.points.isEmpty()) return
    TrendCard {
        CardHeading(title)
        series.points.withIndex().reversed().forEach { (index, point) ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(VezunaTokens.ControlRadius))
                    .background(if (index == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                    .selectable(selected = index == selected, role = Role.Button) { onSelect(index) }
                    .padding(vertical = VezunaTokens.SpaceSm, horizontal = VezunaTokens.SpaceXs)
                    .semantics(mergeDescendants = true) {},
            ) {
                PointDetails(point, series.unit, unit, formatValue)
            }
        }
    }
}

/** Text results as dated records: never a number, axis or line. */
@Composable
internal fun TextTimeline(events: List<TextEvent>) {
    if (events.isEmpty()) return
    TrendCard {
        CardHeading(vitaPlural(L10nKey.TR_TEXT_COUNT, events.size))
        MutedText(vitaText(L10nKey.TR_TEXT_BODY))
        events.reversed().forEach { event ->
            Column(Modifier.fillMaxWidth().padding(vertical = VezunaTokens.SpaceSm).semantics(mergeDescendants = true) {}) {
                Text(dateText(event.date), style = MaterialTheme.typography.bodyLarge)
                Text(event.text, style = MaterialTheme.typography.headlineSmall)
                MutedText(vitaText(L10nKey.TR_SOURCE, sourceText(event.source)))
            }
        }
    }
}

/** Nothing in the chosen period: say so, keep the dates, offer the whole history. */
@Composable
internal fun NoReadingInPeriod(onShowAll: (() -> Unit)?) {
    TrendCard {
        Text(vitaText(L10nKey.TR_STATUS_NO_READING), style = MaterialTheme.typography.titleMedium)
        MutedText(vitaText(L10nKey.TR_NO_READING_BODY))
        onShowAll?.let { TertiaryButton(vitaText(L10nKey.TR_SHOW_ALL), it) }
    }
}

@Composable
internal fun labValue(value: Double, decimals: Int?): String = formatLabValue(value, decimals)
