package app.vezuna

import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabObservation
import app.vezuna.domain.LabReport
import app.vezuna.domain.LabResultFilter
import app.vezuna.domain.LabResultList
import app.vezuna.domain.ReferenceRange
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class LabResultListTest {
    private val glucose = obs("1", "GLICOSE", "Glicose", 120.0, ReferenceRange(70.0, 99.0), "r1", "2026-03-10T12:00:00Z")
    private val hba1c = obs("2", "HbA1c", "Hemoglobina glicada", 5.5, ReferenceRange(4.0, 5.6), "r2", "2026-05-10T12:00:00Z")
    private val iron = obs("3", "FE", "Ferro sérico", 40.0, ReferenceRange(50.0, 170.0), "r2", "2026-05-10T12:00:00Z")
    private val all = listOf(glucose, hba1c, iron)

    @Test
    fun searchMatchesNameOrCodeIgnoringCaseAndAccents() {
        fun ids(text: String) = LabResultList.filter(all, LabResultFilter(text = text)).map { it.observationId }
        assertEquals(listOf("1", "2"), ids("glic"))
        assertEquals(listOf("2"), ids("hba1c"))
        assertEquals(listOf("2"), ids("HBA 1C"))
        assertEquals(listOf("3"), ids("serico"))
        assertEquals(listOf("1", "2", "3"), ids("  "))
        assertEquals(emptyList(), ids("zzz"))
    }

    @Test
    fun dateRangeIsInclusiveAndOutOfRangeNeedsAKnownRange() {
        val april = Instant.parse("2026-04-01T12:00:00Z")
        assertEquals(listOf("2", "3"), LabResultList.filter(all, LabResultFilter(from = april)).map { it.observationId })
        assertEquals(listOf("1"), LabResultList.filter(all, LabResultFilter(to = april)).map { it.observationId })
        assertEquals(listOf("2", "3"), LabResultList.filter(all, LabResultFilter(from = Instant.parse("2026-05-10T12:00:00Z"), to = Instant.parse("2026-05-10T12:00:00Z"))).map { it.observationId })
        assertEquals(listOf("1", "3"), LabResultList.filter(all, LabResultFilter(outOfRangeOnly = true)).map { it.observationId })
        val noRange = obs("4", "X", "No range", 1.0, ReferenceRange(), "r1", "2026-03-10T12:00:00Z")
        assertEquals(emptyList(), LabResultList.filter(listOf(noRange), LabResultFilter(outOfRangeOnly = true)))
    }

    @Test
    fun groupsByReportNewestFirst() {
        val reports = listOf(LabReport("p", "r1", "d1", importedAt = glucose.collectedAt), LabReport("p", "r2", null, importedAt = hba1c.collectedAt))
        val groups = LabResultList.groupByReport(all, reports)
        assertEquals(listOf("r2", "r1"), groups.map { it.reportId })
        assertEquals(2, groups.first().observations.size)
        assertEquals(true, groups.first().report?.isManual)
        assertEquals(null, LabResultList.groupByReport(listOf(glucose), emptyList()).single().report)
    }

    private fun obs(id: String, code: String, name: String, value: Double, range: ReferenceRange, report: String, at: String) = LabObservation(
        "p", id, report, code, name, value, "g/L", value, "g/L", range, Instant.parse(at), ConfirmationStatus.CONFIRMED,
    )
}
