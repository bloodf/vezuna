package app.vezuna.domain

import app.vezuna.importer.foldedText
import kotlinx.datetime.Instant

/** Search text matches display name or test code, ignoring case and accents; dates are inclusive calendar bounds. */
data class LabResultFilter(
    val text: String = "",
    val from: Instant? = null,
    val to: Instant? = null,
    val outOfRangeOnly: Boolean = false,
) {
    val isActive: Boolean get() = text.isNotBlank() || from != null || to != null || outOfRangeOnly
}

/** Results of one report, newest first; [report] is null when the stored report is missing. */
data class ReportGroup(val reportId: String, val report: LabReport?, val observations: List<LabObservation>)

object LabResultList {
    fun filter(observations: List<LabObservation>, filter: LabResultFilter): List<LabObservation> =
        observations.filter { matches(it, filter) }

    fun matches(observation: LabObservation, filter: LabResultFilter): Boolean {
        val query = foldedText(filter.text.trim())
        if (query.isNotEmpty()) {
            val canonicalQuery = UnitNormalizer.canonicalTestCode(filter.text)
            val named = foldedText(observation.displayName).contains(query) || foldedText(observation.testCode).contains(query)
            val coded = canonicalQuery.isNotEmpty() && UnitNormalizer.canonicalTestCode(observation.testCode).contains(canonicalQuery)
            if (!named && !coded) return false
        }
        if (filter.from != null && observation.collectedAt < filter.from) return false
        if (filter.to != null && observation.collectedAt > filter.to) return false
        if (filter.outOfRangeOnly) {
            val assessment = LabComparator.assess(observation)
            if (assessment != RangeAssessment.BELOW && assessment != RangeAssessment.ABOVE) return false
        }
        return true
    }

    /** Groups by report, newest result date first; each group lists its results newest first. */
    fun groupByReport(observations: List<LabObservation>, reports: List<LabReport>): List<ReportGroup> =
        observations.groupBy { it.reportId }
            .map { (reportId, rows) ->
                ReportGroup(reportId, reports.firstOrNull { it.reportId == reportId }, rows.sortedByDescending { it.collectedAt })
            }
            .sortedWith(compareByDescending<ReportGroup> { it.observations.first().collectedAt }.thenBy { it.reportId })
}
