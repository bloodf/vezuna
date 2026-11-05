package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.data.VaultBackedRepository
import app.vezuna.data.VezunaRepository
import app.vezuna.data.VitalsSyncContext
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileVitals
import app.vezuna.domain.StepsDay
import app.vezuna.domain.VitalsBatch
import app.vezuna.health.DeviceVitalsSource
import app.vezuna.health.DeviceVitalsSync
import app.vezuna.health.VitalsReadRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Suspended-read cancellation, safe retry, and already-cancelled repository entry. */
class VitalsSyncCancellationBoundaryTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val steps = listOf(StepsDay(LocalDate(2026, 10, 31), 100))
    private val payload = VitalsBatch(LocalDate(2026, 10, 2), steps = steps)
    private val profile = Profile("p", "Me", createdAt = Instant.fromEpochSeconds(1))
    private val barrier = 5.seconds

    /** Suspends before returning a payload to the sync caller. */
    private class PlatformSource(private val payload: VitalsBatch) : DeviceVitalsSource {
        val requests = mutableListOf<VitalsReadRequest>()
        val readStarted = CompletableDeferred<Unit>()
        var suspendRead = true

        override suspend fun read(request: VitalsReadRequest): VitalsBatch {
            requests += request
            if (suspendRead) {
                readStarted.complete(Unit)
                awaitCancellation()
            }
            return payload
        }
    }

    /** Counts the commits and forwards everything else to the real repository. */
    private class CommitCounter(private val delegate: VezunaRepository) : VezunaRepositoryDelegate(delegate) {
        var commits = 0

        override suspend fun ingestDeviceVitals(context: VitalsSyncContext, batch: VitalsBatch, now: Instant): Int {
            commits += 1
            return delegate.ingestDeviceVitals(context, batch, now)
        }
    }

    private class Fixture(val repository: VezunaRepository, val vault: GenerationalMemoryVault?)

    private suspend fun fixtures(): List<Fixture> {
        val vault = GenerationalMemoryVault()
        val persistent = VaultBackedRepository(vault).also { it.load() }
        return listOf(Fixture(InMemoryVezunaRepository(), null), Fixture(persistent, vault))
    }

    /** Cancels the real sync while its platform source is suspended. */
    private suspend fun CoroutineScope.cancelDuringRead(sync: DeviceVitalsSync, source: PlatformSource): Throwable? {
        val failure = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                sync.synchronize()
                failure.complete(null)
            } catch (error: Throwable) {
                failure.complete(error)
                throw error
            }
        }
        withTimeout(barrier) { source.readStarted.await() }
        job.cancelAndJoin()
        return failure.await()
    }

    @Test
    fun syncCancelledDuringPlatformReadCommitsNothing() = runTest {
        for (fixture in fixtures()) {
            fixture.repository.createProfile(profile)
            val source = PlatformSource(payload)
            val counter = CommitCounter(fixture.repository)
            val sync = DeviceVitalsSync(source, counter, { now }, { TimeZone.UTC })
            val before = fixture.repository.captureVitalsSync(TimeZone.UTC)
            val writesBefore = fixture.vault?.writes?.toList()

            val failure = cancelDuringRead(sync, source)

            assertEquals(ProfileVitals(), fixture.repository.peekProfile("p").vitals, "no vitals may be persisted")
            assertEquals(0, counter.commits, "the commit must not be entered after cancellation during the read")
            val after = fixture.repository.captureVitalsSync(TimeZone.UTC)
            assertEquals(before.generation, after.generation, "the sync generation must not move")
            assertNull(after.lastSync, "the sync window must not advance")
            assertNull(fixture.repository.explicitHealthLinkId(), "the link must not be pinned before the data is durable")
            assertEquals(writesBefore, fixture.vault?.writes?.toList(), "nothing may be written to the vault")
            fixture.vault?.let { vault ->
                assertNull(vault.current["repository-vitals-p-v1"], "no vitals file may exist")
                assertNull(vault.current["repository-vitals-index-v1"], "no vitals index may exist")
                assertNull(vault.current["repository-health-link-v1"], "no health link may exist")
            }
            assertTrue(failure is CancellationException, "the cancelled sync must end in a cancellation")
        }
    }

    @Test
    fun retryAfterCancelledSyncCommitsTheSamePayloadOverTheSameWindow() = runTest {
        for (fixture in fixtures()) {
            fixture.repository.createProfile(profile)
            val source = PlatformSource(payload)
            val counter = CommitCounter(fixture.repository)
            val sync = DeviceVitalsSync(source, counter, { now }, { TimeZone.UTC })
            val before = fixture.repository.captureVitalsSync(TimeZone.UTC)

            cancelDuringRead(sync, source)
            val cancelledRequest = source.requests.single()

            source.suspendRead = false
            assertEquals(1, sync.synchronize(), "the retry must commit the same synthetic payload")
            assertEquals(1, counter.commits, "only the retry may commit")
            val retriedRequest = source.requests.last()
            assertEquals(cancelledRequest.replaceFrom, retriedRequest.replaceFrom, "the cancelled sync must not advance the window")
            assertEquals(cancelledRequest.weightsFrom, retriedRequest.weightsFrom, "the cancelled sync must not advance the weight window")
            val vitals = fixture.repository.peekProfile("p").vitals
            assertEquals(steps, vitals.steps, "the retry must store the same payload")
            assertEquals(now, vitals.lastSync, "the retry must record the sync time")
            assertEquals(before.generation + 1, fixture.repository.captureVitalsSync(TimeZone.UTC).generation, "exactly one commit may move the generation")
            assertEquals("p", fixture.repository.explicitHealthLinkId(), "the commit pins the link after the data is durable")
            fixture.vault?.let { vault ->
                assertTrue("repository-vitals-p-v1" in vault.writes, "the durable vitals file must be written")
                assertTrue("repository-health-link-v1" in vault.writes, "the durable link must be written")
            }
        }
    }

    @Test
    fun alreadyCancelledRepositoryEntryWritesNothingAndAllowsFreshRetry() = runTest {
        for (fixture in fixtures()) {
            val repository = fixture.repository
            repository.createProfile(profile)
            val before = repository.captureVitalsSync(TimeZone.UTC)
            val writesBefore = fixture.vault?.writes?.toList()
            val failure = CompletableDeferred<Throwable?>()
            val job = launch {
                currentCoroutineContext().cancel()
                try {
                    repository.ingestDeviceVitals(before, payload, now)
                    failure.complete(null)
                } catch (error: Throwable) {
                    failure.complete(error)
                    throw error
                }
            }
            job.join()
            assertTrue(failure.await() is CancellationException)
            assertEquals(ProfileVitals(), repository.peekProfile("p").vitals)
            assertEquals(before, repository.captureVitalsSync(TimeZone.UTC))
            assertNull(repository.explicitHealthLinkId())
            assertEquals(writesBefore, fixture.vault?.writes?.toList())
            fixture.vault?.let { vault ->
                val reopened = VaultBackedRepository(vault).also { it.load() }
                assertEquals(ProfileVitals(), reopened.peekProfile("p").vitals)
                assertNull(reopened.explicitHealthLinkId())
            }

            assertEquals(1, repository.ingestDeviceVitals(before, payload, now))
            assertEquals(steps, repository.peekProfile("p").vitals.steps)
            assertEquals(now, repository.peekProfile("p").vitals.lastSync)
            assertEquals(before.generation + 1, repository.captureVitalsSync(TimeZone.UTC).generation)
            fixture.vault?.let { vault ->
                val reopened = VaultBackedRepository(vault).also { it.load() }
                assertEquals(steps, reopened.peekProfile("p").vitals.steps)
                assertEquals(now, reopened.peekProfile("p").vitals.lastSync)
            }
        }
    }
}
