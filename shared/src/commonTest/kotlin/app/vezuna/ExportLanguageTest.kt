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
import kotlin.time.Instant
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
    fun csvKeepsTypedNegativeNumbersAndGuardsNumericLookingText() {
        val observation = data.observations.single().copy(
            testCode = "-2.0", displayName = "-2.0", originalValue = -2.0,
            normalizedValue = -20.0, valueDecimals = 2,
            referenceRange = ReferenceRange(-30.5, -1.0, unit = "-2.0", text = "-2.0"),
            qualitativeValue = null,
        )
        val row = CsvDoctorExporter().export(data.copy(observations = listOf(observation))).lines()[1]
        assertEquals(
            "\"p\",\"2026-03-05T08:30:00Z\",\"'-2.0\",\"'-2.0\",\"-2.00\",\"g/dL\",\"-20\",\"g/L\",\"-30.5\",\"-1\",\"'-2.0\",\"'-2.0\",\"\"",
            row,
        )
    }

    @Test
    fun csvGuardsFormulaPrefixesAndLeadingControlsInText() {
        val exporter = CsvDoctorExporter()
        listOf("=1+1", "+1", "-2.0", "@SUM(A1)", "  =1", "\tplain", "\rplain", "\t=1", "\r=1", "＝1", "＋1", "－1", "＠1").forEach { raw ->
            assertEquals("\"'$raw\"", exporter.escape(raw))
        }
        assertEquals("\"a,\"\"b\"\"\"", exporter.escape("a,\"b\""))
        assertEquals("\"2.0\"", exporter.escape("2.0"))
    }

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
        assertEquals("Above report range", DoctorReportFormat.assessment(RangeAssessment.ABOVE, ContentLanguage.EN))
        assertEquals("Acima da faixa do laudo", DoctorReportFormat.assessment(RangeAssessment.ABOVE, ContentLanguage.PT))
        assertEquals("Por encima del rango del informe", DoctorReportFormat.assessment(RangeAssessment.ABOVE, ContentLanguage.ES))
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
        assertTrue(notes.contains("125 g/L"))
        assertTrue(DoctorExportContent.document(data).lines.joinToString("\n").contains("125 g/L"))
    }

    @Test
    fun csvHeadersLocalizeButNumbersStayMachineReadable() {
        ContentLanguage.entries.forEach { language ->
            val csv = CsvDoctorExporter().export(data, language).lines()
            assertEquals(ExportText.of(language).csvHeader, csv.first())
            val row = csv[1]
            assertTrue(row.contains("\"12.5\""), "$language: canonical decimal point")
            assertTrue(row.contains("\"125\""))
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
