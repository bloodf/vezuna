package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.domain.DeviceVitals
import app.vezuna.domain.HealthLink
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.ProfileVitals
import app.vezuna.domain.VitalDailySummary
import app.vezuna.domain.VitalMetric
import app.vezuna.domain.VitalSample
import app.vezuna.domain.VitalsBatch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VitalsTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val utc = TimeZone.UTC

    private fun sample(id: String, metric: VitalMetric, value: Double, at: String) =
        VitalSample(id, metric, value, Instant.parse(at), Instant.parse(at))

    @Test
    fun oldVaultWithoutVitalsStillDecodes() {
        val old = """{"profile":{"profileId":"p","displayName":"Me","createdAt":"2026-01-01T00:00:00Z"}}"""
        val data = Json { encodeDefaults = true }.decodeFromString(ProfileData.serializer(), old)
        assertEquals(ProfileVitals(), data.vitals)
    }

    @Test
    fun vitalsRoundTripThroughJson() {
        val vitals = DeviceVitals.ingest(ProfileVitals(), VitalsBatch(listOf(sample("a", VitalMetric.WEIGHT_KG, 70.5, "2026-10-30T08:00:00Z"))), now, utc)
        val json = Json { encodeDefaults = true }
        assertEquals(vitals, json.decodeFromString(ProfileVitals.serializer(), json.encodeToString(ProfileVitals.serializer(), vitals)))
    }

    @Test
    fun recentSamplesUpsertAndDeletionsApply() {
        val first = DeviceVitals.ingest(
            ProfileVitals(),
            VitalsBatch(listOf(sample("a", VitalMetric.HEART_RATE_BPM, 60.0, "2026-10-31T08:00:00Z"), sample("b", VitalMetric.STEPS, 100.0, "2026-10-31T09:00:00Z"))),
            now, utc,
        )
        val second = DeviceVitals.ingest(
            first,
            VitalsBatch(listOf(sample("a", VitalMetric.HEART_RATE_BPM, 62.0, "2026-10-31T08:00:00Z")), listOf("b")),
            now, utc,
        )
        assertEquals(listOf(62.0), second.samples.map { it.value })
        assertEquals(listOf("a"), second.samples.map { it.recordId })
    }

    @Test
    fun oldHeartRateAndStepsRollUpToDailySummaries() {
        val old = "2026-09-01"
        val existing = ProfileVitals(samples = listOf(
            sample("h1", VitalMetric.HEART_RATE_BPM, 60.0, "${old}T08:00:00Z"),
            sample("h2", VitalMetric.HEART_RATE_BPM, 80.0, "${old}T09:00:00Z"),
            sample("s1", VitalMetric.STEPS, 1000.0, "${old}T10:00:00Z"),
            sample("s2", VitalMetric.STEPS, 500.0, "${old}T11:00:00Z"),
            sample("w1", VitalMetric.WEIGHT_KG, 70.0, "${old}T07:00:00Z"),
        ))
        val result = DeviceVitals.ingest(existing, VitalsBatch(emptyList()), now, utc)
        assertEquals(listOf("w1"), result.samples.map { it.recordId })
        val date = LocalDate(2026, 9, 1)
        assertEquals(
            setOf(VitalDailySummary(VitalMetric.HEART_RATE_BPM, date, 70.0, 2), VitalDailySummary(VitalMetric.STEPS, date, 1500.0, 2)),
            result.dailySummaries.toSet(),
        )
        // Running the same retention again must not double count.
        assertEquals(result, DeviceVitals.ingest(result, VitalsBatch(emptyList()), now, utc))
    }

    @Test
    fun dataOlderThanOneYearIsDropped() {
        val existing = ProfileVitals(
            samples = listOf(sample("w", VitalMetric.WEIGHT_KG, 70.0, "2025-10-01T00:00:00Z"), sample("w2", VitalMetric.WEIGHT_KG, 71.0, "2026-10-01T00:00:00Z")),
            dailySummaries = listOf(VitalDailySummary(VitalMetric.STEPS, LocalDate(2025, 9, 1), 10.0, 1), VitalDailySummary(VitalMetric.STEPS, LocalDate(2026, 5, 1), 10.0, 1)),
        )
        val result = DeviceVitals.ingest(existing, VitalsBatch(emptyList()), now, utc)
        assertEquals(listOf("w2"), result.samples.map { it.recordId })
        assertEquals(listOf(LocalDate(2026, 5, 1)), result.dailySummaries.map { it.date })
    }

    @Test
    fun incomingHeartRateOlderThanRawWindowIsNotDoubleCounted() {
        val result = DeviceVitals.ingest(ProfileVitals(), VitalsBatch(listOf(sample("old", VitalMetric.HEART_RATE_BPM, 60.0, "2026-08-01T00:00:00Z"))), now, utc)
        assertTrue(result.samples.isEmpty() && result.dailySummaries.isEmpty())
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
        val batch = VitalsBatch(listOf(sample("w", VitalMetric.WEIGHT_KG, 70.0, "2026-10-30T00:00:00Z")))

        assertEquals(1, repository.ingestDeviceVitals(batch, now))
        assertEquals(1, repository.readProfile("a").vitals.samples.size)
        assertTrue(repository.readProfile("b").vitals.samples.isEmpty())

        repository.setLinkedHealthProfile("b")
        repository.ingestDeviceVitals(VitalsBatch(listOf(sample("w3", VitalMetric.WEIGHT_KG, 71.0, "2026-10-31T00:00:00Z"))), now)
        assertEquals(1, repository.readProfile("a").vitals.samples.size, "changing link must not move samples")
        assertEquals(1, repository.readProfile("b").vitals.samples.size)

        repository.deleteProfile("b")
        assertEquals("a", repository.linkedHealthProfileId())
        assertFailsWith<IllegalArgumentException> { repository.setLinkedHealthProfile("missing") }
    }

    @Test
    fun seriesCombinesSummariesWithRecentReadingsPerDay() {
        val vitals = ProfileVitals(
            samples = listOf(sample("s", VitalMetric.STEPS, 400.0, "2026-10-31T10:00:00Z"), sample("w", VitalMetric.WEIGHT_KG, 70.0, "2026-10-30T10:00:00Z")),
            dailySummaries = listOf(VitalDailySummary(VitalMetric.STEPS, LocalDate(2026, 9, 1), 1500.0, 2)),
        )
        val steps = DeviceVitals.series(vitals, VitalMetric.STEPS, utc)
        assertEquals(listOf(1500.0, 400.0), steps.map { it.second })
        assertTrue(steps[0].first < steps[1].first)
        assertEquals(listOf(70.0), DeviceVitals.series(vitals, VitalMetric.WEIGHT_KG, utc).map { it.second })
    }
}
