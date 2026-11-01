package app.vezuna

import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabObservation
import app.vezuna.domain.ReferenceBand
import app.vezuna.domain.ReferenceRange
import app.vezuna.domain.TrendPreparation
import app.vezuna.domain.referenceBand
import app.vezuna.ui.ChartScale
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrendChartTest {
    @Test
    fun scalePadsTheDomainAndKeepsThreeTicks() {
        val scale = ChartScale.of(listOf(90.0, 110.0))
        assertEquals(88.0, scale.min, 1e-9)
        assertEquals(112.0, scale.max, 1e-9)
        assertEquals(listOf(88.0, 100.0, 112.0), scale.ticks)
        assertEquals(0.5f, scale.fraction(100.0), 1e-6f)
    }

    @Test
    fun scaleIncludesTheReferenceBand() {
        val scale = ChartScale.of(listOf(90.0, 95.0), ReferenceBand(70.0, 99.0))
        assertTrue(scale.min < 70.0 && scale.max > 99.0)
    }

    @Test
    fun flatSeriesAndZeroFloorStayDrawable() {
        val flat = ChartScale.of(listOf(5.0, 5.0))
        assertTrue(flat.max > flat.min)
        assertEquals(0.0, ChartScale.of(listOf(0.02, 1.0)).min, 1e-9)
        assertTrue(ChartScale.of(listOf(-3.0)).min < -3.0)
    }

    @Test
    fun bandOnlyWhenEveryPointSharesUnitAndRange() {
        val range = ReferenceRange(70.0, 99.0, "70 a 99", "mg/dL")
        val same = series(obs("a", 90.0, "mg/dL", range, "2026-01-10T12:00:00Z"), obs("b", 95.0, "mg/dL", range, "2026-03-10T12:00:00Z"))
        assertEquals(ReferenceBand(70.0, 99.0), same.referenceBand())
        val otherRange = series(obs("a", 90.0, "mg/dL", range, "2026-01-10T12:00:00Z"), obs("b", 95.0, "mg/dL", ReferenceRange(65.0, 99.0, null, "mg/dL"), "2026-03-10T12:00:00Z"))
        assertNull(otherRange.referenceBand())
        val noRange = series(obs("a", 90.0, "mg/dL", ReferenceRange(), "2026-01-10T12:00:00Z"))
        assertNull(noRange.referenceBand())
        val converted = series(obs("a", 5.0, "mmol/L", ReferenceRange(3.9, 5.5, null, "mmol/L"), "2026-01-10T12:00:00Z"), obs("b", 95.0, "mg/dL", range, "2026-03-10T12:00:00Z"))
        assertNull(converted.referenceBand())
    }

    private fun series(vararg rows: LabObservation) = assertNotNull(TrendPreparation.forTest("p", "GLICOSE", rows.toList()))

    private fun obs(id: String, value: Double, unit: String, range: ReferenceRange, at: String) = LabObservation(
        "p", id, "r", "GLICOSE", "Glicose", value, unit, value, unit, range, Instant.parse(at), ConfirmationStatus.CONFIRMED,
    )
}
