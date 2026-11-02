package app.vezuna

import app.vezuna.data.VaultBackedRepository
import app.vezuna.domain.StepsDay
import app.vezuna.domain.VitalsBatch
import app.vezuna.health.DeviceVitalsSource
import app.vezuna.health.DeviceVitalsSync
import app.vezuna.health.VitalsReadRequest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import app.vezuna.domain.Profile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class PersistedSelectionTest {
    private val vault = GenerationalMemoryVault()
    private val created = Instant.fromEpochSeconds(1_700_000_000)

    private suspend fun open() = VaultBackedRepository(vault, persistSelection = true).also { it.load() }

    private fun VaultBackedRepository.ids() = profiles.value.map { it.profileId }

    @Test
    fun selectionSurvivesReopening() = runTest {
        val first = open()
        first.createProfile(Profile("a", "Ana", createdAt = created))
        first.createProfile(Profile("z", "Zed", createdAt = created))
        first.readProfile("z")

        assertEquals(listOf("z", "a"), open().ids())
    }

    @Test
    fun staleSelectionFallsBackToNameOrder() = runTest {
        val first = open()
        first.createProfile(Profile("a", "Ana", createdAt = created))
        first.createProfile(Profile("z", "Zed", createdAt = created))
        vault.write("repository-selected-profile-v1", "gone".encodeToByteArray())
        assertEquals(listOf("a", "z"), open().ids())

        vault.write("repository-selected-profile-v1", ByteArray(0))
        assertEquals(listOf("a", "z"), open().ids())
    }

    @Test
    fun missingSelectionFileFallsBackToNameOrder() = runTest {
        val first = open()
        first.createProfile(Profile("a", "Ana", createdAt = created))
        first.createProfile(Profile("z", "Zed", createdAt = created))
        first.readProfile("z")
        vault.current.remove("repository-selected-profile-v1")
        vault.previous.remove("repository-selected-profile-v1")

        assertEquals(listOf("a", "z"), open().ids())
    }

    private class FakeSource : DeviceVitalsSource {
        override suspend fun read(request: VitalsReadRequest) =
            VitalsBatch(request.replaceFrom, steps = listOf(StepsDay(LocalDate(2026, 10, 31), 100)))
    }

    @Test
    fun deviceVitalsSyncKeepsTheSelectionAndFillsOnlyTheLinkedProfile() = runTest {
        val first = open()
        first.createProfile(Profile("me", "Me", createdAt = Instant.fromEpochSeconds(1_600_000_000)))
        first.createProfile(Profile("z", "Zed", createdAt = created))
        first.readProfile("z")

        val repository = open()
        assertEquals("me", repository.linkedHealthProfileId())
        val now = Instant.parse("2026-11-01T12:00:00Z")
        DeviceVitalsSync(FakeSource(), repository, { now }, { TimeZone.UTC }).synchronize()

        val reopened = open()
        assertEquals("z", reopened.ids().first())
        assertEquals("me", reopened.linkedHealthProfileId())
        assertEquals(1, reopened.peekProfile("me").vitals.steps.size)
        assertTrue(reopened.peekProfile("z").vitals.isEmpty)
    }

    @Test
    fun failedSelectionWriteWarnsAndStillOpensTheProfile() = runTest {
        val first = open()
        first.createProfile(Profile("a", "Ana", createdAt = created))
        first.createProfile(Profile("z", "Zed", createdAt = created))
        val warnings = mutableListOf<String>()
        val repository = VaultBackedRepository(vault, persistSelection = true, warn = { m, _ -> warnings += m }).also { it.load() }
        vault.failingWrites += "repository-selected-profile-v1"

        assertEquals("z", repository.readProfile("z").profile.profileId)
        assertEquals(1, warnings.size)
    }
}
