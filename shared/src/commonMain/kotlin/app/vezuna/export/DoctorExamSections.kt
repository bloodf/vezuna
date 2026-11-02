package app.vezuna.export

import app.vezuna.domain.UnitNormalizer

data class DoctorExamSection(
    val testName: String,
    val testCode: String,
    /** Unit of [trend] and of every [DoctorChange]. */
    val unit: String,
    val latest: DoctorResultRow,
    val history: List<DoctorResultRow>,
    /** Numeric readings to draw, in the shared original unit when the reports agree; null for text results. */
    val trend: DoctorTrendSeries?,
    val sinceFirst: DoctorChange? = null,
    val sincePrevious: DoctorChange? = null,
) {
    val isText: Boolean get() = latest.qualitativeValue != null
    /** True when [trend] uses the normalized unit because the source reports printed different units. */
    val usesNormalizedUnit: Boolean get() = trend != null && !unit.equals(latest.originalUnit, ignoreCase = true)
    val distinctDates: Int get() = history.map { DoctorReportFormat.date(it.collectedAt) }.distinct().size
}

/** latest minus a baseline, inside the exported series; null on the section means "Not available", never zero. */
data class DoctorChange(val delta: Double, val decimals: Int, val unit: String, val baseline: DoctorTrendPoint)

object DoctorExamSections {
    fun from(document: DoctorPdfDocument): List<DoctorExamSection> = document.resultRows
        .groupBy { row ->
            UnitNormalizer.canonicalTestCode(row.testCode) to row.normalizedUnit.lowercase()
        }
        .map { (_, rows) -> section(document, rows.sortedBy { it.collectedAt }) }
        .sortedWith(compareBy({ it.testName }, { it.testCode }, { it.unit }))

    private fun section(document: DoctorPdfDocument, ordered: List<DoctorResultRow>): DoctorExamSection {
        val latest = ordered.last()
        val canonicalCode = UnitNormalizer.canonicalTestCode(latest.testCode)
        val normalized = document.trends.firstOrNull { trend ->
            UnitNormalizer.canonicalTestCode(trend.testCode) == canonicalCode &&
                trend.unit.equals(latest.normalizedUnit, ignoreCase = true)
        }
        val trend = if (latest.qualitativeValue != null) null else originalUnitSeries(ordered) ?: normalized
        val decimals = ordered.mapNotNull { row -> row.originalValue?.let { DoctorReportFormat.decimalsOf(it, row.valueDecimals) } }.maxOrNull() ?: 0
        // ponytail: local first/previous arithmetic; swap for domain/trends TrendComparison once fix/trends-domain lands.
        fun change(baseline: DoctorTrendPoint?): DoctorChange? {
            val last = trend?.points?.lastOrNull() ?: return null
            if (baseline == null || baseline === last) return null
            return DoctorChange(last.value - baseline.value, decimals, trend.unit, baseline)
        }
        val points = trend?.points.orEmpty()
        return DoctorExamSection(
            testName = latest.testName,
            testCode = latest.testCode,
            unit = trend?.unit ?: latest.normalizedUnit,
            latest = latest,
            history = ordered,
            trend = trend,
            sinceFirst = change(points.firstOrNull()),
            sincePrevious = change(points.getOrNull(points.lastIndex - 1)),
        )
    }

    /** The readings as printed, when every report used the same unit; otherwise the normalized series is drawn. */
    private fun originalUnitSeries(rows: List<DoctorResultRow>): DoctorTrendSeries? {
        val unit = rows.first().originalUnit
        if (rows.any { it.originalValue == null || !it.originalUnit.equals(unit, ignoreCase = true) }) return null
        return DoctorTrendSeries(
            testName = rows.last().testName,
            testCode = rows.last().testCode,
            unit = unit,
            points = rows.map { row ->
                DoctorTrendPoint(row.collectedAt, row.originalValue!!, row.referenceRange.copy(unit = row.referenceRange.unit ?: unit))
            },
        )
    }
}
