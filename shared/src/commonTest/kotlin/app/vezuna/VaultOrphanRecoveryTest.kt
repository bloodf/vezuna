package app.vezuna

import app.vezuna.data.VaultBackedRepository
import app.vezuna.data.VaultSourceFileStore
import app.vezuna.domain.Profile
import app.vezuna.security.CipherEnvelopeCodec
import app.vezuna.security.VaultFailure
import app.vezuna.security.VaultUnreadableException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private val created = Instant.parse("2026-01-01T00:00:00Z")
private const val INDEX_KEY = "repository-profile-index-v1"
private fun recordKey(id: String) = "repository-profile-$id-v1"

/** Profiles missing from a previous-generation index (issue 135) and the guards that keep deletes deleted. */
class VaultOrphanRecoveryTest {
    private val vault = GenerationalMemoryVault()

    private suspend fun open() = VaultBackedRepository(vault).also { it.load() }

    private fun ids(repository: VaultBackedRepository) = repository.profiles.value.map { it.profileId }.sorted()

    @Test
    fun profileWithoutVitalsCreatedBeforeACorruptIndexWriteIsRecovered() = runTest {
        val repository = open()
        repository.createProfile(Profile("a", "Ana", createdAt = created))
        repository.createProfile(Profile("p", "Pia", createdAt = created))
        vault.current[INDEX_KEY] = "garbage".encodeToByteArray()

        val reopened = open()

        assertTrue(reopened.openedFromPreviousGeneration.value)
        assertEquals(listOf("a", "p"), ids(reopened))
        assertEquals("Pia", reopened.readProfile("p").profile.displayName)
        // The index was healed, so the next start needs no fallback.
        val again = open()
        assertEquals(listOf("a", "p"), ids(again))
        assertTrue(again.unavailableProfiles.isEmpty())
    }

    @Test
    fun recoveryIsIdempotentAndKeepsRecordBytes() = runTest {
        val repository = open()
        repository.createProfile(Profile("a", "Ana", createdAt = created))
        repository.createProfile(Profile("p", "Pia", createdAt = created))
        val recordBefore = vault.current.getValue(recordKey("p")).copyOf()
        vault.current[INDEX_KEY] = "garbage".encodeToByteArray()

        repeat(3) { assertEquals(listOf("a", "p"), ids(open())) }

        assertContentEquals(recordBefore, vault.current.getValue(recordKey("p")))
    }

    @Test
    fun deletedProfileWhoseRecordSurvivedIsNotResurrected() = runTest {
        val repository = open()
        repository.createProfile(Profile("a", "Ana", createdAt = created))
        repository.createProfile(Profile("b", "Bia", createdAt = created))
        val survivingRecord = vault.current.getValue(recordKey("b")).copyOf()
        repository.deleteProfile("b")
        // The record delete failed after the index was rewritten: the file is still on disk.
        vault.current[recordKey("b")] = survivingRecord
        // Later writes push the b-less index into the previous slot, then the newest index is corrupted.
        repository.createProfile(Profile("c", "Cy", createdAt = created))
        vault.current[INDEX_KEY] = "garbage".encodeToByteArray()

        val reopened = open()

        assertTrue(reopened.openedFromPreviousGeneration.value)
        assertEquals(listOf("a", "c"), ids(reopened))
        assertContentEquals(survivingRecord, vault.current.getValue(recordKey("b")), "the file is left alone, not deleted")
    }

    @Test
    fun deletedProfileStillListedByAnOlderIndexIsNotReportedUnavailable() = runTest {
        val repository = open()
        repository.createProfile(Profile("a", "Ana", createdAt = created))
        repository.createProfile(Profile("b", "Bia", createdAt = created))
        repository.deleteProfile("b")
        vault.current[INDEX_KEY] = "garbage".encodeToByteArray()

        val reopened = open()

        assertEquals(listOf("a"), ids(reopened))
        assertTrue(reopened.unavailableProfiles.isEmpty())
    }

    @Test
    fun restoringADeletedProfileIdBringsItBackAndLaterRecoveryStillWorks() = runTest {
        val repository = open()
        repository.createProfile(Profile("a", "Ana", createdAt = created))
        repository.createProfile(Profile("b", "Bia", createdAt = created))
        val data = repository.readProfile("b")
        repository.deleteProfile("b")
        repository.restoreProfile(data)
        repository.createProfile(Profile("c", "Cy", createdAt = created))
        vault.current[INDEX_KEY] = "garbage".encodeToByteArray()

        assertEquals(listOf("a", "b", "c"), ids(open()))
    }

    @Test
    fun unreadableUnlistedRecordDoesNotBlockOpeningAndIsNeverDeleted() = runTest {
        val repository = open()
        repository.createProfile(Profile("a", "Ana", createdAt = created))
        repository.createProfile(Profile("p", "Pia", createdAt = created))
        repository.replaceVitals("p", sampleProfileData("V").vitals)
        vault.current[recordKey("p")] = "garbage".encodeToByteArray()
        vault.previous.remove(recordKey("p"))
        vault.current[INDEX_KEY] = "garbage".encodeToByteArray()

        val reopened = open()

        assertEquals(listOf("a"), ids(reopened))
        assertNotNull(vault.read("repository-vitals-p-v1"), "vitals of an unreadable unlisted record are not swept")
        assertTrue(vault.current.containsKey(recordKey("p")) || vault.quarantined.containsKey(recordKey("p")))
    }

    @Test
    fun vaultThatCannotListKeysStillOpensFromThePreviousGeneration() = runTest {
        val repository = open()
        repository.createProfile(Profile("a", "Ana", createdAt = created))
        repository.createProfile(Profile("p", "Pia", createdAt = created))
        vault.listing = false
        vault.current[INDEX_KEY] = "garbage".encodeToByteArray()

        assertEquals(listOf("a"), ids(open()))
        assertNotNull(vault.read(recordKey("p")))
    }

    @Test
    fun theIndexAndOtherKeysAreNeverTreatedAsProfileRecords() = runTest {
        val repository = open()
        repository.createProfile(Profile("a", "Ana", createdAt = created))
        repository.createProfile(Profile("b", "Bia", createdAt = created))
        vault.write("repository-selected-profile-v1", "b".encodeToByteArray())
        vault.current[INDEX_KEY] = "garbage".encodeToByteArray()

        assertEquals(listOf("a", "b"), ids(open()))
    }

    @Test
    fun emptyIndexFileFallsBackToThePreviousGenerationOrFailsAsCorrupt() = runTest {
        val repository = open()
        repository.createProfile(Profile("a", "Ana", createdAt = created))
        repository.createProfile(Profile("b", "Bia", createdAt = created))
        vault.current[INDEX_KEY] = ByteArray(0)
        assertEquals(listOf("a", "b"), ids(open()))

        val lone = GenerationalMemoryVault()
        VaultBackedRepository(lone).also { it.load(); it.createProfile(Profile("x", "Xu", createdAt = created)) }
        lone.current[INDEX_KEY] = ByteArray(0)
        lone.previous.remove(INDEX_KEY)
        val failure = assertFailsWith<VaultUnreadableException> { VaultBackedRepository(lone).load() }
        assertEquals(VaultFailure.CORRUPT, failure.failure)
        assertTrue(lone.current.containsKey(recordKey("x")), "nothing is deleted")
    }

    @Test
    fun envelopeWithAnotherAssociatedDataIsRejectedBeforeDecrypting() = runTest {
        val envelope = vault.encrypt("secret".encodeToByteArray(), "profile:1".encodeToByteArray())

        assertContentEquals("secret".encodeToByteArray(), vault.decrypt(envelope, "profile:1".encodeToByteArray()))
        val failure = assertFailsWith<VaultUnreadableException> { vault.decrypt(envelope, "profile:2".encodeToByteArray()) }
        assertEquals(VaultFailure.CORRUPT, failure.failure)
    }

    @Test
    fun recordSwappedBetweenProfilesIsRejected() = runTest {
        val repository = open()
        repository.createProfile(Profile("a", "Ana", createdAt = created))
        repository.createProfile(Profile("b", "Bia", createdAt = created))
        // b's key now holds a's bytes, whose stored associated data names a.
        vault.current[recordKey("b")] = vault.current.getValue(recordKey("a")).copyOf()
        vault.previous.remove(recordKey("b"))

        val failure = assertFailsWith<VaultUnreadableException> { open() }.failure
        assertEquals(VaultFailure.CORRUPT, failure)
    }

    @Test
    fun sourceFileOfAnotherDocumentIsRejected() = runTest {
        val store = VaultSourceFileStore(vault)
        val key = store.save("p1", "d1", byteArrayOf(1, 2, 3))

        assertContentEquals(byteArrayOf(1, 2, 3), store.load("p1", "d1", key))
        assertFailsWith<VaultUnreadableException> { store.load("p1", "d2", key) }
        assertNull(store.load("p1", "d1", "source-document-missing"))
        assertTrue(CipherEnvelopeCodec.decode(vault.read(key)!!).associatedData.isNotEmpty())
    }
}
