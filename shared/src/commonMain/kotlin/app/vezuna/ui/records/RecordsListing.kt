package app.vezuna.ui.records

import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabObservation
import app.vezuna.domain.LabReport
import app.vezuna.domain.LabResultFilter
import app.vezuna.domain.LabResultList
import app.vezuna.domain.OcrCandidate
import app.vezuna.domain.ProfileData
import app.vezuna.domain.SourceDocument
import app.vezuna.domain.TrendPreparation
import app.vezuna.domain.TrendSeries
import app.vezuna.domain.withoutReport
import app.vezuna.importer.foldedText
import app.vezuna.ui.reportDate
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/** One report as Records shows it: its confirmed results and its drafts, kept apart. */
data class ReportEntry(
    val report: LabReport,
    val document: SourceDocument?,
    val confirmed: List<LabObservation>,
    val pending: List<OcrCandidate>,
    val rejected: List<OcrCandidate>,
) {
    val date: Instant get() = reportDate(report)
}

/** [delta] in [unit] against the result collected on [since]; [decimals] is the printed precision when known. */
data class ResultChange(val delta: Double, val unit: String, val since: Instant, val decimals: Int?)

/** What deleting a report removes (the repository cascade), so the confirmation can name it. */
data class ReportDeletionImpact(val confirmedResults: Int, val drafts: Int, val removesFile: Boolean)

/** Pure list logic for the Records screens; the search itself is [LabResultList] from the domain. */
object RecordsListing {
    fun confirmed(data: ProfileData): List<LabObservation> =
        data.observations.filter { it.confirmation == ConfirmationStatus.CONFIRMED }.sortedByDescending { it.collectedAt }

    fun entry(data: ProfileData, reportId: String): ReportEntry? {
        val report = data.reports.firstOrNull { it.reportId == reportId } ?: return null
        val candidates = report.sourceDocumentId?.let { id -> data.ocrCandidates.filter { it.sourceDocumentId == id } }.orEmpty()
        return ReportEntry(
            report = report,
            document = data.documents.firstOrNull { it.documentId == report.sourceDocumentId },
            confirmed = confirmed(data).filter { it.reportId == reportId },
            pending = candidates.filter { it.status == ConfirmationStatus.UNREVIEWED },
            rejected = candidates.filter { it.status == ConfirmationStatus.REJECTED },
        )
    }

    /**
     * Reports newest first. With an active filter a report stays when one of its confirmed results matches, or when
     * the query names the report ([namesOf]: laboratory and file name) and its results pass the other filters.
     */
    fun reports(data: ProfileData, filter: LabResultFilter, namesOf: (ReportEntry) -> List<String>): List<ReportEntry> =
        data.reports.mapNotNull { entry(data, it.reportId) }
            .filter { includes(it, filter, namesOf(it)) }
            .sortedWith(compareByDescending<ReportEntry> { it.date }.thenByDescending { it.report.importedAt }.thenBy { it.report.reportId })

    fun results(data: ProfileData, filter: LabResultFilter): List<LabObservation> = LabResultList.filter(confirmed(data), filter)

    private fun includes(entry: ReportEntry, filter: LabResultFilter, names: List<String>): Boolean {
        if (!filter.isActive) return true
        if (LabResultList.filter(entry.confirmed, filter).isNotEmpty()) return true
        val query = foldedText(filter.text.trim())
        if (query.isEmpty() || names.none { foldedText(it).contains(query) }) return false
        val others = filter.copy(text = "")
        return !others.isActive || LabResultList.filter(entry.confirmed, others).isNotEmpty()
    }

    /** Consecutive runs of the same calendar month (dates are noon-UTC days), in the given order. */
    fun <T> byMonth(items: List<T>, dateOf: (T) -> Instant): List<Pair<Instant, List<T>>> {
        val groups = mutableListOf<Pair<Instant, MutableList<T>>>()
        items.forEach { item ->
            val date = dateOf(item)
            val last = groups.lastOrNull()
            if (last != null && monthOf(last.first) == monthOf(date)) last.second += item else groups += date to mutableListOf(item)
        }
        return groups
    }

    fun deletionImpact(data: ProfileData, reportId: String): ReportDeletionImpact {
        val after = data.withoutReport(reportId)
        return ReportDeletionImpact(
            confirmedResults = data.observations.size - after.observations.size,
            drafts = data.ocrCandidates.count { it.status != ConfirmationStatus.CONFIRMED } -
                after.ocrCandidates.count { it.status != ConfirmationStatus.CONFIRMED },
            removesFile = after.documents.size < data.documents.size,
        )
    }

    /** The test's compatible confirmed history (in the newest result's unit), cut to the last [months] when set. */
    fun history(data: ProfileData, observation: LabObservation, months: Int?): TrendSeries? {
        if (observation.isQualitative) return null
        val series = TrendPreparation.forTest(data.profile.profileId, observation.testCode, data.observations) ?: return null
        val latest = series.points.lastOrNull()?.observation?.collectedAt ?: return null
        val start = months?.let { latest.minus(it, DateTimeUnit.MONTH, TimeZone.UTC) } ?: return series
        return series.copy(points = series.points.filter { it.observation.collectedAt >= start })
    }

    /** Arithmetic difference from the preceding compatible result (latest minus previous), never a judgement. */
    fun changeSincePrevious(series: TrendSeries, observationId: String): ResultChange? {
        val index = series.points.indexOfFirst { it.observation.observationId == observationId }
        if (index < 1) return null
        val current = series.points[index]
        val previous = series.points[index - 1]
        val decimals = if (current.converted || previous.converted) null
        else listOfNotNull(current.observation.valueDecimals, previous.observation.valueDecimals).maxOrNull()
        return ResultChange(current.y - previous.y, series.unit, previous.observation.collectedAt, decimals)
    }

    private fun monthOf(instant: Instant) = instant.toLocalDateTime(TimeZone.UTC).let { it.year * 12 + it.month.ordinal }
}
