package app.vezuna.ui.trends

import app.vezuna.domain.LabReport
import app.vezuna.domain.ReferenceBand
import app.vezuna.domain.TrendSeries
import app.vezuna.domain.trends.Granularity
import app.vezuna.domain.trends.HistorySeries
import app.vezuna.domain.trends.PointSource
import app.vezuna.domain.trends.PointSourceKind
import app.vezuna.domain.trends.RawReading
import app.vezuna.domain.trends.SeriesPoint
import app.vezuna.domain.trends.TrendEngine
import app.vezuna.domain.trends.TrendRange
import app.vezuna.localization.deviceLanguageTag
import app.vezuna.localization.formatLabValue
import app.vezuna.ui.ChartPoint
import app.vezuna.ui.components.HistoryRange
import kotlin.math.abs
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** Pure display rules for trend screens, kept out of composables so they can be tested. */

internal fun HistoryRange.toTrendRange(): TrendRange = months?.let { TrendRange.Months(it) } ?: TrendRange.All

/** A value plotted in a unit other than the one it was recorded in (a normalized conversion). */
internal fun SeriesPoint.isConverted(seriesUnit: String): Boolean = !originalUnit.trim().equals(seriesUnit.trim(), ignoreCase = true)

internal fun HistorySeries.chartPoints(): List<ChartPoint> =
    points.map { ChartPoint(it.xFraction.toFloat(), it.value, it.isConverted(unit)) }

/**
 * Which neighbouring points may be joined. Same-day readings never imply an ordered change. Lab results join across dates; device
 * series join only on consecutive calendar days, so a missing day stays a visible gap (isolated dots).
 */
internal fun HistorySeries.lineJoins(): List<Boolean> {
    if (!canDrawLine) return List((points.size - 1).coerceAtLeast(0)) { false }
    return points.zipWithNext { a, b ->
        if (a.source.kind == PointSourceKind.DEVICE) a.date.plus(1, DateTimeUnit.DAY) == b.date else a.date != b.date
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
    if (points.isNotEmpty() && points.all { it.valueDecimals != null && !it.isConverted(unit) }) points.maxOf { it.valueDecimals!! } else null

/** "+0.4", "−8" (true minus sign) or "0". Neutral: no arrow, no better/worse. */
internal fun signedNumber(delta: Double, decimals: Int?, languageTag: String = deviceLanguageTag()): String {
    val magnitude = formatLabValue(abs(delta), decimals, languageTag)
    return when {
        delta > 0 -> "+$magnitude"
        delta < 0 -> "−$magnitude"
        else -> magnitude
    }
}

/** Precision of a plotted value, never the printed precision of a different unit. */
internal fun SeriesPoint.plottedDecimals(seriesUnit: String): Int? = valueDecimals.takeUnless { isConverted(seriesUnit) }

/** A missing or stale selection consistently means the latest reading. */
internal fun HistorySeries.selectedIndex(selected: Int?): Int? =
    selected?.takeIf { it in points.indices } ?: points.indices.lastOrNull()

/** Retain Records' existing alignment/filtering rules while sharing the chart and readable rows. */
internal fun TrendSeries.historyPresentation(reports: List<LabReport> = emptyList()): HistorySeries {
    val reportsById = reports.filter { it.profileId == profileId }.associateBy { it.reportId }
    val readings = points.map { point ->
        val observation = point.observation
        val report = reportsById[observation.reportId]
        RawReading(
            observation.observationId,
            observation.collectedAt.toLocalDateTime(TimeZone.UTC).date,
            point.y, observation.originalValue, observation.originalUnit,
            observation.valueDecimals.takeUnless { point.converted }, observation.referenceRange,
            PointSource(if (report?.isManual == true) PointSourceKind.MANUAL else PointSourceKind.REPORT, report?.laboratoryName, observation.reportId),
            Granularity.READING, observation.observationId,
        )
    }
    val today = readings.lastOrNull()?.date ?: LocalDate(1970, 1, 1)
    val series = TrendEngine.series(
        "$testCode|$unit", points.lastOrNull()?.observation?.displayName ?: testCode,
        unit, readings, TrendRange.All, today,
    )
    val decimals = points.associate { it.observation.observationId to it.observation.valueDecimals }
    fun originalPrecision(point: SeriesPoint) = point.copy(valueDecimals = decimals[point.id])
    return series.copy(
        points = series.points.map(::originalPrecision), allPoints = series.allPoints.map(::originalPrecision),
        comparison = series.comparison.copy(latest = series.comparison.latest?.let(::originalPrecision)),
    )
}

/** The canvas also formats computed axis ticks. Only recorded values have printed precision. */
internal fun HistorySeries.chartValue(value: Double, languageTag: String = deviceLanguageTag()): String {
    val decimals = points.filter { it.value == value }.map { it.plottedDecimals(unit) }.distinct().singleOrNull()
    return formatLabValue(value, decimals, languageTag)
}
