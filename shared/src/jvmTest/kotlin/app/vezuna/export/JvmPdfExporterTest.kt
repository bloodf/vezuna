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
