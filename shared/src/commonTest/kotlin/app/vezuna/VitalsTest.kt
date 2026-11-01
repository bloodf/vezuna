package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.domain.DeviceVitals
import app.vezuna.domain.HealthLink
import app.vezuna.domain.HeartRateBucket
import app.vezuna.domain.HeartRateDay
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.ProfileVitals
import app.vezuna.domain.StepsDay
import app.vezuna.domain.VitalMetric
import app.vezuna.domain.VitalsBatch
import app.vezuna.domain.VitalsWindow
import app.vezuna.domain.WeightReading
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VitalsTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val utc = TimeZone.UTC
    private val saoPaulo = TimeZone.of("America/Sao_Paulo")
    private val json = Json { encodeDefaults = true }

    private fun hour(at: String, avg: Double = 70.0) = HeartRateBucket(Instant.parse(at), avg - 5, avg, avg + 5, 30)
    private fun weight(id: String, kg: Double, at: String) = WeightReading(id, Instant.parse(at), kg)

    @Test
    fun oldVaultWithoutVitalsStillDecodes() {
        val old = """{"profile":{"profileId":"p","displayName":"Me","createdAt":"2026-01-01T00:00:00Z"}}"""
        assertEquals(ProfileVitals(), json.decodeFromString(ProfileData.serializer(), old).vitals)
    }

    @Test
    fun firstFormatRawSamplesMigrateToWeightsOnly() {
        val first = """{"samples":[
            {"recordId":"w","metric":"WEIGHT_KG","value":70.0,"start":"2026-10-30T00:00:00Z","end":"2026-10-30T00:00:00Z"},
            {"recordId":"s","metric":"STEPS","value":500.0,"start":"2026-10-30T00:00:00Z","end":"2026-10-30T01:00:00Z"}],
            "dailySummaries":[{"metric":"STEPS","date":"2026-09-01","value":10.0,"count":1}]}"""
        val migrated = json.decodeFromString(ProfileVitals.serializer(), first).migrated()
        assertEquals(listOf("w"), migrated.weights.map { it.recordId })
        assertTrue(migrated.steps.isEmpty() && migrated.samples.isEmpty() && migrated.dailySummaries.isEmpty())
    }

    @Test
    fun stepsAreStoredAsAggregatedDaysAndReReadReplacesNotSums() {
        val batch = VitalsBatch(LocalDate(2026, 10, 25), steps = listOf(StepsDay(LocalDate(2026, 10, 30), 8000), StepsDay(LocalDate(2026, 10, 31), 6000)))
        val first = DeviceVitals.ingest(ProfileVitals(), batch, now, utc)
        // Same window read again with a corrected total and without the other day: replaced, never added.
        val second = DeviceVitals.ingest(first, VitalsBatch(LocalDate(2026, 10, 25), steps = listOf(StepsDay(LocalDate(2026, 10, 30), 8100))), now, utc)
        assertEquals(listOf(StepsDay(LocalDate(2026, 10, 30), 8100)), second.steps)
    }

    @Test
    fun windowReplaceKeepsDaysBeforeTheWindow() {
        val old = ProfileVitals(steps = listOf(StepsDay(LocalDate(2026, 10, 1), 1000)))
        val result = DeviceVitals.ingest(old, VitalsBatch(LocalDate(2026, 10, 25), steps = listOf(StepsDay(LocalDate(2026, 10, 30), 5))), now, utc)
        assertEquals(listOf(LocalDate(2026, 10, 1), LocalDate(2026, 10, 30)), result.steps.map { it.date })
    }

    @Test
    fun fullReimportAfterExpiryReconcilesDeletions() {
        val stored = DeviceVitals.ingest(
            ProfileVitals(),
            VitalsBatch(LocalDate(2026, 10, 2), steps = listOf(StepsDay(LocalDate(2026, 10, 20), 9000)), heartRate = listOf(hour("2026-10-20T10:00:00Z")),
                weights = listOf(weight("gone", 70.0, "2026-10-20T08:00:00Z"), weight("kept", 71.0, "2026-10-21T08:00:00Z"))),
            now, utc,
        )
        // The platform no longer has the 20th (deleted) and one weight; a full window re-read must drop them.
        val result = DeviceVitals.ingest(stored, VitalsBatch(LocalDate(2026, 10, 2), weights = listOf(weight("kept", 71.0, "2026-10-21T08:00:00Z"))), now, utc)
        assertTrue(result.steps.isEmpty() && result.heartRateHourly.isEmpty())
        assertEquals(listOf("kept"), result.weights.map { it.recordId })
    }

    @Test
    fun weightIsReplacedByRecordIdAndDeletedById() {
        val stored = DeviceVitals.ingest(ProfileVitals(), VitalsBatch(LocalDate(2026, 10, 30), weights = listOf(weight("a", 70.0, "2026-09-01T08:00:00Z"), weight("b", 80.0, "2026-09-02T08:00:00Z"))), now, utc)
        val edited = DeviceVitals.ingest(stored, VitalsBatch(LocalDate(2026, 10, 30), weights = listOf(weight("a", 69.0, "2026-09-03T08:00:00Z")), deletedWeightIds = listOf("b")), now, utc)
        assertEquals(listOf(weight("a", 69.0, "2026-09-03T08:00:00Z")), edited.weights)
    }

    @Test
    fun hourlyHeartRateOlderThanRawWindowRollsIntoDaysWithoutDoubleCounting() {
        val stored = ProfileVitals(heartRateHourly = listOf(hour("2026-09-01T08:00:00Z", 60.0), hour("2026-09-01T09:00:00Z", 80.0)))
        val result = DeviceVitals.ingest(stored, VitalsBatch(LocalDate(2026, 10, 30)), now, utc)
        assertTrue(result.heartRateHourly.isEmpty())
        val day = result.heartRateDaily.single()
        assertEquals(LocalDate(2026, 9, 1), day.date)
        assertEquals(70.0, day.avg)
        assertEquals(55.0, day.min)
        assertEquals(85.0, day.max)
        assertEquals(60, day.count)
        assertEquals(result.heartRateDaily, DeviceVitals.ingest(result, VitalsBatch(LocalDate(2026, 10, 30)), now, utc).heartRateDaily)
        // An hour that old arriving again is not accepted, so it cannot be counted twice.
        assertEquals(result.heartRateDaily, DeviceVitals.ingest(result, VitalsBatch(LocalDate(2026, 10, 30), heartRate = listOf(hour("2026-09-01T08:00:00Z", 60.0))), now, utc).heartRateDaily)
    }

    @Test
    fun dayKeysFollowTheLocalZoneNotUtc() {
        // 01:30 UTC on Oct 31 is still Oct 30 in Sao Paulo (UTC-3).
        val late = Instant.parse("2026-10-31T01:30:00Z")
        val points = DeviceVitals.series(ProfileVitals(heartRateHourly = listOf(HeartRateBucket(late, 60.0, 70.0, 80.0, 10))), VitalMetric.HEART_RATE_BPM, saoPaulo)
        assertEquals(LocalDate(2026, 10, 30).atStartOfDayIn(saoPaulo).epochSeconds, points.single().first)
        val rolled = DeviceVitals.ingest(ProfileVitals(heartRateHourly = listOf(HeartRateBucket(Instant.parse("2026-09-02T01:30:00Z"), 60.0, 70.0, 80.0, 10))), VitalsBatch(LocalDate(2026, 10, 30)), now, saoPaulo)
        assertEquals(LocalDate(2026, 9, 1), rolled.heartRateDaily.single().date)
        // 02:00 UTC on Nov 1 is still Oct 31 in Sao Paulo, so the trailing week starts Oct 24.
        val early = Instant.parse("2026-11-01T02:00:00Z")
        assertEquals(LocalDate(2026, 10, 24), VitalsWindow.replaceFrom(early, early, saoPaulo))
    }

    @Test
    fun windowStartsThirtyDaysBackOnFirstSyncThenSevenDaysBeforeLastSync() {
        assertEquals(LocalDate(2026, 10, 2), VitalsWindow.replaceFrom(null, now, utc))
        assertEquals(LocalDate(2026, 10, 25), VitalsWindow.replaceFrom(Instant.parse("2026-11-01T00:00:00Z"), now, utc))
        assertEquals(LocalDate(2026, 10, 2), VitalsWindow.replaceFrom(Instant.parse("2026-08-01T00:00:00Z"), now, utc))
        assertEquals(LocalDate(2025, 11, 1), VitalsWindow.weightsFrom(null, now, utc))
    }

    @Test
    fun storedCountOnlyCountsEntriesThatWereKept() {
        val batch = VitalsBatch(LocalDate(2026, 10, 30), heartRate = listOf(hour("2026-08-01T00:00:00Z"), hour("2026-10-31T00:00:00Z")), steps = listOf(StepsDay(LocalDate(2026, 10, 31), 1)))
        assertEquals(2, DeviceVitals.storedCount(DeviceVitals.ingest(ProfileVitals(), batch, now, utc), batch))
    }

    @Test
    fun invalidPlatformValuesAreRejectedAtTheMappingBoundary() {
        assertNull(StepsDay.ofOrNull(LocalDate(2026, 10, 1), -5))
        assertNull(HeartRateBucket.ofOrNull(now, 10.0, 70.0, 80.0, 5))
        assertNull(HeartRateBucket.ofOrNull(now, 60.0, Double.NaN, 80.0, 5))
        assertNull(HeartRateBucket.ofOrNull(now, 60.0, 70.0, 500.0, 5))
        assertNull(WeightReading.ofOrNull("w", now, 0.0))
        assertNull(WeightReading.ofOrNull("w", now, Double.POSITIVE_INFINITY))
    }

    @Test
    fun oneYearOfVitalsStaysUnderTheSizeBudget() {
        val start = Instant.parse("2025-11-02T00:00:00Z")
        val recent = Instant.parse("2026-10-01T00:00:00Z")
        // Steady state after a year: 31 days of hourly buckets, a daily heart-rate summary per day, daily steps, weekly weight.
        val hourly = (0 until 31 * 24).map { HeartRateBucket(recent + (it * 3600).seconds, 58.5, 72.25, 131.0, 60) }
        val daily = (0 until 365).map { HeartRateDay(LocalDate(2025, 11, 2).plus(it, DateTimeUnit.DAY), 58.5, 72.25, 131.0, 1440) }
        val steps = (0 until 365).map { StepsDay(LocalDate(2025, 11, 2).plus(it, DateTimeUnit.DAY), 8000L + it) }
        val weights = (0 until 52).map { WeightReading("record-$it-aaaaaaaa", start + (it * 7 * 24 * 3600).seconds, 70.5) }
        val stored = ProfileVitals(steps, hourly, daily, weights, now)
        val bytes = json.encodeToString(ProfileVitals.serializer(), stored).encodeToByteArray().size
        assertTrue(bytes < 200_000, "one year of vitals is $bytes bytes")
    }

    @Test
    fun linkDefaultsToFirstCreatedProfileAndSurvivesMissingTarget() {
        val a = Profile("a", "A", createdAt = Instant.fromEpochSeconds(10))
        val b = Profile("b", "B", createdAt = Instant.fromEpochSeconds(20))
        assertEquals("a", HealthLink.resolve(null, listOf(b, a)))
        assertEquals("b", HealthLink.resolve("b", listOf(a, b)))
        assertEquals("a", HealthLink.resolve("gone", listOf(a, b)))
        assertEquals(null, HealthLink.resolve(null, emptyList()))
    }

    @Test
    fun deviceVitalsOnlyReachLinkedProfileAndRelinkOnDelete() = runTest {
        val repository = InMemoryVezunaRepository()
        repository.createProfile(Profile("a", "A", createdAt = Instant.fromEpochSeconds(10)))
        repository.createProfile(Profile("b", "B", createdAt = Instant.fromEpochSeconds(20)))
        fun batch(day: Int) = VitalsBatch(LocalDate(2026, 10, 25), steps = listOf(StepsDay(LocalDate(2026, 10, day), 100)))

        assertEquals(1, repository.ingestDeviceVitals(batch(30), now))
        assertEquals(1, repository.readProfile("a").vitals.steps.size)
        assertTrue(repository.readProfile("b").vitals.isEmpty)

        repository.setLinkedHealthProfile("b")
        repository.ingestDeviceVitals(batch(31), now)
        assertEquals(1, repository.readProfile("a").vitals.steps.size, "changing link must not move data")
        assertEquals(1, repository.readProfile("b").vitals.steps.size)

        repository.deleteProfile("b")
        assertEquals("a", repository.linkedHealthProfileId())
        assertFailsWith<IllegalArgumentException> { repository.setLinkedHealthProfile("missing") }
    }

    @Test
    fun seriesProducesDailyStepsAndAverageHeartRate() {
        val vitals = ProfileVitals(
            steps = listOf(StepsDay(LocalDate(2026, 10, 31), 400)),
            heartRateHourly = listOf(hour("2026-10-31T10:00:00Z", 60.0), hour("2026-10-31T11:00:00Z", 80.0)),
            weights = listOf(weight("w", 70.0, "2026-10-30T10:00:00Z")),
        )
        assertEquals(listOf(400.0), DeviceVitals.series(vitals, VitalMetric.STEPS, utc).map { it.second })
        assertEquals(listOf(70.0), DeviceVitals.series(vitals, VitalMetric.HEART_RATE_BPM, utc).map { it.second })
        assertEquals(listOf(70.0), DeviceVitals.series(vitals, VitalMetric.WEIGHT_KG, utc).map { it.second })
    }
}
