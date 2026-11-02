package app.vezuna.domain.trends

import app.vezuna.domain.ReferenceRange
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round

internal data class RawReading(
    val id: String,
    val date: LocalDate,
    val value: Double,
    val originalValue: Double,
    val originalUnit: String,
    val decimals: Int?,
    val range: ReferenceRange?,
    val source: PointSource,
    val granularity: Granularity,
    val tieBreak: String,
)

internal fun noonUtc(date: LocalDate): Instant = date.atTime(12, 0).toInstant(TimeZone.UTC)

internal object TrendEngine {
    fun series(
        key: String,
        displayName: String,
        unit: String,
        readings: List<RawReading>,
        range: TrendRange,
        today: LocalDate,
    ): HistorySeries {
        val sorted = readings.sortedWith(compareBy({ it.date }, { it.tieBreak }))
        val available = sorted.firstOrNull()?.let { DateWindow(it.date, sorted.last().date) }
        val window = range.window(today, available)
        val axis = window?.let { TimeAxis(noonUtc(it.start).epochSeconds, noonUtc(it.end).epochSeconds) }
        fun point(r: RawReading, a: TimeAxis?): SeriesPoint {
            val at = noonUtc(r.date)
            return SeriesPoint(
                r.id, r.date, at, r.value, r.originalValue, r.originalUnit, r.decimals, r.range, r.source,
                r.granularity, a?.fractionOf(at.epochSeconds) ?: 0.5,
            )
        }
        val points = if (window == null) emptyList() else sorted.filter { it.date in window.start..window.end }.map { point(it, axis) }
        // Whole history is positioned on its own span so it stays usable when the window is a preset.
        val allAxis = available?.let { TimeAxis(noonUtc(it.start).epochSeconds, noonUtc(it.end).epochSeconds) }
        return HistorySeries(key, displayName, unit, window, axis, points, sorted.map { point(it, allAxis) }, compare(points, unit))
    }

    private fun compare(points: List<SeriesPoint>, unit: String): TrendComparison = when (points.size) {
        0 -> TrendComparison(ComparisonStatus.NO_READING, null, null, null)
        1 -> TrendComparison(ComparisonStatus.NOT_ENOUGH_READINGS, points[0], null, null)
        else -> {
            val latest = points.last()
            TrendComparison(
                ComparisonStatus.COMPARABLE, latest,
                change(points.first(), latest, unit), change(points[points.size - 2], latest, unit),
            )
        }
    }

    private fun change(from: SeriesPoint, to: SeriesPoint, unit: String): ChangeSummary {
        val decimals = if (from.valueDecimals != null && to.valueDecimals != null) max(from.valueDecimals, to.valueDecimals) else null
        return ChangeSummary(roundTo(to.value - from.value, decimals ?: MAX_DECIMALS), unit, from.date, from.value, to.date, to.value)
    }

    // Removes binary noise (68.6 - 68.8 = -0.19999999999999574) without hiding real precision.
    private fun roundTo(value: Double, decimals: Int): Double {
        val scale = 10.0.pow(decimals)
        val r = round(value * scale) / scale
        return if (r == 0.0) 0.0 else r
    }

    private const val MAX_DECIMALS = 9
}
