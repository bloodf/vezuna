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

/**
 * The commit boundary of a device vitals sync.
 *
 * A sync reads the platform and then commits through [VezunaRepository.ingestDeviceVitals]. The platform read is the
 * last suspending production seam before that commit, so the cancellation is delivered there: the payload is already
 * in hand and the commit has not been entered. The commit itself runs inside a `Mutex.withLock` critical section,
 * which is not cancellable, so once it is entered it runs to the end. The guarantee under test is therefore the one
 * production can keep: a sync cancelled before the commit is entered persists no vitals, moves no bookkeeping and
 * leaves the window where it was, so the next sync reads the same window and commits the same payload.
 */
class VitalsSyncCancellationBoundaryTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val steps = listOf(StepsDay(LocalDate(2026, 10, 31), 100))
    private val payload = VitalsBatch(LocalDate(2026, 10, 2), steps = steps)
    private val profile = Profile("p", "Me", createdAt = Instant.fromEpochSeconds(1))
    private val barrier = 5.seconds

    /** Hands the payload over and then waits, so the cancellation lands with the payload in hand. */
    private class PlatformSource(private val payload: VitalsBatch) : DeviceVitalsSource {
        val requests = mutableListOf<VitalsReadRequest>()
        val payloadInHand = CompletableDeferred<Unit>()
        var holdBeforeCommit = true

        override suspend fun read(request: VitalsReadRequest): VitalsBatch {
            requests += request
            if (holdBeforeCommit) {
                payloadInHand.complete(Unit)
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

    /** Runs one sync, cancels it once the payload is in hand, and returns the error the sync ended with. */
    private suspend fun CoroutineScope.cancelAtTheBoundary(sync: DeviceVitalsSync, source: PlatformSource): Throwable? {
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
        withTimeout(barrier) { source.payloadInHand.await() }
        job.cancelAndJoin()
        return failure.await()
    }

    @Test
    fun syncCancelledWithThePayloadInHandCommitsNothing() = runTest {
        for (fixture in fixtures()) {
            fixture.repository.createProfile(profile)
            val source = PlatformSource(payload)
            val counter = CommitCounter(fixture.repository)
            val sync = DeviceVitalsSync(source, counter, { now }, { TimeZone.UTC })
            val before = fixture.repository.captureVitalsSync(TimeZone.UTC)
            val writesBefore = fixture.vault?.writes?.toList()

            val failure = cancelAtTheBoundary(sync, source)

            assertEquals(ProfileVitals(), fixture.repository.peekProfile("p").vitals, "no vitals may be persisted")
            assertEquals(0, counter.commits, "the commit must not be entered after a pre-commit cancellation")
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

            cancelAtTheBoundary(sync, source)
            val cancelledRequest = source.requests.single()

            source.holdBeforeCommit = false
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
}
