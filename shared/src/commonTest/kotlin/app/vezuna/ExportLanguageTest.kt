package app.vezuna

import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabObservation
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.RangeAssessment
import app.vezuna.domain.ReferenceRange
import app.vezuna.export.CsvDoctorExporter
import app.vezuna.export.DoctorExportContent
import app.vezuna.export.DoctorReportFormat
import app.vezuna.export.ExportText
import app.vezuna.localization.ContentLanguage
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ExportLanguageTest {
    private val at = Instant.parse("2026-03-05T08:30:00Z")
    private val data = ProfileData(
        Profile("p", "Ana", createdAt = at),
        observations = listOf(
            LabObservation(
                profileId = "p", observationId = "o", reportId = "r", testCode = "HGB", displayName = "Hemoglobina",
                originalValue = 12.5, originalUnit = "g/dL", normalizedValue = 125.0, normalizedUnit = "g/L",
                referenceRange = ReferenceRange(12.0, 15.5, unit = "g/dL"), collectedAt = at, confirmation = ConfirmationStatus.CONFIRMED,
            ),
        ),
    )

    @Test
    fun numbersFollowTheExportLanguageButUnitsStay() {
        assertEquals("12.5", DoctorReportFormat.value(12.5, ContentLanguage.EN))
        assertEquals("12,5", DoctorReportFormat.value(12.5, ContentLanguage.PT))
        assertEquals("12,5", DoctorReportFormat.value(12.5, ContentLanguage.ES))
        assertEquals("12–15,5 g/dL", DoctorExportContent.rangeText(ReferenceRange(12.0, 15.5, unit = "g/dL"), ContentLanguage.PT))
        assertEquals("12–15.5 g/dL", DoctorExportContent.rangeText(ReferenceRange(12.0, 15.5, unit = "g/dL")))
    }

    @Test
    fun datesAndStatusesAreLocalizedPerLanguage() {
        val march = Instant.parse("2026-02-05T08:30:00Z")
        assertEquals("05 Feb 2026", DoctorReportFormat.date(march, ContentLanguage.EN))
        assertEquals("05 Fev 2026", DoctorReportFormat.date(march, ContentLanguage.PT))
        assertEquals("05 Feb 2026", DoctorReportFormat.date(march, ContentLanguage.ES))
        assertEquals("05 Dic 2026", DoctorReportFormat.date(Instant.parse("2026-12-05T00:00:00Z"), ContentLanguage.ES))
        assertEquals("Above reference", DoctorReportFormat.assessment(RangeAssessment.ABOVE, ContentLanguage.EN))
        assertEquals("Acima da referência", DoctorReportFormat.assessment(RangeAssessment.ABOVE, ContentLanguage.PT))
        assertEquals("Por encima de la referencia", DoctorReportFormat.assessment(RangeAssessment.ABOVE, ContentLanguage.ES))
    }

    @Test
    fun everyLanguageDefinesEveryTextAndTheyDiffer() {
        val en = ExportText.of(ContentLanguage.EN)
        listOf(ContentLanguage.PT, ContentLanguage.ES).forEach { language ->
            val other = ExportText.of(language)
            assertEquals(12, other.months.size)
            assertTrue(other.disclaimer.isNotBlank() && other.csvHeader.isNotBlank())
            assertNotEquals(en.disclaimer, other.disclaimer)
            assertNotEquals(en.csvHeader, other.csvHeader)
            assertNotEquals(en.withinReference, other.withinReference)
            assertEquals(13, other.csvHeader.split(",").size)
        }
    }

    @Test
    fun documentCarriesLanguageAndLocalizedNotes() {
        val pt = DoctorExportContent.document(data, language = ContentLanguage.PT)
        assertEquals(ContentLanguage.PT, pt.language)
        val notes = pt.lines.joinToString("\n")
        assertTrue(notes.contains("Perfil: Ana"))
        assertTrue(notes.contains("Resultados laboratoriais confirmados"))
        assertTrue(notes.contains("125,0 g/L"))
        assertTrue(DoctorExportContent.document(data).lines.joinToString("\n").contains("125.0 g/L"))
    }

    @Test
    fun csvHeadersLocalizeButNumbersStayMachineReadable() {
        ContentLanguage.entries.forEach { language ->
            val csv = CsvDoctorExporter().export(data, language).lines()
            assertEquals(ExportText.of(language).csvHeader, csv.first())
            val row = csv[1]
            assertTrue(row.contains("\"12.5\""), "$language: canonical decimal point")
            assertTrue(row.contains("\"125.0\""))
            assertTrue(row.contains("2026-03-05T08:30:00Z"))
            assertTrue(row.contains("\"g/dL\"") && row.contains("\"g/L\""), "units unchanged")
        }
    }

    @Test
    fun changeContextAndPeriodAreLocalized() {
        val rows = DoctorExportContent.document(data, language = ContentLanguage.ES).resultRows
        assertEquals("05 Mar 2026", DoctorReportFormat.period(rows, ContentLanguage.ES))
        assertEquals("Ningún resultado confirmado", DoctorReportFormat.period(emptyList(), ContentLanguage.ES))
        assertEquals("Último resultado confirmado", DoctorReportFormat.changeContext(DoctorExportContent.trends(data).single(), ContentLanguage.PT))
    }
}
