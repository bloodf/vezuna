package app.vezuna.domain

data class ReferenceBand(val lower: Double?, val upper: Double?)

/**
 * The shaded reference band of a trend, only when it is honest: no point was converted, every point states the
 * same range in the series unit, and at least one bound exists. Anything else draws no band (never a guess).
 */
fun TrendSeries.referenceBand(): ReferenceBand? {
    if (points.isEmpty() || points.any { it.converted }) return null
    val ranges = points.map { it.observation.referenceRange }
    if (ranges.any { it.unit != null && !it.unit.trim().equals(unit.trim(), ignoreCase = true) }) return null
    val first = ranges.first()
    if (first.lower == null && first.upper == null) return null
    if (ranges.any { it.lower != first.lower || it.upper != first.upper }) return null
    return ReferenceBand(first.lower, first.upper)
}
