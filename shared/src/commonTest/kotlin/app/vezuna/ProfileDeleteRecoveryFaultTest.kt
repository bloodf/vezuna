package app.vezuna

import app.vezuna.data.VaultBackedRepository
import app.vezuna.domain.Profile
import app.vezuna.security.SecureVault
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Issue 135 compound faults. Snapshots contain only bytes durable before the caller receives success. */
class ProfileDeleteRecoveryFaultTest {
    private val index = "repository-profile-index-v1"
    private val deleted = "repository-deleted-profiles-v1"
    private val record = "repository-profile-target-v1"
    private val vitals = "repository-vitals-target-v1"
    private val clinical = sampleProfileData("DELETE-FAULT")

    private suspend fun seed(vault: SecureVault) = VaultBackedRepository(vault).also {
        it.load()
        it.createProfile(Profile("live", "Live", createdAt = Instant.parse("2026-01-01T00:00:00Z")))
        it.createProfile(Profile("target", "Target", createdAt = clinical.profile.createdAt))
        it.replaceVitals("target", clinical.vitals)
    }

    private fun copy(vault: GenerationalMemoryVault) = GenerationalMemoryVault().also { snapshot ->
        vault.current.forEach { (key, bytes) -> snapshot.current[key] = bytes.copyOf() }
        vault.previous.forEach { (key, bytes) -> snapshot.previous[key] = bytes.copyOf() }
        vault.quarantined.forEach { (key, bytes) -> snapshot.quarantined[key] = bytes.copyOf() }
    }

    private suspend fun reopen(vault: GenerationalMemoryVault) = VaultBackedRepository(vault).also { it.load() }
    private fun ids(repository: VaultBackedRepository) = repository.profiles.value.map { it.profileId }.sorted()

    // Capture before index write or before the first cleanup delete. No exception runs rollback code.
    // The original timeline subsequently finishes, but is discarded; only the deep copy is reopened.
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
        repository.createProfile(Profile("earlier", "Earlier", createdAt = clinical.profile.createdAt))
        repository.deleteProfile("earlier")
        armed = true
        repository.deleteProfile("target")
        return assertNotNull(snapshot).also {
            assertNotNull(it.current[deleted])
            assertNotNull(it.current[record])
            assertNotNull(it.current[vitals])
            assertNull(disk.read(record), "discarded timeline completed; snapshot must not share its maps")
        }
    }

    @Test
    fun acknowledgedDeleteDoesNotResurrectWithStaleTombstonesAndCorruptIndex() = runTest {
        val disk = GenerationalMemoryVault()
        val repository = seed(disk)
        // Give the tombstone a real previous generation, which cannot name the later target deletion.
        repository.createProfile(Profile("earlier", "Earlier", createdAt = clinical.profile.createdAt))
        repository.deleteProfile("earlier")
        repository.deleteProfile("target") // returned success; both generations of clinical files are gone
        assertNull(disk.read(record))
        assertNull(disk.read(vitals))
        assertNotNull(disk.previous[deleted])
        val liveKey = "repository-profile-live-v1"
        val liveBytes = disk.current.getValue(liveKey).copyOf()
        val previousLive = disk.previous[liveKey]?.copyOf()
        disk.current[deleted] = byteArrayOf(0)
        disk.current[index] = byteArrayOf(0)

        repeat(2) {
            val reopened = reopen(disk)
            // Unknown history also hides the live profile listed by the nonauthoritative fallback index.
            assertEquals(emptyList(), ids(reopened))
            assertNull(reopened.linkedHealthProfileId())
            assertContentEquals(liveBytes, disk.current[liveKey])
            assertContentEquals(previousLive, disk.previous[liveKey])
            assertNull(disk.read(record))
            assertNull(disk.read(vitals))
        }
    }

    @Test
    fun liveClinicalOrphanIsRecoveredFromPreviousIndex() = runTest {
        val disk = GenerationalMemoryVault()
        seed(disk)
        val recordBytes = disk.current.getValue(record).copyOf()
        val vitalsBytes = disk.current.getValue(vitals).copyOf()
        disk.current[index] = byteArrayOf(0)

        val reopened = reopen(disk)

        assertEquals(listOf("live", "target"), ids(reopened))
        assertTrue(reopened.openedFromPreviousGeneration.value)
        assertEquals(clinical.vitals.copy(lastSync = null), reopened.readProfile("target").vitals)
        assertContentEquals(recordBytes, disk.current[record])
        assertContentEquals(vitalsBytes, disk.current[vitals])
    }

    @Test
    fun processLossBeforeIndexWriteWithHealthyIndexKeepsUncertainDeletionLive() = runTest {
        val disk = interrupted(beforeIndex = true)
        val recordBytes = disk.current.getValue(record).copyOf()
        val vitalsBytes = disk.current.getValue(vitals).copyOf()

        repeat(2) {
            val reopened = reopen(disk)
            assertEquals(listOf("live", "target"), ids(reopened))
            assertEquals(clinical.vitals.copy(lastSync = null), reopened.readProfile("target").vitals)
            assertContentEquals(recordBytes, disk.current[record])
            assertContentEquals(vitalsBytes, disk.current[vitals])
        }
    }

    @Test
    fun processLossAfterIndexWriteWithCurrentTombstoneDoesNotRecoverTarget() = runTest {
        val disk = interrupted(beforeIndex = false)
        val recordBytes = disk.current.getValue(record).copyOf()
        disk.current[index] = byteArrayOf(0)

        repeat(2) {
            val reopened = reopen(disk)
            assertEquals(listOf("live"), ids(reopened))
            assertTrue(reopened.unavailableProfiles.isEmpty())
            assertContentEquals(recordBytes, disk.current[record], "encrypted record stays as evidence")
        }
    }

    @Test
    fun unreadableTombstoneKeepsUnlistedRecordOnlyOrphanBytesForLaterRecovery() = runTest {
        val disk = GenerationalMemoryVault()
        val repository = reopen(disk)
        repository.createProfile(Profile("live", "Live", createdAt = clinical.profile.createdAt))
        repository.createProfile(Profile("orphan", "Orphan", createdAt = clinical.profile.createdAt))
        val key = "repository-profile-orphan-v1"
        val bytes = disk.current.getValue(key).copyOf()
        val liveKey = "repository-profile-live-v1"
        val liveBytes = disk.current.getValue(liveKey).copyOf()
        val previousLive = disk.previous[liveKey]?.copyOf()
        disk.current[deleted] = byteArrayOf(0)
        disk.current[index] = byteArrayOf(0)

        val reopened = reopen(disk)

        // A listed live record has no stronger deletion evidence than the unlisted orphan in this compound fault.
        assertEquals(emptyList(), ids(reopened))
        assertNull(reopened.linkedHealthProfileId())
        assertContentEquals(liveBytes, disk.current[liveKey])
        assertContentEquals(previousLive, disk.previous[liveKey])
        assertFalse(reopened.profiles.value.any { it.profileId == "orphan" })
        assertContentEquals(bytes, disk.current[key], "fail closed suppresses recovery, not durable record bytes")
    }
}
