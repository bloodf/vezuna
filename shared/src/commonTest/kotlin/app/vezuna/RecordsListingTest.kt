package app.vezuna

import app.vezuna.domain.LabResultFilter
import app.vezuna.ui.DesignFixture
import app.vezuna.ui.records.RecordsListing
import app.vezuna.ui.records.ReportEntry
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R13 local search/filter and report grouping, on the synthetic DATA_AND_COPY household. */
class RecordsListingTest {
    private val data = DesignFixture.data()
    private val names = { entry: ReportEntry -> listOfNotNull(entry.document?.displayName) }

    private fun reportIds(filter: LabResultFilter) = RecordsListing.reports(data, filter, names).map { it.report.reportId }

    @Test
    fun noFilterListsEveryReportNewestFirstAndOnlyConfirmedResults() {
        assertEquals(
            listOf("report-cbc-oct", "report-hbsag-oct", "report-thyroid-oct", "report-lipid-oct", "report-jul", "report-apr"),
            reportIds(LabResultFilter()),
        )
        val results = RecordsListing.results(data, LabResultFilter())
        assertEquals(8, results.size)
        assertTrue(results.none { it.observationId.startsWith("candidate") })
    }

    @Test
    fun searchMatchesResultsAndReportsWithThoseResults() {
        val filter = LabResultFilter(text = "hemoglobin")
        assertEquals(listOf("hgb-2026-10", "hgb-2026-07", "hgb-2026-04"), RecordsListing.results(data, filter).map { it.observationId })
        assertEquals(listOf("report-cbc-oct", "report-jul", "report-apr"), reportIds(filter))
    }

    @Test
    fun searchMatchesAReportByItsFileName() {
        assertEquals(listOf("report-lipid-oct"), reportIds(LabResultFilter(text = "lipid-panel")))
    }

    @Test
    fun noMatchIsEmptyAndClearingRestoresEveryRow() {
        val none = LabResultFilter(text = "ferritin zz")
        assertTrue(reportIds(none).isEmpty())
        assertTrue(RecordsListing.results(data, none).isEmpty())
        assertEquals(6, reportIds(LabResultFilter()).size)
        assertEquals(8, RecordsListing.results(data, LabResultFilter()).size)
    }

    @Test
    fun outOfRangeAndDatesNarrowBothViews() {
        val outOfRange = LabResultFilter(outOfRangeOnly = true)
        assertEquals(listOf("ldl-2026-10", "ldl-2026-07", "ldl-2026-04"), RecordsListing.results(data, outOfRange).map { it.observationId })
        val october = LabResultFilter(from = Instant.parse("2026-10-01T12:00:00Z"), to = Instant.parse("2026-10-31T12:00:00Z"))
        assertEquals(listOf("report-cbc-oct", "report-hbsag-oct", "report-thyroid-oct", "report-lipid-oct"), reportIds(october))
        // A name hit still has to pass the other filters.
        assertTrue(reportIds(LabResultFilter(text = "april", outOfRangeOnly = true)).contains("report-apr"))
        assertTrue(reportIds(LabResultFilter(text = "thyroid", outOfRangeOnly = true)).isEmpty())
    }

    @Test
    fun groupsConsecutiveCalendarMonths() {
        val months = RecordsListing.byMonth(RecordsListing.reports(data, LabResultFilter(), names)) { it.date }
        assertEquals(listOf(4, 1, 1), months.map { it.second.size })
    }

    @Test
    fun reportEntryKeepsDraftsApartFromConfirmedResults() {
        val withDrafts = data.copy(
            reports = data.reports + app.vezuna.domain.LabReport(DesignFixture.PROFILE_ID, "report-review", "doc-october-review", importedAt = Instant.parse("2026-11-01T12:10:00Z")),
        )
        val entry = RecordsListing.entry(withDrafts, "report-review")!!
        assertTrue(entry.confirmed.isEmpty())
        assertEquals(3, entry.pending.size)
        val impact = RecordsListing.deletionImpact(withDrafts, "report-review")
        assertEquals(0, impact.confirmedResults)
        assertEquals(3, impact.drafts)
        assertTrue(impact.removesFile)
        assertEquals(2, RecordsListing.deletionImpact(data, "report-apr").confirmedResults)
    }

    @Test
    fun historyAndChangeAreArithmeticInTheSeriesUnit() {
        val latest = data.observations.first { it.observationId == "hgb-2026-10" }
        val all = RecordsListing.history(data, latest, null)!!
        assertEquals(3, all.points.size)
        val change = RecordsListing.changeSincePrevious(all, "hgb-2026-10")!!
        assertEquals("g/dL", change.unit)
        assertEquals(0.4, change.delta, 1e-9)
        assertEquals(1, change.decimals)
        assertEquals(Instant.parse("2026-07-15T11:00:00Z"), change.since)
        assertEquals(2, RecordsListing.history(data, latest, 6)!!.points.size)
        assertNull(RecordsListing.changeSincePrevious(all, "hgb-2026-04"))
        assertNull(RecordsListing.history(data, data.observations.first { it.isQualitative }, null))
    }
}
