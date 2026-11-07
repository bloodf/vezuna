package app.vezuna

import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabObservation
import app.vezuna.domain.ReferenceBand
import app.vezuna.domain.ReferenceRange
import app.vezuna.domain.TrendPreparation
import app.vezuna.domain.referenceBand
import app.vezuna.ui.trends.historyPresentation
import app.vezuna.ui.trends.chartPoints
import app.vezuna.ui.trends.lineJoins
import app.vezuna.ui.ChartScale
import kotlin.time.Instant
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

    @Test
    fun recordsPresentationCentersSameDayAndNeverJoinsIt() {
        val range = ReferenceRange(70.0, 99.0, null, "mg/dL")
        val history = series(
            obs("a", 90.0, "mg/dL", range, "2026-01-10T12:00:00Z"),
            obs("b", 95.0, "mg/dL", range, "2026-01-10T12:00:00Z"),
        ).historyPresentation()
        assertEquals(listOf(0.5f, 0.5f), history.chartPoints().map { it.x })
        assertEquals(listOf(false), history.lineJoins())
        assertEquals("2026-01-10", history.window!!.start.toString())
        assertEquals(listOf(90.0, 95.0), history.points.map { it.originalValue })
        assertEquals(listOf(range, range), history.points.map { it.referenceRange })
        val single = series(obs("a", 90.0, "mg/dL", range, "2026-01-10T12:00:00Z")).historyPresentation()
        assertEquals(listOf(0.5f), single.chartPoints().map { it.x })
        assertEquals(emptyList(), single.lineJoins())
        val empty = series(obs("a", 90.0, "mg/dL", range, "2026-01-10T12:00:00Z")).copy(points = emptyList()).historyPresentation()
        assertEquals(emptyList(), empty.chartPoints())
        assertEquals(emptyList(), empty.lineJoins())
    }

    @Test
    fun recordsPresentationKeepsAlignedValueAndOriginalReportMetadata() {
        val originalRange = ReferenceRange(3.9, 5.5, null, "mmol/L")
        val aligned = series(
            obs("a", 5.0, "mmol/L", originalRange, "2026-01-10T12:00:00Z").copy(valueDecimals = 1),
            obs("b", 95.0, "mg/dL", ReferenceRange(70.0, 99.0, null, "mg/dL"), "2026-03-10T12:00:00Z"),
        ).historyPresentation()
        val converted = aligned.points.first()
        assertEquals(90.09009009009009, converted.value, 1e-9)
        assertEquals(5.0, converted.originalValue)
        assertEquals("mmol/L", converted.originalUnit)
        assertEquals(1, converted.valueDecimals)
        assertEquals(originalRange, converted.referenceRange)
        assertEquals(listOf(true, false), aligned.chartPoints().map { it.hollow })
        assertEquals(listOf(0f, 1f), aligned.chartPoints().map { it.x })
        assertEquals(listOf(true), aligned.lineJoins())
    }

    @Test
    fun sameDayNeighborsStayUnjoinedWithinLongerHistory() {
        val range = ReferenceRange(70.0, 99.0, null, "mg/dL")
        val history = series(
            obs("a", 90.0, "mg/dL", range, "2026-01-10T12:00:00Z"),
            obs("b", 95.0, "mg/dL", range, "2026-01-10T12:00:00Z"),
            obs("c", 92.0, "mg/dL", range, "2026-03-10T12:00:00Z"),
        ).historyPresentation()
        assertEquals(listOf(0f, 0f, 1f), history.chartPoints().map { it.x })
        assertEquals(listOf(false, true), history.lineJoins())
    }

    private fun series(vararg rows: LabObservation) = assertNotNull(TrendPreparation.forTest("p", "GLICOSE", rows.toList()))

    private fun obs(id: String, value: Double, unit: String, range: ReferenceRange, at: String) = LabObservation(
        "p", id, "r", "GLICOSE", "Glicose", value, unit, value, unit, range, Instant.parse(at), ConfirmationStatus.CONFIRMED,
    )
}
