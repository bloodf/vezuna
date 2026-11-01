package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.data.VezunaRepository
import app.vezuna.domain.Profile
import app.vezuna.domain.StepsDay
import app.vezuna.domain.VitalsBatch
import app.vezuna.health.DeviceVitalsSource
import app.vezuna.health.DeviceVitalsSync
import app.vezuna.health.VitalsReadRequest
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeviceVitalsSyncTest {
    private var now = Instant.parse("2026-11-01T12:00:00Z")

    private class FakeSource(var steps: List<StepsDay>) : DeviceVitalsSource {
        val requests = mutableListOf<VitalsReadRequest>()
        override suspend fun read(request: VitalsReadRequest): VitalsBatch {
            requests += request
            return VitalsBatch(request.replaceFrom, steps = steps)
        }
    }

    private class FlakyRepository(private val delegate: VezunaRepository, var failNext: Boolean) : VezunaRepository by delegate {
        override suspend fun ingestDeviceVitals(batch: VitalsBatch, now: Instant): Int {
            if (failNext) { failNext = false; throw IllegalStateException("disk full") }
            return delegate.ingestDeviceVitals(batch, now)
        }
    }

    private suspend fun repo() = InMemoryVezunaRepository().also { it.createProfile(Profile("p", "Me", createdAt = Instant.fromEpochSeconds(1))) }
    private fun sync(source: DeviceVitalsSource, repository: VezunaRepository) = DeviceVitalsSync(source, repository, { now }, { TimeZone.UTC })

    @Test
    fun firstSyncReadsThirtyDaysThenTrailingWeek() = runTest {
        val repository = repo()
        val source = FakeSource(listOf(StepsDay(LocalDate(2026, 10, 31), 100)))
        val sync = sync(source, repository)
        sync.synchronize()
        now = Instant.parse("2026-11-05T12:00:00Z")
        sync.synchronize()
        assertEquals(LocalDate(2026, 10, 2), source.requests[0].replaceFrom)
        assertEquals(LocalDate(2026, 10, 25), source.requests[1].replaceFrom)
    }

    @Test
    fun failedSaveDoesNotAdvanceTheWindowAndRetryLosesNothing() = runTest {
        val flaky = FlakyRepository(repo(), failNext = true)
        val source = FakeSource(listOf(StepsDay(LocalDate(2026, 10, 31), 100)))
        val sync = sync(source, flaky)
        assertFailsWith<IllegalStateException> { sync.synchronize() }
        assertEquals(null, flaky.readProfile("p").vitals.lastSync)
        assertEquals(1, sync.synchronize())
        assertEquals(source.requests[0].replaceFrom, source.requests[1].replaceFrom)
        assertEquals(listOf(StepsDay(LocalDate(2026, 10, 31), 100)), flaky.readProfile("p").vitals.steps)
    }

    @Test
    fun overlappingSourcesAreNotSummedBecauseAggregateIsStoredAsIs() = runTest {
        val repository = repo()
        // Two apps wrote the same walk; the platform aggregate already de-duplicated it to 1000.
        val source = FakeSource(listOf(StepsDay(LocalDate(2026, 10, 31), 1000)))
        val sync = sync(source, repository)
        sync.synchronize()
        sync.synchronize()
        assertEquals(1000, repository.readProfile("p").vitals.steps.single().count)
    }

    @Test
    fun deletionInsideTheWindowSelfHealsOnNextSync() = runTest {
        val repository = repo()
        val source = FakeSource(listOf(StepsDay(LocalDate(2026, 10, 31), 1000)))
        val sync = sync(source, repository)
        sync.synchronize()
        source.steps = emptyList()
        sync.synchronize()
        assertEquals(emptyList(), repository.readProfile("p").vitals.steps)
    }
}
