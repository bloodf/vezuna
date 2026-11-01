package app.vezuna.export

import app.vezuna.domain.UnitNormalizer
import kotlin.math.abs
import kotlin.math.max

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
        val padding = if (rawMinimum == rawMaximum) max(1.0, abs(rawMinimum) * 0.05) else (rawMaximum - rawMinimum) * 0.08
        return DoctorChartGeometry(rawMinimum - padding, rawMaximum + padding, points)
    }

    private fun normalizedBounds(point: DoctorTrendPoint, unit: String): Pair<Double?, Double?> {
        val range = point.referenceRange
        val rangeUnit = range.unit ?: unit
        val lower = range.lower?.let { UnitNormalizer.normalize(it, rangeUnit) }
        val upper = range.upper?.let { UnitNormalizer.normalize(it, rangeUnit) }
        return Pair(
            lower?.takeIf { it.unit.equals(unit, ignoreCase = true) }?.value,
            upper?.takeIf { it.unit.equals(unit, ignoreCase = true) }?.value,
        )
    }
}
