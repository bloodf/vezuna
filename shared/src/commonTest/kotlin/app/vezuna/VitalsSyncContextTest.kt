package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.data.VaultBackedRepository
import app.vezuna.data.VezunaRepository
import app.vezuna.data.restoreWithVitals
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileVitals
import app.vezuna.domain.StepsDay
import app.vezuna.domain.VitalsBatch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class VitalsSyncContextTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val batch = VitalsBatch(LocalDate(2026, 10, 25), steps = listOf(StepsDay(LocalDate(2026, 10, 31), 100)))

    private suspend fun repositories(): List<VezunaRepository> = listOf(
        InMemoryVezunaRepository(),
        VaultBackedRepository(GenerationalMemoryVault()).also { it.load() },
    ).onEach { repository ->
        repository.createProfile(Profile("a", "A", createdAt = Instant.fromEpochSeconds(1)))
        repository.createProfile(Profile("b", "B", createdAt = Instant.fromEpochSeconds(2)))
    }

    @Test
    fun relinkRoundTripAndTargetReplacementInvalidateCapturedContext() = runTest {
        for (repository in repositories()) {
            val changes: List<suspend () -> Unit> = listOf(
                { repository.setLinkedHealthProfile("b"); repository.setLinkedHealthProfile("a") },
                { repository.restoreProfile(repository.peekProfile("a")) },
                { repository.replaceVitals("a", ProfileVitals()) },
                { repository.restoreWithVitals(repository.peekProfile("a")); Unit },
                {
                    val profile = repository.peekProfile("a").profile
                    repository.deleteProfile("a")
                    repository.createProfile(profile)
                    repository.setLinkedHealthProfile("a")
                },
            )
            for (change in changes) {
                val context = repository.captureVitalsSync()
                change()
                val before = repository.peekProfile("a").vitals
                assertFailsWith<IllegalStateException> { repository.ingestDeviceVitals(context, batch, now) }
                assertEquals(before, repository.peekProfile("a").vitals)
                assertEquals(ProfileVitals(), repository.peekProfile("b").vitals)
            }
        }
    }

    @Test
    fun successfulCommitRejectsOverlappingSameProfileFetchWithoutAdvancingState() = runTest {
        for (repository in repositories()) {
            val first = repository.captureVitalsSync()
            val overlapping = repository.captureVitalsSync()
            assertEquals(1, repository.ingestDeviceVitals(first, batch, now))
            val saved = repository.peekProfile("a").vitals
            assertFailsWith<IllegalStateException> {
                repository.ingestDeviceVitals(overlapping, batch.copy(steps = emptyList()), Instant.parse("2026-11-02T12:00:00Z"))
            }
            assertEquals(saved, repository.peekProfile("a").vitals)
            assertEquals(now, saved.lastSync)
            assertEquals(1, repository.ingestDeviceVitals(repository.captureVitalsSync(), batch, now))
        }
    }

    @Test
    fun stalePersistentCommitWritesNothingAndFailedSaveKeepsContextRetryable() = runTest {
        val vault = GenerationalMemoryVault()
        val repository = VaultBackedRepository(vault)
        repository.load()
        repository.createProfile(Profile("a", "A", createdAt = Instant.fromEpochSeconds(1)))
        val context = repository.captureVitalsSync()
        vault.failingWrites += "repository-vitals-a-v1"
        assertFailsWith<IllegalStateException> { repository.ingestDeviceVitals(context, batch, now) }
        assertEquals(ProfileVitals(), repository.peekProfile("a").vitals)
        vault.failingWrites.clear()
        assertEquals(1, repository.ingestDeviceVitals(context, batch, now))
        val writes = vault.writes.toList()
        assertFailsWith<IllegalStateException> { repository.ingestDeviceVitals(context, batch, now) }
        assertEquals(writes, vault.writes)
    }

    @Test
    fun defaultLinkRoundTripViaProfileDatesInvalidatesContext() = runTest {
        for (repository in repositories()) {
            val context = repository.captureVitalsSync()
            val other = repository.peekProfile("b").profile
            repository.updateProfile(other.copy(createdAt = Instant.fromEpochSeconds(0)))
            repository.updateProfile(other)
            assertEquals("a", repository.linkedHealthProfileId())
            assertFailsWith<IllegalStateException> { repository.ingestDeviceVitals(context, batch, now) }
            assertEquals(ProfileVitals(), repository.peekProfile("a").vitals)
        }
    }

    @Test
    fun onboardingLinkIsConditionalAndCapturedContextRejectsPermissionDelayAndAba() = runTest {
        for (repository in repositories()) {
            val expected = kotlin.test.assertNotNull(repository.linkOnboardingHealthProfile("a"))
            assertEquals("a", repository.explicitHealthLinkId())
            repository.setLinkedHealthProfile("b")
            kotlin.test.assertNull(repository.linkOnboardingHealthProfile("a"))
            assertEquals("b", repository.explicitHealthLinkId())
            var reads = 0
            val source = object : app.vezuna.health.DeviceVitalsSource {
                override suspend fun read(request: app.vezuna.health.VitalsReadRequest): VitalsBatch {
                    reads++
                    return batch
                }
            }
            val sync = app.vezuna.health.DeviceVitalsSync(source, repository, { now })
            assertFailsWith<IllegalStateException> { sync.synchronize(expected) }
            repository.setLinkedHealthProfile("a")
            assertFailsWith<IllegalStateException> { sync.synchronize(expected) }
            assertEquals(0, reads)
            assertEquals(1, sync.synchronize())
            assertEquals(1, reads)
        }
    }

    @Test
    fun cancellationAfterPlatformReadDoesNotCommit() = runTest {
        for (repository in repositories()) {
            val expected = kotlin.test.assertNotNull(repository.linkOnboardingHealthProfile("a"))
            val source = object : app.vezuna.health.DeviceVitalsSource {
                override suspend fun read(request: app.vezuna.health.VitalsReadRequest): VitalsBatch {
                    currentCoroutineContext().cancel()
                    return batch
                }
            }
            val job = launch { app.vezuna.health.DeviceVitalsSync(source, repository, { now }).synchronize(expected) }
            job.join()
            kotlin.test.assertTrue(job.isCancelled)
            assertEquals(ProfileVitals(), repository.peekProfile("a").vitals)
            assertEquals(expected, repository.captureVitalsSync(expected.zone))
        }
    }

    @Test
    fun relinkWhileReadingRejectsCommitAndReadUsesCapturedZone() = runTest {
        for (repository in repositories()) {
            val zone = TimeZone.of("America/Sao_Paulo")
            val expected = kotlin.test.assertNotNull(repository.linkOnboardingHealthProfile("a", zone))
            val reading = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val source = object : app.vezuna.health.DeviceVitalsSource {
                override suspend fun read(request: app.vezuna.health.VitalsReadRequest): VitalsBatch {
                    assertEquals(zone, request.zone)
                    reading.complete(Unit)
                    resume.await()
                    return batch
                }
            }
            val sync = app.vezuna.health.DeviceVitalsSync(source, repository, { now }, { TimeZone.UTC })
            val job = launch { assertFailsWith<IllegalStateException> { sync.synchronize(expected) } }
            withTimeout(5_000) { reading.await() }
            repository.setLinkedHealthProfile("b")
            resume.complete(Unit)
            withTimeout(5_000) { job.join() }
            assertEquals(ProfileVitals(), repository.peekProfile("a").vitals)
            assertEquals(ProfileVitals(), repository.peekProfile("b").vitals)
        }
    }

    @Test
    fun noOpRelinkAndUnrelatedProfileWritesKeepContextValid() = runTest {
        for (repository in repositories()) {
            val context = repository.captureVitalsSync()
            repository.setLinkedHealthProfile("a")
            repository.updateProfile(repository.peekProfile("b").profile.copy(displayName = "Other"))
            repository.replaceVitals("b", ProfileVitals())
            assertEquals(1, repository.ingestDeviceVitals(context, batch, now))
        }
    }
    @Test
    fun exactCommitSuccessorPreservesZoneAndRejectsRelinkedCleanup() = runTest {
        for (repository in repositories()) {
            val zone = TimeZone.of("America/Sao_Paulo")
            val original = repository.captureVitalsSync(zone)
            val commit = repository.ingestDeviceVitalsAndCapture(original, batch, now)
            assertEquals(1, commit.storedCount)
            assertEquals(original.copy(generation = original.generation + 1, lastSync = now), commit.context)
            var deletes = 0
            repository.withValidatedVitalsSync(commit.context) { deletes++ }
            assertEquals(1, deletes)
            repository.setLinkedHealthProfile("b")
            repository.setLinkedHealthProfile("a")
            assertFailsWith<IllegalStateException> { repository.withValidatedVitalsSync(commit.context) { deletes++ } }
            assertEquals(1, deletes)
        }
    }

    @Test
    fun linkBookkeepingFailureAfterDurableCommitDoesNotReturnSuccessorOrCleanup() = runTest {
        val vault = GenerationalMemoryVault()
        val repository = VaultBackedRepository(vault)
        repository.load()
        repository.createProfile(Profile("a", "A", createdAt = Instant.fromEpochSeconds(1)))
        val original = repository.captureVitalsSync()
        vault.failingWrites += "repository-health-link-v1"
        assertFailsWith<IllegalStateException> { repository.ingestDeviceVitalsAndCapture(original, batch, now) }
        // Vitals were already durable. Failure is not rollback and the original context cannot retry.
        assertEquals(now, repository.peekProfile("a").vitals.lastSync)
        assertEquals(now, VaultBackedRepository(vault).also { it.load() }.peekProfile("a").vitals.lastSync)
        assertFailsWith<IllegalStateException> { repository.captureVitalsSync() }
        assertFailsWith<IllegalStateException> { repository.ingestDeviceVitalsAndCapture(original, batch, now) }
    }

}
