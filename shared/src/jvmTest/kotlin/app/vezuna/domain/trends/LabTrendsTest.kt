package app.vezuna.domain.trends

import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabObservation
import app.vezuna.domain.LabReport
import app.vezuna.domain.ProfileVitals
import app.vezuna.domain.ReferenceRange
import app.vezuna.domain.HeartRateDay
import app.vezuna.domain.StepsDay
import app.vezuna.domain.WeightReading
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabTrendsTest {
    private val today = LocalDate(2026, 11, 1)
    private val reports = listOf(
        LabReport("p", "r1", sourceDocumentId = "d1", laboratoryName = "Lab One", importedAt = Instant.parse("2026-11-01T00:00:00Z")),
        LabReport("p", "manual", importedAt = Instant.parse("2026-11-01T00:00:00Z")),
    )

    private fun obs(
        id: String,
        date: String,
        value: Double,
        unit: String = "g/dL",
        norm: Pair<Double, String> = value * 10 to "g/L",
        code: String = "HGB",
        status: ConfirmationStatus = ConfirmationStatus.CONFIRMED,
        decimals: Int? = 1,
        report: String = "r1",
    ) = LabObservation(
        "p", id, report, code, "Hemoglobin", value, unit, norm.first, norm.second,
        ReferenceRange(12.0, 16.0, unit = unit), Instant.parse("${date}T12:00:00Z"), status, valueDecimals = decimals,
    )

    private fun text(id: String, date: String, v: String, code: String = "HBSAG") = LabObservation(
        "p", id, "r1", code, "HBsAg", 0.0, "", 0.0, "", ReferenceRange(), Instant.parse("${date}T12:00:00Z"),
        ConfirmationStatus.CONFIRMED, qualitativeValue = v,
    )

    private val hgb = listOf(obs("a", "2026-04-12", 13.0), obs("b", "2026-07-15", 12.8), obs("c", "2026-10-28", 13.2))

    private fun trend(o: List<LabObservation>, r: TrendRange) =
        LabTrends.forTest("p", "HGB", o, reports, emptyList(), r, today)!!

    @Test
    fun threeMonthsShowsOnePointAndNoTrend() {
        val s = trend(hgb, TrendRange.THREE_MONTHS).series.single()
        assertEquals(DateWindow(LocalDate(2026, 8, 1), today), s.window)
        assertEquals(1, s.points.size)
        assertEquals(ComparisonStatus.NOT_ENOUGH_READINGS, s.comparison.status)
        assertNull(s.comparison.sinceFirst)
        assertFalse(s.canDrawLine)
        assertTrue(s.hasHistoryOutsideWindow)
    }

    @Test
    fun sixMonthsComparesLatestWithFirstAndPrevious() {
        val s = trend(hgb, TrendRange.SIX_MONTHS).series.single()
        assertEquals(2, s.points.size)
        val c = s.comparison
        assertEquals(0.4, c.sincePrevious!!.delta)
        assertEquals(LocalDate(2026, 7, 15), c.sincePrevious!!.fromDate)
        assertEquals(c.sinceFirst, c.sincePrevious)
        assertEquals("g/dL", c.sincePrevious!!.unit)
    }

    @Test
    fun oneYearUsesElapsedTimeSpacingAndLeavesEarlyMonthsEmpty() {
        val s = trend(hgb, TrendRange.ONE_YEAR).series.single()
        val f = s.points.map { it.xFraction * 100 }
        assertEquals(44.38, f[0], 0.01)
        assertEquals(70.14, f[1], 0.01)
        assertEquals(98.90, f[2], 0.01)
        assertEquals(0.2, s.comparison.sinceFirst!!.delta)
        assertEquals(0.4, s.comparison.sincePrevious!!.delta)
        assertEquals(LocalDate(2026, 4, 12), s.comparison.sinceFirst!!.fromDate)
    }

    @Test
    fun allRangeFitsRecordedHistory() {
        val s = trend(hgb, TrendRange.All).series.single()
        assertEquals(DateWindow(LocalDate(2026, 4, 12), LocalDate(2026, 10, 28)), s.window)
        assertEquals(0.0, s.points.first().xFraction)
        assertEquals(1.0, s.points.last().xFraction)
    }

    @Test
    fun noDataInRangeIsNoReadingAndNeverZero() {
        val s = trend(listOf(obs("a", "2024-01-05", 13.0)), TrendRange.THREE_MONTHS).series.single()
        assertEquals(ComparisonStatus.NO_READING, s.comparison.status)
        assertTrue(s.points.isEmpty())
        assertTrue(s.hasHistoryOutsideWindow)
        assertEquals(1, s.allPoints.size)
    }

    @Test
    fun singlePointAllRangeNeverYieldsTrend() {
        val s = trend(listOf(obs("a", "2026-10-28", 13.0)), TrendRange.All).series.single()
        assertEquals(ComparisonStatus.NOT_ENOUGH_READINGS, s.comparison.status)
        assertEquals(0.5, s.points.single().xFraction)
    }

    @Test
    fun unconfirmedAndRejectedExcluded() {
        val o = hgb + obs("u", "2026-10-30", 99.0, status = ConfirmationStatus.UNREVIEWED) +
            obs("x", "2026-10-31", 98.0, status = ConfirmationStatus.REJECTED)
        val s = trend(o, TrendRange.All).series.single()
        assertEquals(3, s.allPoints.size)
        assertEquals(13.2, s.comparison.latest!!.value)
        assertNull(LabTrends.forTest("p", "HGB", o.filter { it.confirmation != ConfirmationStatus.CONFIRMED }, reports, emptyList(), TrendRange.All, today))
    }

    @Test
    fun compatibleUnitsAreMergedInSharedNormalizedUnitNotRelabelled() {
        val o = listOf(obs("a", "2026-04-12", 13.0, "g/dL"), obs("b", "2026-07-15", 128.0, "g/L", 128.0 to "g/L"))
        val s = trend(o, TrendRange.All).series.single()
        assertEquals("g/L", s.unit)
        assertEquals(130.0, s.points[0].value)
        assertEquals("g/dL", s.points[0].originalUnit)
        assertEquals(13.0, s.points[0].originalValue)
        assertEquals(-2.0, s.comparison.sincePrevious!!.delta)
    }

    @Test
    fun incompatibleUnitsAreSeparateSeries() {
        val o = listOf(
            obs("a", "2026-04-12", 13.0),
            obs("b", "2026-07-15", 12.0, "mmol/L", 12.0 to "mmol/L"),
            obs("c", "2026-10-28", 13.2),
        )
        val t = trend(o, TrendRange.All)
        assertEquals(2, t.series.size)
        assertEquals(listOf(2, 1), t.series.map { it.allPoints.size })
        assertEquals("g/dL", t.series[0].unit)
        assertEquals("mmol/L", t.series[1].unit)
        assertEquals(ComparisonStatus.NOT_ENOUGH_READINGS, t.series[1].comparison.status)
    }

    @Test
    fun qualitativeStaysTextAndOutOfSeries() {
        val o = listOf(text("t1", "2026-10-28", "Not detected"), text("t2", "2026-04-01", "Negative"))
        val t = LabTrends.forTest("p", "HBsAg", o, reports, emptyList(), TrendRange.THREE_MONTHS, today)!!
        assertTrue(t.series.isEmpty())
        assertEquals(listOf("Not detected"), t.textEvents.map { it.text })
        assertEquals(2, t.textEventsAll.size)
        assertEquals("Negative", t.textEventsAll.first().text)
    }

    @Test
    fun numericAndTextForSameTestAreSeparated() {
        val o = listOf(obs("a", "2026-10-01", 5.0, code = "X"), text("t", "2026-10-02", "Trace", code = "X"))
        val t = LabTrends.forTest("p", "X", o, reports, emptyList(), TrendRange.All, today)!!
        assertEquals(1, t.series.single().points.size)
        assertEquals(1, t.textEvents.size)
    }

    @Test
    fun pointDetailKeepsOriginalValueRangeAndSource() {
        val s = trend(hgb + obs("m", "2026-10-29", 13.0, report = "manual"), TrendRange.All).series.single()
        val p = s.points.first()
        assertEquals(13.0, p.originalValue)
        assertEquals("g/dL", p.originalUnit)
        assertEquals(1, p.valueDecimals)
        assertEquals(12.0, p.referenceRange!!.lower)
        assertEquals(PointSource(PointSourceKind.REPORT, "Lab One", "r1", "d1"), p.source)
        assertEquals(PointSourceKind.MANUAL, s.points.last().source.kind)
    }

    @Test
    fun labCollectionDayIsStableAndWeightUsesLocalDay() {
        val p = trend(hgb, TrendRange.All).series.single().points.first()
        assertEquals(LocalDate(2026, 4, 12), p.date)
        assertEquals(Instant.parse("2026-04-12T12:00:00Z"), p.at)
        val v = ProfileVitals(weights = listOf(WeightReading("w", Instant.parse("2026-10-31T23:30:00Z"), 68.4)))
        fun day(zone: String) = VitalTrends.series(VitalSeriesKind.WEIGHT, v, TrendRange.All, today, TimeZone.of(zone)).points.single().date
        assertEquals(LocalDate(2026, 11, 1), day("Pacific/Auckland"))
        assertEquals(LocalDate(2026, 10, 31), day("America/Sao_Paulo"))
        assertEquals(LocalDate(2026, 10, 31), day("UTC"))
    }

    @Test
    fun normalizedMixedUnitsKeepNonZeroDelta() {
        val o = listOf(
            obs("a", "2026-04-12", 80.0, "umol/L", 0.08 to "mmol/L", decimals = 0),
            obs("b", "2026-07-15", 0.09, "mmol/L", 0.09 to "mmol/L", decimals = 2),
        )
        val s = trend(o, TrendRange.All).series.single()
        assertEquals("mmol/L", s.unit)
        assertEquals(0.01, s.comparison.sincePrevious!!.delta, 1e-9)
    }

    @Test
    fun customRangeRejectsReversedDates() {
        assertNull(TrendRange.Custom.ofOrNull(LocalDate(2026, 5, 2), LocalDate(2026, 5, 1)))
        assertNotNull(TrendRange.Custom.ofOrNull(LocalDate(2026, 5, 1), LocalDate(2026, 5, 1)))
        assertFailsWith<IllegalArgumentException> { TrendRange.Custom(LocalDate(2026, 5, 2), LocalDate(2026, 5, 1)) }
    }

    @Test
    fun duplicateDeviceDaysCollapseToOnePoint() {
        val v = ProfileVitals(steps = listOf(StepsDay(LocalDate(2026, 10, 30), 100), StepsDay(LocalDate(2026, 10, 30), 200)))
        assertEquals(1, VitalTrends.series(VitalSeriesKind.STEPS, v, TrendRange.All, today, TimeZone.UTC).points.size)
    }

    @Test
    fun customRangeBoundsAreInclusive() {
        val s = trend(hgb, TrendRange.Custom(LocalDate(2026, 7, 15), LocalDate(2026, 10, 28))).series.single()
        assertEquals(2, s.points.size)
        assertEquals(0.0, s.points.first().xFraction)
        assertEquals(1.0, s.points.last().xFraction)
    }

    @Test
    fun sameDayDuplicatesDoNotBreakOrdering() {
        val s = trend(listOf(obs("b", "2026-10-28", 13.2), obs("a", "2026-10-28", 13.0)), TrendRange.All).series.single()
        assertEquals(listOf("a", "b"), s.points.map { it.id })
        assertEquals(0.2, s.comparison.sincePrevious!!.delta)
        assertTrue(s.comparison.sincePrevious!!.sameDay)
        assertFalse(s.canDrawLine)
    }

    @Test
    fun otherProfilesIgnored() {
        assertNull(LabTrends.forTest("other", "HGB", hgb, reports, emptyList(), TrendRange.All, today))
        assertTrue(LabTrends.forProfile("other", hgb, reports, emptyList(), TrendRange.All, today).isEmpty())
        assertEquals(1, LabTrends.forProfile("p", hgb, reports, emptyList(), TrendRange.All, today).size)
    }

    @Test
    fun missingDatesAreNotZeroFilled() {
        val s = trend(hgb, TrendRange.ONE_YEAR).series.single()
        assertEquals(3, s.points.size)
        assertTrue(s.points.all { it.value > 0 })
    }

    @Test
    fun deviceSeriesAreDailySummariesWithGaps() {
        val v = ProfileVitals(
            steps = listOf(StepsDay(LocalDate(2026, 10, 25), 5870), StepsDay(LocalDate(2026, 10, 30), 6100), StepsDay(LocalDate(2026, 10, 31), 6240)),
            heartRateDaily = listOf(HeartRateDay(LocalDate(2026, 10, 25), 60.0, 73.0, 120.0, 24), HeartRateDay(LocalDate(2026, 10, 30), 60.0, 73.0, 120.0, 24), HeartRateDay(LocalDate(2026, 10, 31), 60.0, 72.0, 120.0, 24)),
            weights = listOf(
                WeightReading("w1", Instant.parse("2026-10-25T08:00:00Z"), 68.8),
                WeightReading("w2", Instant.parse("2026-10-28T08:00:00Z"), 68.6),
                WeightReading("w3", Instant.parse("2026-10-31T08:00:00Z"), 68.4),
            ),
        )
        val steps = VitalTrends.series(VitalSeriesKind.STEPS, v, TrendRange.All, today, TimeZone.UTC)
        assertEquals(3, steps.points.size)
        assertTrue(steps.points.all { it.granularity == Granularity.DAILY_SUMMARY && it.source.kind == PointSourceKind.DEVICE })
        assertEquals(370.0, steps.comparison.sinceFirst!!.delta)
        assertEquals(140.0, steps.comparison.sincePrevious!!.delta)
        assertEquals(LocalDate(2026, 10, 30), steps.comparison.sincePrevious!!.fromDate)
        val hr = VitalTrends.series(VitalSeriesKind.HEART_RATE_DAILY_AVERAGE, v, TrendRange.All, today, TimeZone.UTC)
        assertEquals(-1.0, hr.comparison.sincePrevious!!.delta)
        val w = VitalTrends.series(VitalSeriesKind.WEIGHT, v, TrendRange.All, today, TimeZone.UTC)
        assertEquals(-0.2, w.comparison.sincePrevious!!.delta)
        assertEquals(-0.4, w.comparison.sinceFirst!!.delta)
        assertEquals(Granularity.READING, w.points.first().granularity)
        // gap days have no point
        assertEquals(listOf(25, 30, 31), steps.points.map { it.date.day })
        assertNotNull(steps.axis)
        assertEquals(ComparisonStatus.NO_READING, VitalTrends.series(VitalSeriesKind.STEPS, ProfileVitals(), TrendRange.THREE_MONTHS, today, TimeZone.UTC).comparison.status)
        assertEquals(ComparisonStatus.NO_READING, VitalTrends.series(VitalSeriesKind.STEPS, ProfileVitals(), TrendRange.All, today, TimeZone.UTC).comparison.status)
    }
}
