package app.vezuna

import app.vezuna.data.VaultBackedRepository
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Unknown deletion history grants no automatic fallback recovery, including otherwise valid live profiles. */
class ProfileUnknownTombstoneEligibilityTest {
    private val index = "repository-profile-index-v1"
    private val deleted = "repository-deleted-profiles-v1"
    private val clinical = sampleProfileData("UNKNOWN-TOMBSTONE").vitals.copy(lastSync = null)
    private val created = Instant.parse("2026-01-01T00:00:00Z")
    private enum class History { CORRUPT, PREVIOUS, RESET, COMPLETE }
    private data class Fixture(val disk: GenerationalMemoryVault, val backup: ProfileData)

    private suspend fun fixture(listed: Boolean, withVitals: Boolean, history: History, corruptIndex: Boolean = true): Fixture {
        // This helper has the Android file-store semantics: read silently serves previous-only bytes,
        // readGeneration identifies them, and quarantineCurrent leaves .prev addressable on later launches.
        val disk = GenerationalMemoryVault()
        val repository = VaultBackedRepository(disk, persistSelection = true).also { it.load() }
        repository.createProfile(Profile("live", "Live", createdAt = created))
        repository.replaceVitals("live", clinical)
        val withoutTarget = disk.current.getValue(index).copyOf()
        repository.createProfile(Profile("target", "Target", createdAt = created))
        if (withVitals) {
            repository.replaceVitals("target", clinical.copy(weights = emptyList()))
            repository.replaceVitals("target", clinical)
        }
        repository.updateProfile(repository.readProfile("target").profile.copy(displayName = "Target updated"))
        val backup = repository.readProfile("target")
        repository.setLinkedHealthProfile("target")
        // Produce actual current and previous tombstone documents through repository deletion.
        for (id in listOf("earlier", "later")) {
            repository.createProfile(Profile(id, id, createdAt = created))
            repository.deleteProfile(id)
        }
        when (history) {
            History.CORRUPT -> {
                disk.current[deleted] = byteArrayOf(0)
                disk.previous.remove(deleted)
            }
            History.PREVIOUS -> disk.current[deleted] = byteArrayOf(0)
            History.RESET -> {
                disk.current[deleted] = byteArrayOf(0)
                disk.previous.remove(deleted)
                val reopened = VaultBackedRepository(disk).also { it.load() }
                reopened.createProfile(Profile("reset", "Reset", createdAt = created))
                reopened.deleteProfile("reset")
            }
            History.COMPLETE -> Unit
        }
        if (corruptIndex) {
            disk.previous[index] = if (listed) disk.current.getValue(index).copyOf() else withoutTarget
            disk.current[index] = byteArrayOf(0)
        }
        return Fixture(disk, backup)
    }

    private fun evidence(disk: GenerationalMemoryVault): Map<String, ByteArray> = buildMap {
        for ((generation, files) in listOf("current" to disk.current, "previous" to disk.previous)) {
            for ((key, bytes) in files) {
                if (key.startsWith("repository-profile-") && key != index ||
                    key.startsWith("repository-vitals-") && key != "repository-vitals-index-v1") {
                    put("$generation/$key", bytes.copyOf())
                }
            }
        }
    }

    private fun assertEvidence(expected: Map<String, ByteArray>, disk: GenerationalMemoryVault) {
        val actual = evidence(disk)
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (key, bytes) -> assertContentEquals(bytes, actual.getValue(key), key) }
    }

    private suspend fun assertHidden(repository: VaultBackedRepository) {
        assertEquals(emptyList(), repository.profiles.value)
        assertTrue(repository.unavailableProfiles.isEmpty())
        assertNull(repository.linkedHealthProfileId())
        assertNull(repository.explicitHealthLinkId())
        for (id in listOf("live", "target")) {
            assertFailsWith<NoSuchElementException> { repository.readProfile(id) }
            assertFailsWith<IllegalArgumentException> { repository.setLinkedHealthProfile(id) }
        }
    }

    private suspend fun hiddenOnThreeLaunches(listed: Boolean, withVitals: Boolean, history: History) {
        val (disk) = fixture(listed, withVitals, history)
        val saved = evidence(disk)
        repeat(3) { launch ->
            assertHidden(VaultBackedRepository(disk, persistSelection = true).also { it.load() })
            assertEvidence(saved, disk)
            // No startup repair is allowed to promote the uncertain fallback index to current authority.
            assertNull(disk.current[index], "launch $launch must retain fallback provenance")
            assertNotNull(disk.previous[index])
        }
    }

    @Test fun corruptHistoryHidesListedClinical() = runTest { hiddenOnThreeLaunches(true, true, History.CORRUPT) }
    @Test fun corruptHistoryHidesUnlistedClinical() = runTest { hiddenOnThreeLaunches(false, true, History.CORRUPT) }
    @Test fun corruptHistoryHidesListedRecordOnly() = runTest { hiddenOnThreeLaunches(true, false, History.CORRUPT) }
    @Test fun corruptHistoryHidesUnlistedRecordOnly() = runTest { hiddenOnThreeLaunches(false, false, History.CORRUPT) }
    @Test fun previousHistoryHidesListedClinical() = runTest { hiddenOnThreeLaunches(true, true, History.PREVIOUS) }
    @Test fun previousHistoryHidesUnlistedClinical() = runTest { hiddenOnThreeLaunches(false, true, History.PREVIOUS) }
    @Test fun previousHistoryHidesListedRecordOnly() = runTest { hiddenOnThreeLaunches(true, false, History.PREVIOUS) }
    @Test fun previousHistoryHidesUnlistedRecordOnly() = runTest { hiddenOnThreeLaunches(false, false, History.PREVIOUS) }
    @Test fun resetHistoryHidesListedClinical() = runTest { hiddenOnThreeLaunches(true, true, History.RESET) }
    @Test fun resetHistoryHidesUnlistedClinical() = runTest { hiddenOnThreeLaunches(false, true, History.RESET) }
    @Test fun resetHistoryHidesListedRecordOnly() = runTest { hiddenOnThreeLaunches(true, false, History.RESET) }
    @Test fun resetHistoryHidesUnlistedRecordOnly() = runTest { hiddenOnThreeLaunches(false, false, History.RESET) }

    @Test fun healthyCurrentIndexRemainsAuthoritativeWithUnknownOrResetHistory() = runTest {
        for (history in listOf(History.CORRUPT, History.PREVIOUS, History.RESET)) {
            val (disk) = fixture(true, true, history, corruptIndex = false)
            val saved = evidence(disk)
            repeat(3) {
                val repository = VaultBackedRepository(disk).also { it.load() }
                assertEquals(listOf("live", "target"), repository.profiles.value.map { it.profileId }.sorted())
                assertEquals(clinical, repository.readProfile("target").vitals)
                assertEquals("target", repository.linkedHealthProfileId())
                assertEvidence(saved, disk)
            }
        }
    }

    @Test fun completeCurrentHistoryStillRecoversLiveListedAndUnlistedClinicalProfiles() = runTest {
        for (listed in listOf(true, false)) {
            val (disk) = fixture(listed, true, History.COMPLETE)
            val saved = evidence(disk)
            repeat(3) {
                val repository = VaultBackedRepository(disk).also { it.load() }
                assertEquals(listOf("live", "target"), repository.profiles.value.map { it.profileId }.sorted())
                assertEquals(clinical, repository.readProfile("target").vitals)
                assertEvidence(saved, disk)
            }
        }
    }

    @Test fun newDeletionRetainsResetAndCannotReviveOldCandidates() = runTest {
        val (disk) = fixture(true, true, History.CORRUPT)
        val saved = evidence(disk)
        val reopened = VaultBackedRepository(disk).also { it.load() }
        assertHidden(reopened)
        reopened.createProfile(Profile("new", "New", createdAt = created))
        reopened.createProfile(Profile("delete", "Delete", createdAt = created))
        reopened.deleteProfile("delete")
        val resetBytes = disk.current.getValue(deleted).copyOf()
        // Real later index, listing a known live new profile, is still uncertain when read as fallback.
        disk.previous[index] = disk.current.getValue(index).copyOf()
        disk.current[index] = byteArrayOf(0)
        repeat(3) {
            val again = VaultBackedRepository(disk).also { it.load() }
            assertHidden(again)
            assertFailsWith<NoSuchElementException> { again.readProfile("new") }
            assertContentEquals(resetBytes, disk.current[deleted])
            saved.forEach { (key, bytes) -> assertContentEquals(bytes, evidence(disk).getValue(key), key) }
        }
    }

    @Test fun explicitBackupRestoreCanPublishOnlyTheRestoredProfile() = runTest {
        val (disk, backup) = fixture(true, true, History.PREVIOUS)
        val saved = evidence(disk)
        val repository = VaultBackedRepository(disk).also { it.load() }
        assertHidden(repository)
        assertEvidence(saved, disk)
        repository.restoreProfile(backup)
        assertEquals(listOf("target"), repository.profiles.value.map { it.profileId })
        assertEquals(clinical, repository.readProfile("target").vitals)
        repeat(3) {
            val again = VaultBackedRepository(disk).also { it.load() }
            assertEquals(listOf("target"), again.profiles.value.map { it.profileId })
            assertEquals(clinical, again.readProfile("target").vitals)
            assertFailsWith<NoSuchElementException> { again.readProfile("live") }
        }
    }
}
