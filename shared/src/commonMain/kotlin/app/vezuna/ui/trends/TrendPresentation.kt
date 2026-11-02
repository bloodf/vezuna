package app.vezuna.ui.trends

import app.vezuna.domain.ReferenceBand
import app.vezuna.domain.trends.HistorySeries
import app.vezuna.domain.trends.PointSourceKind
import app.vezuna.domain.trends.SeriesPoint
import app.vezuna.domain.trends.TrendRange
import app.vezuna.localization.deviceLanguageTag
import app.vezuna.localization.formatLabValue
import app.vezuna.ui.ChartPoint
import app.vezuna.ui.components.HistoryRange
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlin.math.abs

/** Pure display rules for trend screens, kept out of composables so they can be tested. */

internal fun HistoryRange.toTrendRange(): TrendRange = months?.let { TrendRange.Months(it) } ?: TrendRange.All

/** A value plotted in a unit other than the one it was recorded in (a normalized conversion). */
internal fun SeriesPoint.isConverted(seriesUnit: String): Boolean = !originalUnit.trim().equals(seriesUnit.trim(), ignoreCase = true)

internal fun HistorySeries.chartPoints(): List<ChartPoint> =
    points.map { ChartPoint(it.xFraction.toFloat(), it.value, it.isConverted(unit)) }

/**
 * Which neighbouring points may be joined. Never with fewer than two dates. Lab results join in order; device
 * series join only on consecutive calendar days, so a missing day stays a visible gap (isolated dots).
 */
internal fun HistorySeries.lineJoins(): List<Boolean> {
    if (!canDrawLine) return List((points.size - 1).coerceAtLeast(0)) { false }
    return points.zipWithNext { a, b ->
        if (a.source.kind == PointSourceKind.DEVICE) a.date.plus(1, DateTimeUnit.DAY) == b.date else true
    }
}

/**
 * The shared report range for the chart, only when honest: no converted point, every point states the same
 * bounds in the series unit (or no unit), and at least one bound exists. Otherwise each row shows its own range.
 */
internal fun HistorySeries.referenceBand(): ReferenceBand? {
    if (points.isEmpty() || points.any { it.isConverted(unit) }) return null
    val ranges = points.map { it.referenceRange ?: return null }
    if (ranges.any { r -> r.unit != null && !r.unit.trim().equals(unit.trim(), ignoreCase = true) }) return null
    val first = ranges.first()
    if (first.lower == null && first.upper == null) return null
    if (ranges.any { it.lower != first.lower || it.upper != first.upper }) return null
    return ReferenceBand(first.lower, first.upper)
}

/** True when the points carry ranges but they cannot share one band. */
internal fun HistorySeries.rangesDiffer(): Boolean =
    referenceBand() == null && points.any { r -> r.referenceRange?.let { it.lower != null || it.upper != null } == true }

/** Printed precision shared by every point, or null (shortest exact form). */
internal fun HistorySeries.decimals(): Int? =
    if (points.isNotEmpty() && points.all { it.valueDecimals != null }) points.maxOf { it.valueDecimals!! } else null

/** "+0.4", "−8" (true minus sign) or "0". Neutral: no arrow, no better/worse. */
internal fun signedNumber(delta: Double, decimals: Int?, languageTag: String = deviceLanguageTag()): String {
    val magnitude = formatLabValue(abs(delta), decimals, languageTag)
    return when {
        delta > 0 -> "+$magnitude"
        delta < 0 -> "−$magnitude"
        else -> magnitude
    }
}
