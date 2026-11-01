package app.vezuna.export

import app.vezuna.domain.ReferenceRange
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmPdfExporterTest {
    @Test
    fun longUnicodeContentPaginatesWithoutTruncationAndChartsRemainVector() {
        val longToken = "X".repeat(500)
        val lines = buildList {
            add("Profile: Ana Ribeiro — Medicação")
            add(longToken)
            repeat(120) { index -> add("Registro laboratorial $index com valores e unidades para verificar a paginação.") }
        }
        val document = DoctorPdfDocument(
            title = "Relatório médico",
            lines = lines,
            trends = listOf(
                DoctorTrendSeries(
                    testName = "Hemoglobina",
                    testCode = "HGB",
                    unit = "g/L",
                    points = listOf(110.0, 120.0, 130.0).mapIndexed { index, value ->
                        DoctorTrendPoint(
                            timestamp = Instant.parse("2026-02-0${index + 1}T08:30:00Z"),
                            value = value,
                            referenceRange = ReferenceRange(100.0 + index, 140.0 + index, unit = "g/L"),
                        )
                    },
                ),
            ),
            generatedAt = Instant.parse("2026-02-04T08:30:00Z"),
        )
        val bytes = NativePdfExporter().export(document).bytes
        val pdf = String(bytes, Charsets.ISO_8859_1)
        assertTrue(pdf.startsWith("%PDF-1.4"))
        val pageCount = pdf.split("/Type /Page /Parent").size - 1
        assertTrue(pageCount >= 2, "Expected multiple pages, found $pageCount")
        assertTrue(pdf.contains("Ana Ribeiro"))
        assertTrue(pdf.contains("Medicação"))
        assertTrue(pdf.contains("Registro laboratorial 119"))
        assertTrue(pdf.contains(" c f\n"))
        assertTrue(pdf.contains(" RG "))
        assertEquals(500, pdf.count { it == 'X' })
    }
}

class JvmPdfExporterLanguageTest {
    private val range = ReferenceRange(12.0, 15.5, unit = "g/dL")
    private fun row() = DoctorResultRow(
        Instant.parse("2026-03-05T08:30:00Z"), "Hemoglobina", "HGB", 17.5, "g/dL", 175.0, "g/L", range,
        app.vezuna.domain.RangeAssessment.ABOVE,
    )

    private fun pdf(language: app.vezuna.localization.ContentLanguage): String = String(
        NativePdfExporter().export(
            DoctorPdfDocument(
                title = "t", lines = emptyList(), profileDisplayName = "Ana", resultRows = listOf(row()),
                medicationRows = listOf(DoctorMedicationRow("Metformina", null, null)),
                generatedAt = Instant.parse("2026-03-06T10:00:00Z"), language = language,
            ),
        ).bytes,
        Charsets.ISO_8859_1,
    )

    @Test
    fun pdfHeadingsStatusesAndNumbersFollowTheExportLanguage() {
        val en = pdf(app.vezuna.localization.ContentLanguage.EN)
        assertTrue(en.contains("(Above reference)") && en.contains("Generated 06 Mar 2026") && en.contains("(17.5 g/dL)"))
        val pt = pdf(app.vezuna.localization.ContentLanguage.PT)
        assertTrue(pt.contains("(Acima da refer") && pt.contains("Gerado em 06 Mar 2026") && pt.contains("(Medicamentos)") && pt.contains("(17,5 g/dL)"))
        assertTrue(!pt.contains("(Above reference)") && !pt.contains("(Medications)"))
        val es = pdf(app.vezuna.localization.ContentLanguage.ES)
        assertTrue(es.contains("(Por encima de la referencia)") && es.contains("Generado el 06 Mar 2026") && es.contains("(17,5 g/dL)"))
    }
}
