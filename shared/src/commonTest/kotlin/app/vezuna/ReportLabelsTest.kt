package app.vezuna

import app.vezuna.domain.LabReport
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.SourceDocument
import app.vezuna.ui.reportDate
import app.vezuna.ui.reportDisplayName
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ReportLabelsTest {
    private val imported = Instant.parse("2026-05-01T12:00:00Z")
    private val ordered = Instant.parse("2026-04-20T12:00:00Z")
    private val document = SourceDocument("p", "d1", "laudo.pdf", "application/pdf", "hash", imported)
    private val data = ProfileData(Profile("p", "Ana", createdAt = imported), documents = listOf(document))

    @Test
    fun laboratoryNameWinsThenFileNameThenManual() {
        val named = LabReport("p", "r1", "d1", laboratoryName = "Lab Central", orderedAt = ordered, importedAt = imported)
        val unnamed = named.copy(laboratoryName = " ")
        val manual = LabReport("p", "r2", null, importedAt = imported)
        assertEquals("Lab Central", reportDisplayName(named, data, "Manual"))
        assertEquals("laudo.pdf", reportDisplayName(unnamed, data, "Manual"))
        assertEquals("Manual", reportDisplayName(manual, data, "Manual"))
        assertEquals("Manual", reportDisplayName(null, data, "Manual"))
    }

    @Test
    fun orderDateWinsOverImportDate() {
        assertEquals(ordered, reportDate(LabReport("p", "r1", "d1", orderedAt = ordered, importedAt = imported)))
        assertEquals(imported, reportDate(LabReport("p", "r1", "d1", importedAt = imported)))
    }
}
