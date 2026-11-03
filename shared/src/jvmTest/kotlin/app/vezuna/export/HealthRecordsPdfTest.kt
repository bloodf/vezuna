package app.vezuna.export

import app.vezuna.domain.AllergyRecord
import app.vezuna.domain.ProfileData
import app.vezuna.localization.ContentLanguage
import app.vezuna.ui.DesignFixture
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthRecordsPdfTest {
    private val all = ExportOptions(includeAllergies = true, includeConditions = true, includeVaccinations = true)

    private fun bytes(data: ProfileData, options: ExportOptions = all, language: ContentLanguage = ContentLanguage.EN) =
        PdfFixtures.pdfBytes(DoctorExportContent.document(data, ExportSectionText.titleOf(options.family, language), language, options, TimeZone.UTC).copy(generatedAt = PdfFixtures.generatedAt))

    private fun text(bytes: ByteArray) = PdfFixtures.pageTexts(bytes).joinToString(" ").replace(Regex("\\s+"), " ")

    @Test
    fun sectionsPrintValuesAsEnteredAtTheirKnownPrecision() {
        val text = text(bytes(DesignFixture.data()))
        listOf(
            "Allergies and intolerances", "Penicillin", "Reaction: Hives on both arms · Severity, as entered: Moderate",
            "Lactose", "Notes: Yogurt is fine",
            "Conditions and history", "Seasonal rhinitis", "Onset: 2015 · Status, as entered: Ongoing", "Onset: Jun 2012", "Onset unknown",
            "Vaccinations", "Influenza 2026", "Date: 20 Apr 2026 · Dose: 1 · Lot: FLU-2026-118", "Date not recorded · Dose: Booster",
            "Not verified by Vezuna and not derived from laboratory results",
        ).forEach { assertTrue(it in text, "missing: $it in $text") }
    }

    @Test
    fun emptyOrUnselectedSectionsAreLeftOut() {
        val empty = DesignFixture.data().copy(allergies = emptyList(), conditions = emptyList(), vaccinations = emptyList())
        val text = text(bytes(empty))
        listOf("Allergies and intolerances", "Conditions and history", "Vaccinations", "Not verified by Vezuna").forEach { assertFalse(it in text, it) }
        val unselected = text(bytes(DesignFixture.data(), ExportOptions(includeConditions = true)))
        assertTrue("Migraine" in unselected)
        assertFalse("Penicillin" in unselected || "Influenza 2026" in unselected)
    }

    @Test
    fun theSectionSaysHowThePeriodApplies() {
        val text = text(bytes(DesignFixture.data(), all.copy(from = LocalDate(2026, 1, 1), to = LocalDate(2026, 12, 31))))
        assertTrue("Not limited to the selected period." in text, text)
        assertTrue("Only vaccinations dated inside the selected period are listed." in text, text)
        assertTrue("Influenza 2026" in text && "Tetanus" !in text && "Hepatitis B" !in text.substringAfter("Only vaccinations"))
    }

    @Test
    fun translatedLabelsWithTheOwnersWordsUntouched() {
        val pt = text(bytes(DesignFixture.data(), language = ContentLanguage.PT))
        listOf("Alergias e intolerâncias", "Gravidade, como informada: Moderada", "Hives on both arms", "Início desconhecido", "Vacinas", "Data não registrada").forEach { assertTrue(it in pt, "missing: $it") }
        val es = text(bytes(DesignFixture.data(), language = ContentLanguage.ES))
        listOf("Alergias e intolerancias", "Afecciones e historial", "Vacunas", "Fecha no registrada").forEach { assertTrue(it in es, "missing: $it") }
    }

    /** Free text of any length flows across pages and stays above the footer band, at 11 pt or more, in grayscale too. */
    @Test
    fun veryLongFreeTextNeverReachesTheFooter() {
        val long = List(600) { "reaction$it" }.joinToString(" ")
        val longLine = "x".repeat(3000)
        val base = DesignFixture.data()
        val data = base.copy(
            allergies = List(40) { AllergyRecord(base.profile.profileId, "allergy-$it", "Substance $it", reaction = long.takeIf { _ -> it == 3 }, notes = longLine.takeIf { _ -> it == 7 }) },
        )
        listOf(PaperSize.A4, PaperSize.LETTER).forEach { paper ->
            val bytes = bytes(data, all.copy(paper = paper, grayscale = true))
            val pages = PdfFixtures.pageTexts(bytes)
            assertTrue(pages.size >= 3, "expected several pages, got ${pages.size}")
            assertTrue("reaction599" in text(bytes), "nothing is cut")
            val found = mutableListOf<Pair<Float, TextPosition>>()
            PdfFixtures.open(bytes) { pdf ->
                object : PDFTextStripper() {
                    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                        textPositions.forEach { found += currentPage.mediaBox.height to it }
                    }
                }.getText(pdf)
            }
            found.forEach { (height, position) ->
                if (height - position.yDirAdj > 25 * PdfStyle.MM) assertTrue(position.fontSizeInPt >= 11f, "'${position.unicode}' at ${position.fontSizeInPt}pt")
                else assertTrue(position.fontSizeInPt < 11f, "content '${position.unicode}' in the footer band")
            }
        }
    }
}
