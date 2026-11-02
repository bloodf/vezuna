package app.vezuna.domain.trends

import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabObservation
import app.vezuna.domain.LabReport
import app.vezuna.domain.SourceDocument
import app.vezuna.domain.UnitNormalizer
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Entry point for lab trend screens and the PDF history report.
 * Only CONFIRMED observations are used. Compatibility comes from the existing [UnitNormalizer]
 * (same canonical test code and same normalized unit); anything else becomes a separate series.
 */
object LabTrends {
    fun forProfile(
        profileId: String,
        observations: List<LabObservation>,
        reports: List<LabReport>,
        documents: List<SourceDocument>,
        range: TrendRange,
        today: LocalDate,
    ): List<TestTrend> = observations
        .filter { it.profileId == profileId && it.confirmation == ConfirmationStatus.CONFIRMED }
        .groupBy { UnitNormalizer.canonicalTestCode(it.testCode) }
        .map { (code, rows) -> build(code, rows, reports, documents, range, today) }
        .sortedBy { it.displayName.lowercase() }

    fun forTest(
        profileId: String,
        testCode: String,
        observations: List<LabObservation>,
        reports: List<LabReport>,
        documents: List<SourceDocument>,
        range: TrendRange,
        today: LocalDate,
    ): TestTrend? {
        val code = UnitNormalizer.canonicalTestCode(testCode)
        val rows = observations.filter {
            it.profileId == profileId && it.confirmation == ConfirmationStatus.CONFIRMED &&
                UnitNormalizer.canonicalTestCode(it.testCode) == code
        }
        return if (rows.isEmpty()) null else build(code, rows, reports, documents, range, today)
    }

    private fun build(
        code: String,
        rows: List<LabObservation>,
        reports: List<LabReport>,
        documents: List<SourceDocument>,
        range: TrendRange,
        today: LocalDate,
    ): TestTrend {
        val latest = rows.maxWith(compareBy({ it.collectedAt }, { it.observationId }))
        val reportsById = reports.associateBy { it.reportId }
        val docsById = documents.associateBy { it.documentId }
        fun sourceOf(o: LabObservation): PointSource {
            val report = reportsById[o.reportId]
            val doc = report?.sourceDocumentId?.let { docsById[it] }
            return when {
                report == null -> PointSource(PointSourceKind.REPORT, null, o.reportId)
                report.isManual -> PointSource(PointSourceKind.MANUAL, report.laboratoryName, report.reportId)
                else -> PointSource(PointSourceKind.REPORT, report.laboratoryName ?: doc?.displayName, report.reportId, report.sourceDocumentId)
            }
        }
        fun dateOf(o: LabObservation) = o.collectedAt.toLocalDateTime(TimeZone.UTC).date

        val text = rows.filter { it.isQualitative }.sortedWith(compareBy({ it.collectedAt }, { it.observationId })).map {
            TextEvent(it.observationId, dateOf(it), noonUtc(dateOf(it)), it.qualitativeValue!!, sourceOf(it))
        }
        val window = range.window(today, text.firstOrNull()?.let { DateWindow(it.date, text.last().date) })
        val textInRange = if (window == null) emptyList() else text.filter { it.date in window.start..window.end }

        val series = rows.filter { !it.isQualitative }
            .groupBy { it.normalizedUnit.trim().lowercase() }
            .map { (unitKey, group) ->
                // Plot in the recorded unit when every reading used it; otherwise in the shared normalized unit. Never relabel.
                val originals = group.map { it.originalUnit.trim().lowercase() }.distinct()
                val useOriginal = originals.size == 1
                val unit = if (useOriginal) group.first().originalUnit.trim() else group.first().normalizedUnit
                val raw = group.map {
                    RawReading(
                        it.observationId, dateOf(it),
                        if (useOriginal) it.originalValue else it.normalizedValue,
                        it.originalValue, it.originalUnit, it.valueDecimals,
                        it.referenceRange, sourceOf(it), Granularity.READING, it.observationId,
                    )
                }
                TrendEngine.series("$code|$unitKey", latest.displayName, unit, raw, range, today)
            }
            // The unit of the most recent reading comes first.
            .sortedWith(compareByDescending<HistorySeries> { it.allPoints.last().at }.thenBy { it.unit })
        return TestTrend(code, latest.displayName, series, textInRange, text)
    }
}
