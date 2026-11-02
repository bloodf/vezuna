package app.vezuna.export

import app.vezuna.domain.RangeAssessment
import app.vezuna.domain.ReferenceRange
import app.vezuna.localization.ContentLanguage
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.COSNumber
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Structure checks on the real JVM PDF bytes: extracted text, fonts, sizes, colors and pagination. */
class JvmPdfExporterTest {
    private val canonical = PdfFixtures.pdfBytes(PdfFixtures.document())

    @Test
    fun canonicalHistoryPrintsEveryValueComparisonAndScopeAsText() {
        // Wrapped cells break lines; compare on single-spaced text.
        val text = PdfFixtures.pageTexts(canonical).joinToString(" ").replace(Regex("\\s+"), " ")
        listOf(
            "Laboratory history", "Ana Silva", "Date of birth: 18 May 1988",
            "Collection period: 12 Apr 2026 – 28 Oct 2026",
            "Confirmed results included: 8 · Tests: 4", "Unreviewed imported values excluded: 3",
            "Latest: 13.2 g/dL", "13.0 g/dL", "12.8 g/dL", "Recorded reference: 12.0–16.0 g/dL", "Within report range",
            "+0.2 g/dL (from 12 Apr 2026)", "+0.4 g/dL (from 15 Jul 2026)",
            "Latest: 132 mg/dL", "Recorded reference: Under 130 mg/dL", "Above report range",
            "-14 mg/dL (from 12 Apr 2026)", "-8 mg/dL (from 15 Jul 2026)",
            "2.1 mIU/L", "One recorded result; a change comparison is not available.",
            "Not detected", "Reference not assessed", "Text result, shown as recorded",
            "April blood work", "Lipid panel", "Vitamin D", "Strength: 1,000 IU", "Instructions: 1 capsule", "Scheduled times: 08:00",
            "Dots are recorded results", "Generated 01 Nov 2026, 12:41 UTC",
        ).forEach { assertTrue(it in text, "missing from extracted text: $it") }
        assertFalse("Marker" in text || "marker" in text, "reports say Test, not Marker")
        assertFalse("Fasting glucose" in text || "Ferritin" in text, "unreviewed values must not be printed")
        assertFalse(Regex("\\b0 mg|\\b0\\.0\\b").containsMatchIn(text), "qualitative placeholders must never print as zero")
    }

    @Test
    fun everyPageHasFooterWithTotalAndContinuationRepeatsTitleAndProfile() {
        val pages = PdfFixtures.pageTexts(canonical)
        assertTrue(pages.size >= 2)
        pages.forEachIndexed { index, page ->
            assertTrue("Page ${index + 1} of ${pages.size}" in page, "footer on page ${index + 1}")
            assertTrue("Vezuna · Private health record" in page)
            if (index > 0) assertTrue("Laboratory history, continued" in page && "Ana Silva" in page, "continuation header on page ${index + 1}")
        }
    }

    @Test
    fun bodyTextIsAtLeastElevenPointAndOnlyTheFooterIsSmaller() {
        listOf(canonical, PdfFixtures.pdfBytes(PdfFixtures.document(PdfFixtures.longHistory()))).forEach { bytes ->
            positions(bytes).forEach { (pageHeight, position) ->
                val size = position.fontSizeInPt
                val fromBottom = pageHeight - position.yDirAdj
                if (fromBottom > 25 * PdfStyle.MM) {
                    assertTrue(size >= 11f, "'${position.unicode}' at ${size}pt above the footer")
                } else assertTrue(size >= 9f, "footer text below 9pt")
            }
        }
    }

    @Test
    fun longHistoryPaginatesWithRepeatedTableHeadersAndKeepsEveryReading() {
        val bytes = PdfFixtures.pdfBytes(PdfFixtures.document(PdfFixtures.longHistory(120)))
        val pages = PdfFixtures.pageTexts(bytes)
        assertTrue(pages.size >= 5, "120 rows at 11 pt need several pages, got ${pages.size}")
        val tablePages = pages.drop(1).filter { "results, continued" in it }
        assertTrue(tablePages.size >= 3, "continued table pages")
        tablePages.forEach { assertTrue("Date" in it && "Result" in it && "Recorded reference" in it && "Source" in it, "header repeated") }
        val all = pages.joinToString("\n")
        assertEquals(120, Regex("\\d\\d \\w{3} 20\\d\\d\\s+1[2-3]\\.\\d g/dL").findAll(all).count(), "every reading is a table row")
        assertTrue("Very long medication name that wraps" in all && "who recorded it" in all, "long text wraps instead of truncating")
        assertTrue("Some labels are left out for space; every value and date is in the table." in all)
    }

    @Test
    fun portugueseLocalizesLabelsDatesAndDecimalsButKeepsSourceText() {
        val text = PdfFixtures.pageTexts(PdfFixtures.pdfBytes(PdfFixtures.document(language = ContentLanguage.PT))).joinToString("\n")
        listOf(
            "Histórico laboratorial", "Período de coleta: 12 Abr 2026 – 28 Out 2026", "13,2 g/dL", "+0,2 g/dL (desde 12 Abr 2026)",
            "+0,4 g/dL (desde 15 Jul 2026)", "-14 mg/dL", "Hemoglobin", "LDL cholesterol", "Under 130 mg/dL",
            "Dentro da faixa do laudo", "Acima da faixa do laudo", "Alteração desde o primeiro resultado", "Página 1 de",
        ).forEach { assertTrue(it in text, "missing PT text: $it") }
        assertFalse("Within report range" in text)
        val es = PdfFixtures.pageTexts(PdfFixtures.pdfBytes(PdfFixtures.document(language = ContentLanguage.ES))).joinToString("\n")
        assertTrue("Historial de laboratorio" in es && "Dentro del rango del informe" in es && "13,2 g/dL" in es)
    }

    @Test
    fun letterGrayscaleUsesLetterPagesAndOnlyGrayColors() {
        val bytes = PdfFixtures.pdfBytes(PdfFixtures.document(options = ExportOptions(paper = PaperSize.LETTER, grayscale = true)))
        PdfFixtures.open(bytes) { pdf ->
            pdf.pages.forEach { page ->
                assertEquals(612f, page.mediaBox.width)
                assertEquals(792f, page.mediaBox.height)
                val tokens = PDFStreamParser(page).parse()
                tokens.forEachIndexed { index, token ->
                    if (token is Operator && token.name in setOf("rg", "RG")) {
                        val (r, g, b) = tokens.subList(index - 3, index).map { (it as COSNumber).floatValue() }
                        assertTrue(r == g && g == b, "non-gray color $r $g $b")
                    }
                }
            }
        }
        val text = PdfFixtures.pageTexts(bytes).joinToString("\n")
        assertTrue("Within report range" in text && "Above report range" in text, "status stays words in grayscale")
    }

    @Test
    fun textIsRealTextWithEmbeddedFontsAndNoRasterImages() {
        PdfFixtures.open(canonical) { pdf ->
            assertEquals("en", pdf.documentCatalog.language)
            assertEquals("Laboratory history", pdf.documentInformation.title)
            pdf.pages.forEach { page ->
                val resources = page.resources
                assertTrue(resources.xObjectNames.none { resources.getXObject(it) is PDImageXObject }, "no raster images")
                resources.fontNames.forEach { name ->
                    val font = resources.getFont(name)
                    assertTrue(font is PDType0Font && font.isEmbedded && "NotoSans" in font.name, "embedded Noto Sans, got ${font.name}")
                }
            }
        }
    }

    @Test
    fun sparseSelectionSaysSoWithoutChartsOrZeros() {
        val options = ExportOptions(from = LocalDate(2026, 3, 1), to = LocalDate(2026, 3, 31), includeMedicationsPdf = false)
        val pages = PdfFixtures.pageTexts(PdfFixtures.pdfBytes(PdfFixtures.document(options = options)))
        val text = pages.joinToString("\n")
        assertEquals(1, pages.size)
        listOf(
            "Selected period: 01 Mar 2026 – 31 Mar 2026", "No confirmed results in this period.",
            "Available laboratory history: 12 Apr 2026 – 28 Oct 2026", "Not selected", "Confirmed results included: 0",
        ).forEach { assertTrue(it in text, "missing: $it") }
        assertFalse("How to read the graphs" in text)
    }

    @Test
    fun singleSelectedTestKeepsComparisonsAndFileNameIsMeaningful() {
        val document = PdfFixtures.document(options = ExportOptions(testCodes = setOf("HGB")))
        val exported = NativePdfExporter().export(document)
        assertEquals("vezuna-ana-silva-lab-history-2026-11-01.pdf", exported.fileName)
        val text = PdfFixtures.pageTexts(exported.bytes).joinToString("\n")
        assertTrue("+0.4 g/dL (from 15 Jul 2026)" in text && "Tests: 1" in text)
        assertFalse("LDL cholesterol" in text)
    }

    @Test
    fun linesOnlyDocumentsStillPaginateWithoutTruncation() {
        val longToken = "X".repeat(500)
        val document = DoctorPdfDocument(
            title = "Relatório médico",
            lines = listOf("Profile: Ana Ribeiro — Medicação", longToken) + (0 until 120).map { "Registro laboratorial $it com valores." },
            trends = listOf(
                DoctorTrendSeries("Hemoglobina", "HGB", "g/L", listOf(110.0, 120.0, 130.0).mapIndexed { index, value ->
                    DoctorTrendPoint(Instant.parse("2026-02-0${index + 1}T08:30:00Z"), value, ReferenceRange(100.0 + index, 140.0 + index, unit = "g/L"))
                }),
            ),
            generatedAt = Instant.parse("2026-02-04T08:30:00Z"),
        )
        val text = PdfFixtures.pageTexts(PdfFixtures.pdfBytes(document))
        assertTrue(text.size >= 2)
        val all = text.joinToString("")
        assertTrue("Medicação" in all && "Registro laboratorial 119" in all)
        assertEquals(500, all.count { it == 'X' })
    }

    @Test
    fun languageOverrideChangesStatusWords() {
        val row = DoctorResultRow(
            Instant.parse("2026-03-05T08:30:00Z"), "Hemoglobina", "HGB", 17.5, "g/dL", 175.0, "g/L",
            ReferenceRange(12.0, 15.5, unit = "g/dL"), RangeAssessment.ABOVE,
        )
        fun text(language: ContentLanguage) = PdfFixtures.pageTexts(
            PdfFixtures.pdfBytes(DoctorPdfDocument("t", emptyList(), profileDisplayName = "Ana", resultRows = listOf(row), language = language, generatedAt = PdfFixtures.generatedAt)),
        ).joinToString("\n")
        assertTrue("Above report range" in text(ContentLanguage.EN) && "17.5 g/dL" in text(ContentLanguage.EN))
        assertTrue("Acima da faixa do laudo" in text(ContentLanguage.PT) && "17,5 g/dL" in text(ContentLanguage.PT))
        assertTrue("Por encima del rango del informe" in text(ContentLanguage.ES))
    }

    @Test
    fun comparisonsComeFromTheTrendsEngineAndSameDayIsNamed() {
        val hgb = DoctorExamSections.from(PdfFixtures.document()).single { it.testCode == "HGB" }
        val comparison = hgb.comparison!!
        assertEquals(app.vezuna.domain.trends.ComparisonStatus.COMPARABLE, comparison.status)
        assertEquals(0.2, comparison.sinceFirst!!.delta, 1e-9)
        assertEquals(LocalDate(2026, 7, 15), comparison.sincePrevious!!.fromDate)
        val base = PdfFixtures.canonical()
        val sameDay = base.copy(observations = base.observations + base.observations.first { it.observationId == "tsh-2026-10" }.copy(observationId = "tsh-repeat", originalValue = 2.3, normalizedValue = 2.3))
        val text = PdfFixtures.pageTexts(PdfFixtures.pdfBytes(PdfFixtures.document(sameDay))).joinToString("\n")
        assertTrue("All results share one collection date" in text, "no time trend for one date")
    }

    @Test
    fun hardLineBreaksStartNewLinesAndSharedNumericReferenceIsNotCalledVarying() {
        val base = PdfFixtures.longHistory(60)
        val multiLine = base.copy(medications = listOf(app.vezuna.domain.Medication(PdfFixtures.PROFILE, "m", "Iron", "65 mg", "Morning: 1 tablet\nEvening:\t1 tablet")))
        val pages = PdfFixtures.pageTexts(PdfFixtures.pdfBytes(PdfFixtures.document(multiLine)))
        val text = pages.joinToString("\n")
        assertTrue("Instructions: Morning: 1 tablet\nEvening: 1 tablet" in text, "newline starts a new line, tab becomes a space")
        assertFalse("?" in text.substringAfter("Iron"), "no replacement glyph for control characters")
        assertTrue("Recorded reference: 12.0–16.0 g/dL" in text, "one numeric range is printed at source precision even when wording differs")
        assertFalse("differs between reports" in text)
    }

    @Test
    fun paperDefaultsFollowTheDeviceRegion() {
        assertEquals(PaperSize.LETTER, PaperSize.forLanguageTag("en-US"))
        assertEquals(PaperSize.LETTER, PaperSize.forLanguageTag("es_MX"))
        assertEquals(PaperSize.LETTER, PaperSize.forLanguageTag("zh-Hant-CA"))
        assertEquals(PaperSize.A4, PaperSize.forLanguageTag("pt-BR"))
        assertEquals(PaperSize.A4, PaperSize.forLanguageTag("en"))
    }

    private fun positions(bytes: ByteArray): List<Pair<Float, TextPosition>> = PdfFixtures.open(bytes) { pdf ->
        val found = mutableListOf<Pair<Float, TextPosition>>()
        object : PDFTextStripper() {
            override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                textPositions.forEach { found += currentPage.mediaBox.height to it }
            }
        }.getText(pdf)
        found
    }
}

/**
 * Writes the reviewable sample PDFs when VEZUNA_PDF_SAMPLES_DIR is set; a no-op in normal test runs.
 * Synthetic data only.
 */
class PdfSampleWriterTest {
    @Test
    fun writeSamples() {
        val directory = System.getenv("VEZUNA_PDF_SAMPLES_DIR")?.let(::File) ?: return
        directory.mkdirs()
        val samples = mapOf(
            "vezuna-lab-history-a4.pdf" to PdfFixtures.document(),
            "vezuna-lab-history-letter-grayscale.pdf" to PdfFixtures.document(options = ExportOptions(paper = PaperSize.LETTER, grayscale = true)),
            "vezuna-lab-history-pt.pdf" to PdfFixtures.document(language = ContentLanguage.PT),
            "vezuna-lab-history-es.pdf" to PdfFixtures.document(language = ContentLanguage.ES),
            "vezuna-lab-history-sparse.pdf" to PdfFixtures.document(options = ExportOptions(from = LocalDate(2026, 3, 1), to = LocalDate(2026, 3, 31), includeMedicationsPdf = false)),
            "vezuna-lab-history-hemoglobin.pdf" to PdfFixtures.document(options = ExportOptions(testCodes = setOf("HGB"))),
            "vezuna-lab-history-one-day.pdf" to PdfFixtures.document(options = ExportOptions(from = LocalDate(2026, 10, 28), to = LocalDate(2026, 10, 28))),
            "vezuna-lab-history-long.pdf" to PdfFixtures.document(PdfFixtures.longHistory(120)),
        ) + ExportFamily.entries.associate { family ->
            // The design fixture carries dose events and device summaries, so every family has content.
            "vezuna-${family.slug}.pdf" to DoctorExportContent.document(
                app.vezuna.ui.DesignFixture.data(), ExportSectionText.titleOf(family, ContentLanguage.EN), ContentLanguage.EN, family.defaults(),
            ).copy(generatedAt = PdfFixtures.generatedAt)
        }
        samples.forEach { (name, document) -> File(directory, name).writeBytes(PdfFixtures.pdfBytes(document)) }
    }
}

class JvmPdfExporterSourceTest {
    @Test
    fun headerNamesTheLaboratoryAndOrderDate() {
        val row = DoctorResultRow(
            Instant.parse("2026-03-05T08:30:00Z"), "Hemoglobina", "HGB", 17.5, "g/dL", 175.0, "g/L", ReferenceRange(12.0, 15.5, unit = "g/dL"),
            app.vezuna.domain.RangeAssessment.ABOVE, laboratoryName = "Lab Central", orderedAt = Instant.parse("2026-03-01T12:00:00Z"),
        )
        val document = DoctorPdfDocument("Report", emptyList(), profileDisplayName = "Ana", resultRows = listOf(row))
        val pdf = PdfFixtures.pageTexts(NativePdfExporter().export(document).bytes).joinToString("\n")
        assertTrue(pdf.contains("Laboratories: Lab Central") && pdf.contains("ordered 01 Mar 2026"), "lab name missing from PDF")
        assertEquals("Laboratories: Lab Central (ordered 01 Mar 2026)", app.vezuna.export.DoctorReportFormat.sources(listOf(row)))
        assertEquals(null, app.vezuna.export.DoctorReportFormat.sources(listOf(row.copy(laboratoryName = null, orderedAt = null))))
    }
}
