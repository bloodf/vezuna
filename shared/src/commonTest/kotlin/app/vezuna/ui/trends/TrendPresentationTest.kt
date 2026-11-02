package app.vezuna.ui.trends

import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabObservation
import app.vezuna.domain.ProfileVitals
import app.vezuna.domain.ReferenceBand
import app.vezuna.domain.ReferenceRange
import app.vezuna.domain.StepsDay
import app.vezuna.domain.WeightReading
import app.vezuna.domain.trends.LabTrends
import app.vezuna.domain.trends.TrendRange
import app.vezuna.domain.trends.VitalSeriesKind
import app.vezuna.domain.trends.VitalTrends
import app.vezuna.ui.components.HistoryRange
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrendPresentationTest {
    private val today = LocalDate(2026, 11, 1)
    private val hgbRange = ReferenceRange(12.0, 16.0, null, "g/dL")

    @Test
    fun oneYearPlacesHemoglobinAtElapsedTimeNotEvenly() {
        val series = hgb(TrendRange.ONE_YEAR).series.single()
        val xs = series.chartPoints().map { it.x }
        assertEquals(listOf(0.4438f, 0.7014f, 0.9890f), xs.map { kotlin.math.round(it * 10000) / 10000 })
        assertEquals(listOf(true, true), series.lineJoins())
        assertEquals(ReferenceBand(12.0, 16.0), series.referenceBand())
        assertFalse(series.rangesDiffer())
        assertEquals(1, series.decimals())
    }

    @Test
    fun threeMonthsLeavesOnePointAndNoLine() {
        val series = hgb(TrendRange.THREE_MONTHS).series.single()
        assertEquals(1, series.points.size)
        assertEquals(emptyList(), series.lineJoins())
    }

    @Test
    fun differingRangesDrawNoBandAndSayWhy() {
        val trend = LabTrends.forTest(
            "p", "HGB",
            listOf(obs("a", "2026-04-12", 13.0, hgbRange), obs("b", "2026-07-15", 12.8, ReferenceRange(11.5, 15.5, null, "g/dL"))),
            emptyList(), emptyList(), TrendRange.All, today,
        )!!
        val series = trend.series.single()
        assertNull(series.referenceBand())
        assertTrue(series.rangesDiffer())
    }

    @Test
    fun singleBoundIsKeptForADashedLine() {
        val ldl = ReferenceRange(null, 130.0, "Under 130 mg/dL", "mg/dL")
        val trend = LabTrends.forTest(
            "p", "LDL",
            listOf(obs("a", "2026-04-12", 146.0, ldl, "LDL", "mg/dL"), obs("b", "2026-10-14", 132.0, ldl, "LDL", "mg/dL")),
            emptyList(), emptyList(), TrendRange.All, today,
        )!!
        assertEquals(ReferenceBand(null, 130.0), trend.series.single().referenceBand())
    }

    @Test
    fun deviceSeriesJoinOnlyConsecutiveDays() {
        val days = (25..31).map { LocalDate(2026, 10, it) }
        val vitals = ProfileVitals(
            steps = days.map { StepsDay(it, 6000L) },
            weights = listOf(25, 28, 31).map { WeightReading("w$it", Instant.parse("2026-10-${it}T10:30:00Z"), 68.0) },
        )
        val steps = VitalTrends.series(VitalSeriesKind.STEPS, vitals, TrendRange.All, today, TimeZone.UTC)
        assertEquals(List(6) { true }, steps.lineJoins())
        val weight = VitalTrends.series(VitalSeriesKind.WEIGHT, vitals, TrendRange.All, today, TimeZone.UTC)
        assertEquals(listOf(false, false), weight.lineJoins())
        assertNull(weight.referenceBand())
    }

    @Test
    fun convertedPointsAreHollowAndBlockTheBand() {
        val trend = LabTrends.forTest(
            "p", "HGB",
            listOf(obs("a", "2026-04-12", 13.0, hgbRange), obs("b", "2026-07-15", 128.0, hgbRange, unit = "g/L", normalized = 12.8, normalizedUnit = "g/dL")),
            emptyList(), emptyList(), TrendRange.All, today,
        )!!
        val series = trend.series.single()
        assertEquals(listOf(false, true), series.chartPoints().map { it.hollow })
        assertNull(series.referenceBand())
    }

    @Test
    fun changesAreSignedAndNeutral() {
        assertEquals("+0.4", signedNumber(0.4, 1, "en"))
        assertEquals("−8", signedNumber(-8.0, 0, "en"))
        assertEquals("0", signedNumber(0.0, null, "en"))
        assertEquals("+0,4", signedNumber(0.4, 1, "pt-BR"))
    }

    @Test
    fun rangeChipsMapToDomainRanges() {
        assertEquals(TrendRange.All, HistoryRange.ALL.toTrendRange())
        assertEquals(TrendRange.Months(12), HistoryRange.ONE_YEAR.toTrendRange())
    }

    private fun hgb(range: TrendRange) = assertNotNull(
        LabTrends.forTest(
            "p", "HGB",
            listOf(obs("a", "2026-04-12", 13.0, hgbRange), obs("b", "2026-07-15", 12.8, hgbRange), obs("c", "2026-10-28", 13.2, hgbRange)),
            emptyList(), emptyList(), range, today,
        ),
    )

    private fun obs(
        id: String,
        date: String,
        value: Double,
        range: ReferenceRange,
        code: String = "HGB",
        unit: String = "g/dL",
        normalized: Double = value,
        normalizedUnit: String = unit,
    ) = LabObservation(
        "p", id, "r", code, code, value, unit, normalized, normalizedUnit, range, Instant.parse("${date}T12:00:00Z"),
        ConfirmationStatus.CONFIRMED, valueDecimals = 1,
    )
}
