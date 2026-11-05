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
    fun noOpRelinkAndUnrelatedProfileWritesKeepContextValid() = runTest {
        for (repository in repositories()) {
            val context = repository.captureVitalsSync()
            repository.setLinkedHealthProfile("a")
            repository.updateProfile(repository.peekProfile("b").profile.copy(displayName = "Other"))
            repository.replaceVitals("b", ProfileVitals())
            assertEquals(1, repository.ingestDeviceVitals(context, batch, now))
        }
    }
}
