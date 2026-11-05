package app.vezuna

import app.vezuna.data.VaultBackedRepository
import app.vezuna.domain.Profile
import app.vezuna.security.SecureVault
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Retaining uncertain encrypted evidence must neither publish a deleted profile nor prevent completed cleanup. */
class ProfileDeleteVitalsPreservationTest {
    private val index = "repository-profile-index-v1"
    private val deleted = "repository-deleted-profiles-v1"
    private val record = "repository-profile-target-v1"
    private val vitals = "repository-vitals-target-v1"
    private val clinical = sampleProfileData("DELETE-PRESERVATION").vitals

    private suspend fun seed(vault: SecureVault) = VaultBackedRepository(vault).also {
        it.load()
        it.createProfile(Profile("live", "Live", createdAt = Instant.parse("2026-01-01T00:00:00Z")))
        it.createProfile(Profile("target", "Target", createdAt = Instant.parse("2026-01-02T00:00:00Z")))
        it.replaceVitals("target", clinical.copy(weights = emptyList()))
        it.replaceVitals("target", clinical)
        it.updateProfile(it.readProfile("target").profile.copy(displayName = "Target updated"))
        // A real previous tombstone generation predates the target's deletion.
        it.createProfile(Profile("earlier", "Earlier", createdAt = Instant.parse("2026-01-03T00:00:00Z")))
        it.deleteProfile("earlier")
    }

    private fun copy(disk: GenerationalMemoryVault) = GenerationalMemoryVault().also { snapshot ->
        disk.current.forEach { (key, bytes) -> snapshot.current[key] = bytes.copyOf() }
        disk.previous.forEach { (key, bytes) -> snapshot.previous[key] = bytes.copyOf() }
        disk.quarantined.forEach { (key, bytes) -> snapshot.quarantined[key] = bytes.copyOf() }
    }

    // Capture real persisted bytes without throwing through deleteProfile's rollback path.
    // The original deletion finishes; only its independent snapshot is subsequently reopened.
    private suspend fun interrupted(beforeIndex: Boolean): GenerationalMemoryVault {
        val disk = GenerationalMemoryVault()
        var armed = false
        var snapshot: GenerationalMemoryVault? = null
        val observed = object : SecureVault by disk {
            override suspend fun write(key: String, ciphertext: ByteArray) {
                if (armed && beforeIndex && key == index && snapshot == null) snapshot = copy(disk)
                disk.write(key, ciphertext)
            }

            override suspend fun delete(key: String) {
                if (armed && !beforeIndex && snapshot == null) snapshot = copy(disk)
                disk.delete(key)
            }
        }
        val repository = seed(observed)
        assertEquals(clinical.copy(lastSync = null), repository.readProfile("target").vitals)
        armed = true
        repository.deleteProfile("target")
        assertNull(disk.read(record))
        assertNull(disk.read(vitals))
        return assertNotNull(snapshot).also {
            assertNotNull(it.current[record])
            assertNotNull(it.previous[record])
            assertNotNull(it.current[vitals])
            assertNotNull(it.previous[vitals])
            assertNotNull(it.current[deleted])
            assertNotNull(it.previous[deleted])
            it.current[index] = byteArrayOf(0)
        }
    }

    private suspend fun assertHidden(repository: VaultBackedRepository) {
        assertEquals(listOf("live"), repository.profiles.value.map { it.profileId })
        assertTrue(repository.unavailableProfiles.isEmpty())
        assertEquals("live", repository.linkedHealthProfileId())
        assertNull(repository.explicitHealthLinkId())
        assertFailsWith<NoSuchElementException> { repository.readProfile("target") }
    }

    private suspend fun assertPreserved(disk: GenerationalMemoryVault) {
        val before = copy(disk)
        repeat(2) { launch ->
            val repository = VaultBackedRepository(disk).also { it.load() }
            assertHidden(repository)
            assertContentEquals(before.current[deleted], disk.current[deleted], "current tombstone on launch $launch")
            assertContentEquals(before.previous[deleted], disk.previous[deleted], "previous tombstone on launch $launch")
            assertContentEquals(before.current[record], disk.current[record], "current record on launch $launch")
            assertContentEquals(before.previous[record], disk.previous[record], "previous record on launch $launch")
            assertContentEquals(before.current[vitals], disk.current[vitals], "current vitals on launch $launch")
            assertContentEquals(before.previous[vitals], disk.previous[vitals], "previous vitals on launch $launch")
        }
    }

    @Test
    fun preindexSnapshotPreservesHiddenVitalsOnEveryLaunch() = runTest {
        assertPreserved(interrupted(beforeIndex = true))
    }

    @Test
    fun postindexSnapshotPreservesHiddenVitalsOnEveryLaunch() = runTest {
        assertPreserved(interrupted(beforeIndex = false))
    }

    @Test
    fun previousOnlyRecordPreservesHiddenVitalsOnEveryLaunch() = runTest {
        val disk = interrupted(beforeIndex = false)
        disk.current.remove(record)
        assertContentEquals(disk.previous.getValue(record), disk.read(record))
        assertPreserved(disk)
    }

    @Test
    fun corruptRecordPreservesHiddenVitalsWithoutDecodingOnEveryLaunch() = runTest {
        val disk = interrupted(beforeIndex = false)
        disk.current[record] = byteArrayOf(0)
        disk.previous[record] = byteArrayOf(1)
        assertPreserved(disk)
    }

    @Test
    fun recordPresenceReadFailureAbortsBeforeDeletingVitals() = runTest {
        val disk = interrupted(beforeIndex = false)
        val before = copy(disk)
        val failure = IllegalStateException("record presence read failed")
        val unreadable = object : SecureVault by disk {
            override suspend fun read(key: String): ByteArray? {
                if (key == record) throw failure
                return disk.read(key)
            }
        }
        repeat(2) {
            assertSame(failure, assertFailsWith<IllegalStateException> { VaultBackedRepository(unreadable).load() })
            assertContentEquals(before.current[vitals], disk.current[vitals])
            assertContentEquals(before.previous[vitals], disk.previous[vitals])
            assertContentEquals(before.current[record], disk.current[record])
            assertContentEquals(before.previous[record], disk.previous[record])
            assertContentEquals(before.current[deleted], disk.current[deleted])
        }
    }

    @Test
    fun absentRecordGenerationsAllowLeftoverVitalsCleanup() = runTest {
        val disk = interrupted(beforeIndex = false)
        disk.delete(record)
        assertNull(disk.current[record])
        assertNull(disk.previous[record])
        assertNotNull(disk.current[vitals])
        assertNotNull(disk.previous[vitals])
        repeat(2) {
            assertHidden(VaultBackedRepository(disk).also { it.load() })
            assertNull(disk.current[vitals])
            assertNull(disk.previous[vitals])
        }
    }

    @Test
    fun successfulDeletionRemovesBothGenerationsAndCannotReviveTarget() = runTest {
        val disk = GenerationalMemoryVault()
        val repository = seed(disk)
        assertNotNull(disk.current[record])
        assertNotNull(disk.previous[record])
        assertNotNull(disk.current[vitals])
        assertNotNull(disk.previous[vitals])
        repository.deleteProfile("target")
        assertNull(disk.current[record])
        assertNull(disk.previous[record])
        assertNull(disk.current[vitals])
        assertNull(disk.previous[vitals])
        assertNotNull(disk.previous[deleted])
        val liveKey = "repository-profile-live-v1"
        val liveBytes = disk.current.getValue(liveKey).copyOf()
        val previousLive = disk.previous[liveKey]?.copyOf()
        disk.current[deleted] = byteArrayOf(0)
        disk.current[index] = byteArrayOf(0)
        repeat(2) {
            val reopened = VaultBackedRepository(disk).also { it.load() }
            // The successful target deletion remains complete; lost history separately hides the live fallback entry.
            assertEquals(emptyList(), reopened.profiles.value)
            assertNull(reopened.linkedHealthProfileId())
            assertContentEquals(liveBytes, disk.current[liveKey])
            assertContentEquals(previousLive, disk.previous[liveKey])
            assertNull(disk.current[record])
            assertNull(disk.previous[record])
            assertNull(disk.current[vitals])
            assertNull(disk.previous[vitals])
        }
    }
}
