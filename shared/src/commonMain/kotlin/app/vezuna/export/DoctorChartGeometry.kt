package app.vezuna.export

import app.vezuna.domain.UnitNormalizer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

data class DoctorChartPointGeometry(
    val point: DoctorTrendPoint,
    val xFraction: Double,
    val referenceLower: Double?,
    val referenceUpper: Double?,
)

data class DoctorChartGeometry(
    val minimumValue: Double,
    val maximumValue: Double,
    val points: List<DoctorChartPointGeometry>,
    /** Round axis ticks from [minimumValue] to [maximumValue] inclusive. */
    val ticks: List<Double> = emptyList(),
) {
    val range: Double get() = maximumValue - minimumValue

    fun yFraction(value: Double): Double = (value - minimumValue) / range
}

object DoctorChartGeometryFactory {
    fun create(series: DoctorTrendSeries): DoctorChartGeometry {
        require(series.points.isNotEmpty())
        val ordered = series.points.sortedBy { it.timestamp }
        val firstTime = ordered.first().timestamp.epochSeconds
        val lastTime = ordered.last().timestamp.epochSeconds
        val points = ordered.map { point ->
            val bounds = normalizedBounds(point, series.unit)
            DoctorChartPointGeometry(
                point = point,
                xFraction = if (lastTime == firstTime) 0.5 else (point.timestamp.epochSeconds - firstTime).toDouble() / (lastTime - firstTime).toDouble(),
                referenceLower = bounds.first,
                referenceUpper = bounds.second,
            )
        }
        val values = buildList {
            points.forEach { point ->
                add(point.point.value)
                point.referenceLower?.let(::add)
                point.referenceUpper?.let(::add)
            }
        }
        val rawMinimum = values.minOrNull()!!
        val rawMaximum = values.maxOrNull()!!
        val padding = if (rawMinimum == rawMaximum) max(1.0, abs(rawMinimum) * 0.05) else 0.0
        val step = niceStep((rawMaximum + padding) - (rawMinimum - padding))
        val dataMinimum = points.minOf { it.point.value }
        val dataMaximum = points.maxOf { it.point.value }
        var low = floor((rawMinimum - padding) / step) * step
        var high = ceil((rawMaximum + padding) / step) * step
        // A reference line exactly on the frame would vanish into the axis: give it one step of room.
        if (low == rawMinimum && rawMinimum < dataMinimum) low -= step
        if (high == rawMaximum && rawMaximum > dataMaximum) high += step
        val ticks = generateSequence(low) { it + step }.takeWhile { it <= high + step / 2 }.map { round(it, step) }.toList()
        return DoctorChartGeometry(low, high, points, ticks)
    }

    /** 1, 2, 2.5 or 5 times a power of ten, giving four to six intervals. */
    private fun niceStep(span: Double): Double {
        val raw = span / 4.0
        val magnitude = 10.0.pow(floor(log10(raw)))
        return listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { it >= raw }
    }

    private fun round(value: Double, step: Double): Double {
        val digits = max(0.0, -floor(log10(step)) + 1).toInt()
        val factor = 10.0.pow(digits)
        return kotlin.math.round(value * factor) / factor
    }

    private fun normalizedBounds(point: DoctorTrendPoint, unit: String): Pair<Double?, Double?> {
        val range = point.referenceRange
        val rangeUnit = range.unit ?: unit
        if (rangeUnit.equals(unit, ignoreCase = true)) return range.lower to range.upper
        val lower = range.lower?.let { UnitNormalizer.normalize(it, rangeUnit) }
        val upper = range.upper?.let { UnitNormalizer.normalize(it, rangeUnit) }
        return Pair(
            lower?.takeIf { it.unit.equals(unit, ignoreCase = true) }?.value,
            upper?.takeIf { it.unit.equals(unit, ignoreCase = true) }?.value,
        )
    }
}
