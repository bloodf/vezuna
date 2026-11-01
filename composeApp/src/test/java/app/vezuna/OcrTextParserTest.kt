package app.vezuna

import app.vezuna.importer.OcrTextParser
import app.vezuna.ui.ExtractedValueKind
import kotlinx.datetime.Instant
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OcrTextParserTest {
    @Test
    fun parsesAccentedAndDigitBearingAnalytesWithoutConfusingNameDigits() {
        val rows = OcrTextParser.parseMeasurements(
            "Zinco sérico 91,5 µg/L 55,0 a 120,0 µg/L\n" +
                "Vitamina B-12, Dosagem 372 pg/mL 190 a 900 pg/mL\n" +
                "T4 Livre (Tiroxina) 1,10 ng/dL 0,80 a 1,70 ng/dL",
        )
        assertEquals(listOf("Zinco sérico", "Vitamina B-12, Dosagem", "T4 Livre (Tiroxina)"), rows.map { it.displayName })
        assertEquals("91.5", rows[0].value)
        assertEquals(55.0, rows[0].referenceRange.lower)
        assertEquals(120.0, rows[0].referenceRange.upper)
        assertEquals("ZINCOSRICO", OcrTextParser.canonicalTestCode(rows[0].displayName))
    }

    @Test
    fun normalizesNbspLigaturesAndBrazilianThousands() {
        assertEquals("quantificação clínica", OcrTextParser.normalizeText("quantiﬁcação\u00a0clínica"))
        val row = OcrTextParser.parseMeasurementLine("Contagem de células 245.000 /µL 150.000 a 450.000 /µL")
        requireNotNull(row)
        assertEquals("245000", row.value)
        assertEquals(150000.0, row.referenceRange.lower)
        assertEquals(450000.0, row.referenceRange.upper)
    }

    @Test
    fun dualRelativeAndAbsoluteValuesUseAbsoluteResultAndMatchingRange() {
        val row = OcrTextParser.parseMeasurementLine("Células brancas 41,0 % 6.200 /µL 38,0 a 70,0 % 3.500 a 9.500 /µL")
        requireNotNull(row)
        assertEquals("6200", row.value)
        assertEquals("/µL", row.unit)
        assertEquals(3500.0, row.referenceRange.lower)
        assertEquals(9500.0, row.referenceRange.upper)
    }

    @Test
    fun parsesQualifiedResultsThresholdsAndDeferredReferences() {
        val qualified = OcrTextParser.parseMeasurementLine("Anticorpo sintético Inferior a 0,4 U/mL Inferior a 4,0 U/mL")
        requireNotNull(qualified)
        assertEquals("0.4", qualified.value)
        assertEquals(4.0, qualified.referenceRange.upper)
        val threshold = OcrTextParser.parseMeasurementLine("Receptor sintético 0,65 UI/L Inferior a 1,50 UI/L")
        requireNotNull(threshold)
        assertEquals(1.5, threshold.referenceRange.upper)
        val deferred = OcrTextParser.parseMeasurementLine("Triglicérides 132 mg/dL CONFIRA NA TABELA ABAIXO")
        requireNotNull(deferred)
        assertNull(deferred.referenceRange.lower)
        assertTrue(deferred.referenceRange.text.orEmpty().contains("TABELA"))
    }

    @Test
    fun qualitativeRowsRemainTypedAndHistoricalRowsAreExcluded() {
        val rows = OcrTextParser.parseMeasurements(
            "Protozoários Ausente Ausente\n" +
                "Histórico Zinco\n" +
                "Resultado 70 µg/L 75 µg/L 80 µg/L\n" +
                "Zinco sérico 88 µg/L 60 a 120 µg/L",
        )
        assertEquals(listOf("Protozoários", "Zinco sérico"), rows.map { it.displayName })
        assertEquals(ExtractedValueKind.QUALITATIVE, rows[0].valueKind)
        assertEquals("Ausente", rows[0].value)
        assertEquals("88", rows[1].value)
    }

    @Test
    fun confidentInlineDatesAndAdministrativeNoiseAreHandledConservatively() {
        val dated = OcrTextParser.parseMeasurementLine("Zinco sérico 88 µg/L 60 a 120 µg/L 28/02/2026")
        requireNotNull(dated)
        assertEquals(Instant.parse("2026-02-28T00:00:00Z"), dated.collectedAt)
        val invalidDate = OcrTextParser.parseMeasurementLine("Zinco sérico 88 µg/L 60 a 120 µg/L 31/02/2026")
        requireNotNull(invalidDate)
        assertNull(invalidDate.collectedAt)
        val noise = OcrTextParser.parseMeasurements(
            "Paciente 123 mg/dL\nPágina 1 de 1\nMétodo 45 U/L\nValidação 999 U/L",
        )
        assertTrue(noise.isEmpty())
    }
}
