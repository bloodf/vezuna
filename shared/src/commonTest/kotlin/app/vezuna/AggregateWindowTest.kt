package app.vezuna

import app.vezuna.domain.AggregateWindow
import app.vezuna.domain.DeviceVitals
import app.vezuna.domain.HeartRateBucket
import app.vezuna.domain.HeartRateDay
import app.vezuna.domain.ProfileVitals
import app.vezuna.domain.StepsDay
import app.vezuna.domain.VitalDailySummary
import app.vezuna.domain.VitalMetric
import app.vezuna.domain.VitalSample
import app.vezuna.domain.WeightReading
import app.vezuna.health.SourceClaim
import app.vezuna.health.SourceContinuityEvidence
import app.vezuna.health.WeightChanges
import app.vezuna.health.WeightContinuation
import app.vezuna.health.WeightLocalState
import app.vezuna.health.WeightMutation
import app.vezuna.health.WeightPage
import app.vezuna.health.WeightProvider
import app.vezuna.health.WeightSource
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class AggregateWindowTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val utc = TimeZone.UTC
    private val from = LocalDate(2026, 10, 25)
    private fun hour(at: String, avg: Double = 70.0) = HeartRateBucket(Instant.parse(at), avg - 5, avg, avg + 5)
    private fun window(steps: List<StepsDay> = emptyList(), hours: List<HeartRateBucket> = emptyList()) = AggregateWindow(from, steps, hours, now, utc)

    @Test
    fun aggregateReplacementPreservesWeightOrderingOldWeightsAndLegacyFieldsExactly() {
        val old = ProfileVitals(
            steps = listOf(StepsDay(LocalDate(2026, 10, 1), 10), StepsDay(LocalDate(2026, 10, 30), 500)),
            heartRateHourly = listOf(hour("2026-10-20T10:00:00Z"), hour("2026-10-30T10:00:00Z")),
            weights = listOf(
                WeightReading("newer-first", now, 70.0), WeightReading("old-last", Instant.parse("2020-01-01T00:00:00Z"), 60.0),
            ),
            lastSync = Instant.parse("2026-10-30T12:00:00Z"),
            samples = listOf(VitalSample("raw-weight", VitalMetric.WEIGHT_KG, 50.0, now, now)),
            dailySummaries = listOf(VitalDailySummary(VitalMetric.STEPS, LocalDate(2020, 1, 1), 99.0, 1)),
        )
        val result = DeviceVitals.ingestAggregates(old, window(listOf(StepsDay(LocalDate(2026, 10, 31), 800))))
        assertEquals(old.copy(
            steps = listOf(StepsDay(LocalDate(2026, 10, 1), 10), StepsDay(LocalDate(2026, 10, 31), 800)),
            heartRateHourly = listOf(hour("2026-10-20T10:00:00Z")), lastSync = now,
        ), result)
    }

    @Test
    fun aggregateAndWeightUpsertDeletionCommuteInBothOrdersWithLiteralExpectedResult() {
        val source = WeightSource(WeightProvider.HEALTH_CONNECT, "synthetic-store", "all")
        val evidence = SourceContinuityEvidence(source, "synthetic verified continuity")
        val keep = WeightReading("unknown", now, 65.0)
        val gone = WeightReading("gone", now, 70.0)
        val added = WeightReading("added", now, 71.0)
        val old = ProfileVitals(
            steps = listOf(StepsDay(LocalDate(2026, 10, 30), 500)),
            heartRateHourly = listOf(hour("2026-09-01T10:00:00Z", 60.0), hour("2026-10-30T10:00:00Z")),
            weights = listOf(keep, gone), lastSync = Instant.parse("2026-10-30T12:00:00Z"),
        )
        val local = WeightLocalState(mapOf("gone" to evidence), continuation = WeightContinuation.Replay(source, "before"))
        val page = WeightPage(SourceClaim(source, evidence), listOf(WeightMutation.Upsert(added), WeightMutation.Delete("gone")), WeightContinuation.Steady(source, "after"))
        val aggregates = window(listOf(StepsDay(LocalDate(2026, 10, 31), 800)), listOf(hour("2026-10-31T10:00:00Z", 80.0)))

        val weightFirst = WeightChanges.reduce(old, local, page)
        assertEquals(old.copy(weights = listOf(keep, added)), weightFirst.vitals)
        val aggregateSecond = DeviceVitals.ingestAggregates(weightFirst.vitals, aggregates)
        val aggregateFirst = DeviceVitals.ingestAggregates(old, aggregates)
        assertEquals(listOf(keep, gone), aggregateFirst.weights)
        val weightSecond = WeightChanges.reduce(aggregateFirst, local, page)

        val expected = ProfileVitals(
            steps = listOf(StepsDay(LocalDate(2026, 10, 31), 800)),
            heartRateHourly = listOf(hour("2026-10-31T10:00:00Z", 80.0)),
            heartRateDaily = listOf(HeartRateDay(LocalDate(2026, 9, 1), 55.0, 60.0, 65.0, 1)),
            weights = listOf(keep, added), lastSync = now,
        )
        assertEquals(expected, aggregateSecond)
        assertEquals(expected, weightSecond.vitals)
        assertEquals(WeightLocalState(mapOf("added" to evidence), continuation = WeightContinuation.Steady(source, "after")), weightFirst.local)
        assertEquals(weightFirst.local, weightSecond.local)
    }

    @Test
    fun repeatedOldBucketCannotDoubleCountAndStoredOldHoursRollEvenInsideReplacementWindow() {
        val stale = hour("2026-09-01T08:00:00Z", 60.0)
        val stored = ProfileVitals(
            heartRateHourly = listOf(stale, hour("2026-09-01T09:00:00Z", 80.0)),
            heartRateDaily = listOf(HeartRateDay(LocalDate(2026, 9, 1), 45.0, 50.0, 55.0, 2)),
        )
        val wide = AggregateWindow(LocalDate(2026, 1, 1), emptyList(), listOf(stale), now, utc)
        val first = DeviceVitals.ingestAggregates(stored, wide)
        val again = DeviceVitals.ingestAggregates(first, wide)
        val expected = ProfileVitals(heartRateDaily = listOf(HeartRateDay(LocalDate(2026, 9, 1), 45.0, 60.0, 85.0, 4)), lastSync = now)
        assertEquals(expected, first)
        assertEquals(expected, again)
    }

    @Test
    fun dstRepeatedLocalHourRollsTwoDistinctInstantsIntoOneDayAndCutoffIsInclusive() {
        val zone = TimeZone.of("America/New_York")
        val later = Instant.parse("2026-12-03T12:00:00Z")
        val cutoff = hour("2026-11-02T12:00:00Z", 90.0)
        val fallBackA = hour("2026-11-01T05:00:00Z", 60.0)
        val fallBackB = hour("2026-11-01T06:00:00Z", 80.0)
        val old = ProfileVitals(heartRateHourly = listOf(fallBackA, fallBackB, cutoff))
        val read = AggregateWindow(LocalDate(2026, 10, 1), emptyList(), listOf(fallBackA, fallBackB, cutoff), later, zone)
        val result = DeviceVitals.ingestAggregates(old, read)
        val expected = ProfileVitals(
            heartRateHourly = listOf(cutoff),
            heartRateDaily = listOf(HeartRateDay(LocalDate(2026, 11, 1), 55.0, 70.0, 85.0, 2)), lastSync = later,
        )
        assertEquals(expected, result)
        assertEquals(expected, DeviceVitals.ingestAggregates(result, read))
    }

    @Test
    fun aggregateRetentionBoundaryIsInclusiveWithoutRetainingOrRemovingWeights() {
        val oldWeight = WeightReading("old", Instant.parse("2020-01-01T00:00:00Z"), 60.0)
        val old = ProfileVitals(
            steps = listOf(StepsDay(LocalDate(2025, 10, 31), 10), StepsDay(LocalDate(2025, 11, 1), 20)),
            heartRateDaily = listOf(
                HeartRateDay(LocalDate(2025, 10, 31), 50.0, 60.0, 70.0, 1),
                HeartRateDay(LocalDate(2025, 11, 1), 55.0, 65.0, 75.0, 2),
            ), weights = listOf(oldWeight),
        )
        assertEquals(ProfileVitals(
            steps = listOf(StepsDay(LocalDate(2025, 11, 1), 20)),
            heartRateDaily = listOf(HeartRateDay(LocalDate(2025, 11, 1), 55.0, 65.0, 75.0, 2)),
            weights = listOf(oldWeight), lastSync = now,
        ), DeviceVitals.ingestAggregates(old, window()))
    }
}
