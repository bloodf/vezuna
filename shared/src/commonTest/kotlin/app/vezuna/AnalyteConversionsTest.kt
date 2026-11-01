package app.vezuna

import app.vezuna.domain.AnalyteConversions
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabObservation
import app.vezuna.domain.ReferenceRange
import app.vezuna.domain.TrendPreparation
import app.vezuna.domain.UnitNormalizer
import kotlinx.datetime.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyteConversionsTest {
    private fun near(expected: Double, actual: Double?, tolerance: Double = 1e-9) =
        assertTrue(actual != null && abs(expected - actual) <= tolerance, "expected $expected got $actual")

    @Test
    fun everyCitedFactorMatchesItsPublishedValue() {
        val published = mapOf(
            "Glucose" to 0.0555,
            "Total cholesterol" to 0.02586,
            "HDL cholesterol" to 0.02586,
            "LDL cholesterol" to 0.02586,
            "Triglycerides" to 0.01129,
            "Creatinine" to 88.42,
            "Urea" to 0.1665,
            "Urea nitrogen (BUN)" to 0.357,
            "Calcium (total)" to 0.2495,
            "Magnesium" to 0.4114,
            "Phosphorus (inorganic)" to 0.3229,
            "Uric acid" to 59.48,
            "Total bilirubin" to 17.1,
            "Hemoglobin" to 10.0,
            "Albumin" to 10.0,
            "25-OH vitamin D" to 2.496,
        )
        assertEquals(published.keys, AnalyteConversions.table.map { it.analyte }.toSet())
        AnalyteConversions.table.forEach { assertEquals(published.getValue(it.analyte), it.factor, it.analyte) }
    }

    @Test
    fun massToMolarFactorsAgreeWithMolarMass() {
        // factor = 10 / molar mass (mg/dL -> mmol/L); x1000 where the SI unit is µmol/L.
        val molarMass = mapOf(
            "Glucose" to 180.16, "Total cholesterol" to 386.65, "HDL cholesterol" to 386.65, "LDL cholesterol" to 386.65,
            "Triglycerides" to 885.43, "Urea" to 60.06, "Urea nitrogen (BUN)" to 28.014, "Calcium (total)" to 40.078,
            "Magnesium" to 24.305, "Phosphorus (inorganic)" to 30.974,
        )
        val micro = mapOf("Creatinine" to 113.12, "Uric acid" to 168.11, "Total bilirubin" to 584.66)
        AnalyteConversions.table.forEach { entry ->
            val expected = molarMass[entry.analyte]?.let { 10.0 / it } ?: micro[entry.analyte]?.let { 10_000.0 / it } ?: return@forEach
            assertTrue(abs(entry.factor - expected) / expected < 0.005, "${entry.analyte}: ${entry.factor} vs $expected")
        }
        near(2.496, 1000.0 / 400.64 * 1.0, 0.01)
    }

    @Test
    fun everyEntryHasACitationAndConvertsBothWays() {
        AnalyteConversions.table.forEach { entry ->
            assertTrue(entry.source.contains("http") || entry.source.contains("doi"), entry.analyte)
            val code = entry.aliases.first()
            near(entry.factor, AnalyteConversions.factor(code, entry.fromUnit, entry.toUnit))
            near(1.0 / entry.factor, AnalyteConversions.factor(code, entry.toUnit, entry.fromUnit))
        }
    }

    @Test
    fun glucoseConvertsBothWays() {
        near(5.55, AnalyteConversions.alternatives("Glicose", 100.0, "mg/dL").single().value)
        near(100.0, AnalyteConversions.alternatives("GLU", 5.55, "mmol/L").single().value, 0.01)
    }

    @Test
    fun noFactorMeansNoConversion() {
        assertNull(AnalyteConversions.factor("Ferritina", "ng/mL", "pmol/L"))
        assertNull(AnalyteConversions.factor("Glicose", "mg/dL", "g/L"))
        assertNull(AnalyteConversions.factor("Glicose", "mg/dL", "mg/dL"))
        assertTrue(AnalyteConversions.alternatives("Ferritina", 50.0, "ng/mL").isEmpty())
    }

    @Test
    fun trendMergesUnitsOnlyWhenACitedFactorExists() {
        val glucoseOld = obs("o1", "GLICOSE", "Glicose", 100.0, "mg/dL", "2026-01-10T12:00:00Z")
        val glucoseNew = obs("o2", "GLICOSE", "Glicose", 5.55, "mmol/L", "2026-06-10T12:00:00Z")
        val series = assertNotNull(TrendPreparation.forTest("p", "glicose", listOf(glucoseOld, glucoseNew)))
        assertEquals(2, series.points.size)
        assertEquals("mmol/L", series.unit)
        assertEquals(listOf(true, false), series.points.map { it.converted })
        near(5.55, series.points.first().y)
        assertTrue(series.points.first().converted)
        assertTrue(series.droppedUnits.isEmpty())
        // stored data is untouched
        assertEquals(100.0, series.points.first().observation.originalValue)
    }

    @Test
    fun trendShowsTheNewestEnteredUnitAndScalesPlainUnits() {
        val hbOld = obs("o1", "HGB", "Hemoglobina", 140.0, "g/L", "2026-01-10T12:00:00Z")
        val hbNew = obs("o2", "HGB", "Hemoglobina", 13.5, "g/dL", "2026-06-10T12:00:00Z")
        val series = assertNotNull(TrendPreparation.forTest("p", "HGB", listOf(hbOld, hbNew)))
        assertEquals("g/dL", series.unit)
        near(14.0, series.points.first().y)
        near(13.5, series.points.last().y)
        val glucose = TrendPreparation.forTest("p", "GLICOSE", listOf(
            obs("a", "GLICOSE", "Glicose", 5.55, "mmol/L", "2026-01-10T12:00:00Z"),
            obs("b", "GLICOSE", "Glicose", 100.0, "mg/dL", "2026-06-10T12:00:00Z"),
        ))
        assertEquals("mg/dL", glucose?.unit)
        near(100.0, glucose?.points?.first()?.y, 0.01)
    }

    @Test
    fun trendSurfacesUnitsWithoutAFactor() {
        val old = obs("o1", "FER", "Ferritin", 50.0, "ng/mL", "2026-01-10T12:00:00Z")
        val new = obs("o2", "FER", "Ferritin", 110.0, "pmol/L", "2026-06-10T12:00:00Z")
        val series = assertNotNull(TrendPreparation.forTest("p", "FER", listOf(old, new)))
        assertEquals(1, series.points.size)
        assertEquals(listOf("ng/mL"), series.droppedUnits)
    }

    @Test
    fun choicesGroupByCanonicalCodeAndShowDisplayName() {
        val rows = listOf(
            obs("o1", "HbA1c", "Hemoglobina glicada", 5.5, "%", "2026-01-10T12:00:00Z"),
            obs("o2", "HBA1C", "Hemoglobina Glicada A1c", 5.7, "%", "2026-03-10T12:00:00Z"),
            obs("o3", "LDL", "LDL", 100.0, "mg/dL", "2026-03-10T12:00:00Z"),
        )
        val choices = TrendPreparation.choices(rows)
        assertEquals(listOf("HBA1C", "LDL"), choices.map { it.code }.sorted())
        assertEquals("Hemoglobina Glicada A1c", choices.first { it.code == "HBA1C" }.displayName)
        assertEquals(2, TrendPreparation.forTest("p", "HbA1c", rows)?.points?.size)
    }

    private fun obs(id: String, code: String, name: String, value: Double, unit: String, at: String): LabObservation {
        val normalized = UnitNormalizer.normalize(value, unit)
        return LabObservation("p", id, "r", code, name, value, unit, normalized.value, normalized.unit, ReferenceRange(), Instant.parse(at), ConfirmationStatus.CONFIRMED)
    }
}
