package app.vezuna.export

import app.vezuna.domain.UnitNormalizer
import app.vezuna.domain.trends.Granularity
import app.vezuna.domain.trends.PointSource
import app.vezuna.domain.trends.PointSourceKind
import app.vezuna.domain.trends.RawReading
import app.vezuna.domain.trends.TrendComparison
import app.vezuna.domain.trends.TrendEngine
import app.vezuna.domain.trends.TrendRange
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

data class DoctorExamSection(
    val testName: String,
    val testCode: String,
    /** Unit of [trend] and of [comparison]. */
    val unit: String,
    val latest: DoctorResultRow,
    val history: List<DoctorResultRow>,
    /** Numeric readings to draw, in the shared original unit when the reports agree; null for text results. */
    val trend: DoctorTrendSeries?,
    /** First/previous arithmetic from the shared trends engine; null for text results. */
    val comparison: TrendComparison? = null,
    /** Fraction digits to print the comparison with: the most precise reading wins. */
    val decimals: Int = 0,
) {
    val isText: Boolean get() = latest.qualitativeValue != null
    /** True when [trend] uses the normalized unit because the source reports printed different units. */
    val usesNormalizedUnit: Boolean get() = trend != null && !unit.equals(latest.originalUnit, ignoreCase = true)
    val distinctDates: Int get() = history.map { DoctorReportFormat.date(it.collectedAt) }.distinct().size
}

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
        val decimals = if (trend != null && !trend.unit.equals(latest.originalUnit, ignoreCase = true)) {
            trend.points.maxOf { DoctorReportFormat.decimalsOf(it.value, null) }
        } else {
            ordered.mapNotNull { row -> row.originalValue?.let { DoctorReportFormat.decimalsOf(it, row.valueDecimals) } }.maxOrNull() ?: 0
        }
        return DoctorExamSection(
            testName = latest.testName,
            testCode = latest.testCode,
            unit = trend?.unit ?: latest.normalizedUnit,
            latest = latest,
            history = ordered,
            trend = trend,
            comparison = trend?.let { comparison(canonicalCode, it) },
            decimals = decimals,
        )
    }

    /** The exported readings are the whole period (the selection was applied upstream), hence [TrendRange.All]. */
    private fun comparison(code: String, trend: DoctorTrendSeries): TrendComparison {
        val readings = trend.points.mapIndexed { index, point ->
            val date = point.timestamp.toLocalDateTime(TimeZone.UTC).date
            RawReading(
                "$index", date, point.value, point.value, trend.unit, null, point.referenceRange,
                PointSource(PointSourceKind.REPORT), Granularity.READING, index.toString().padStart(6, '0'),
            )
        }
        val today = readings.last().date
        return TrendEngine.series("$code|${trend.unit}", trend.testName, trend.unit, readings, TrendRange.All, today).comparison
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
