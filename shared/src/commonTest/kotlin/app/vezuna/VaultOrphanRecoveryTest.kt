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
    fun emptyIndexFileOpensFromThePreviousGenerationAndKeepsTheNewestProfileWithItsVitals() = runTest {
        val repository = open()
        repository.createProfile(Profile("a", "Ana", createdAt = created))
        repository.createProfile(Profile("p", "Pia", createdAt = created))
        val vitals = sampleProfileData("V").vitals
        repository.replaceVitals("p", vitals)
        vault.current[INDEX_KEY] = ByteArray(0)

        val reopened = open()

        assertTrue(reopened.openedFromPreviousGeneration.value)
        assertEquals(listOf("a", "p"), ids(reopened))
        assertEquals(vitals.steps, reopened.readProfile("p").vitals.steps)
        assertTrue(vault.quarantined.containsKey(INDEX_KEY), "the empty file is kept as evidence")
    }

    @Test
    fun corruptDeletedRecordDoesNotRaiseThePreviousGenerationFlag() = runTest {
        val repository = open()
        repository.createProfile(Profile("a", "Ana", createdAt = created))
        repository.createProfile(Profile("b", "Bia", createdAt = created))
        repository.deleteProfile("b")
        vault.current["repository-deleted-profiles-v1"] = "garbage".encodeToByteArray()

        val reopened = open()

        assertTrue(!reopened.openedFromPreviousGeneration.value)
        assertEquals(listOf("a"), ids(reopened))
    }

    @Test
    fun corruptDeletedRecordMeansNothingIsRecoveredFromRecordFiles() = runTest {
        val repository = open()
        repository.createProfile(Profile("a", "Ana", createdAt = created))
        repository.createProfile(Profile("b", "Bia", createdAt = created))
        repository.deleteProfile("b")
        repository.createProfile(Profile("c", "Cy", createdAt = created))
        vault.current["repository-deleted-profiles-v1"] = "garbage".encodeToByteArray()
        vault.current[INDEX_KEY] = "garbage".encodeToByteArray()

        val reopened = open()

        assertEquals(listOf("a"), ids(reopened))
        assertNotNull(vault.read(recordKey("c")), "the unlisted record is left alone")
    }

    @Test
    fun deletedRecordServedFromThePreviousGenerationRecoversNothing() = runTest {
        val repository = open()
        repository.createProfile(Profile("x", "Xu", createdAt = created))
        repository.createProfile(Profile("y", "Yu", createdAt = created))
        val recordY = vault.current.getValue(recordKey("y")).copyOf()
        repository.deleteProfile("x")
        repository.deleteProfile("y")
        // y's record outlived its delete; the newest deleted-profile record (naming y) is corrupt, the older one names only x.
        vault.current[recordKey("y")] = recordY
        repository.createProfile(Profile("z", "Zed", createdAt = created))
        vault.current["repository-deleted-profiles-v1"] = "garbage".encodeToByteArray()
        vault.current[INDEX_KEY] = "garbage".encodeToByteArray()

        val reopened = open()

        assertTrue(reopened.openedFromPreviousGeneration.value)
        assertEquals(emptyList(), ids(reopened))
        assertContentEquals(recordY, vault.current.getValue(recordKey("y")))
    }

    @Test
    fun replacedTombstoneFileKeepsRecordRecoveryOffEvenAfterANewDeleteIsRecorded() = runTest {
        val repository = open()
        repository.createProfile(Profile("a", "Ana", createdAt = created))
        repository.createProfile(Profile("b", "Bia", createdAt = created))
        val survivingB = vault.current.getValue(recordKey("b")).copyOf()
        repository.deleteProfile("b")
        vault.current[recordKey("b")] = survivingB // b's record outlived its delete
        vault.current["repository-deleted-profiles-v1"] = "garbage".encodeToByteArray() // the tombstone naming b is lost

        val reopened = open()
        reopened.createProfile(Profile("d", "Dee", createdAt = created))
        reopened.deleteProfile("a") // replaces the unreadable tombstone file with one naming only a
        vault.current[INDEX_KEY] = "garbage".encodeToByteArray()

        val again = open()

        assertTrue(again.openedFromPreviousGeneration.value)
        assertEquals(listOf("d"), ids(again), "b is not recovered: the earlier tombstones are gone")
        assertContentEquals(survivingB, vault.current.getValue(recordKey("b")), "the file is left alone, not deleted")
    }

    @Test
    fun deletedProfileListedByAPreviousGenerationIndexStaysDeletedAndKeepsItsTombstone() = runTest {
        val repository = open()
        repository.createProfile(Profile("a", "Ana", createdAt = created))
        repository.createProfile(Profile("b", "Bia", createdAt = created))
        val survivingB = vault.current.getValue(recordKey("b")).copyOf()
        repository.deleteProfile("b")
        vault.current[recordKey("b")] = survivingB // the delete finished except for removing the record
        vault.current[INDEX_KEY] = "garbage".encodeToByteArray() // the previous index still lists b

        val reopened = open()

        assertTrue(reopened.openedFromPreviousGeneration.value)
        assertEquals(listOf("a"), ids(reopened))
        assertContentEquals(survivingB, vault.current.getValue(recordKey("b")), "the file is left alone, not deleted")
        // The tombstone survived: a later previous-generation load does not recover b as an orphan either.
        reopened.createProfile(Profile("c", "Cy", createdAt = created))
        vault.current[INDEX_KEY] = "garbage".encodeToByteArray()
        assertEquals(listOf("a", "c"), ids(open()))
    }

    @Test
    fun aFailedDeleteLeavesTheProfileLiveEvenWhenTheNextLaunchUsesAPreviousIndex() = runTest {
        val repository = open()
        repository.createProfile(Profile("a", "Ana", createdAt = created))
        repository.createProfile(Profile("x", "Xu", createdAt = created))
        val vitals = sampleProfileData("V").vitals
        repository.replaceVitals("x", vitals)
        repository.updateProfile(Profile("a", "Ana", createdAt = created)) // both index generations now list x
        vault.failingWrites += INDEX_KEY
        assertFailsWith<IllegalStateException> { repository.deleteProfile("x") }
        vault.failingWrites.clear()
        vault.current[INDEX_KEY] = "garbage".encodeToByteArray()

        val reopened = open()

        assertTrue(reopened.openedFromPreviousGeneration.value)
        assertEquals(listOf("a", "x"), ids(reopened))
        assertEquals(vitals.steps, reopened.readProfile("x").vitals.steps)
    }

    @Test
    fun aTombstonedListedProfileStaysLiveWhenOnlyAnotherRecordFellBackToItsPreviousCopy() = runTest {
        val repository = open()
        repository.createProfile(Profile("a", "Ana", createdAt = created))
        repository.createProfile(Profile("x", "Xu", createdAt = created))
        val vitals = sampleProfileData("V").vitals
        repository.replaceVitals("x", vitals)
        val indexWithX = vault.current.getValue(INDEX_KEY).copyOf()
        val recordA = vault.current.getValue(recordKey("a")).copyOf()
        val recordX = vault.current.getValue(recordKey("x")).copyOf()
        val vitalsKey = vault.current.keys.single { it.startsWith("repository-vitals-x") }
        val vitalsX = vault.current.getValue(vitalsKey).copyOf()
        repository.deleteProfile("x")
        // A crash after the tombstone but before the index write: x is listed, tombstoned and fully intact.
        vault.current[INDEX_KEY] = indexWithX
        vault.current[recordKey("x")] = recordX
        vault.current[vitalsKey] = vitalsX
        // a's current record is corrupt with a good previous copy, which sets the live "previous generation" flag.
        vault.previous[recordKey("a")] = recordA
        vault.current[recordKey("a")] = "garbage".encodeToByteArray()

        val reopened = open()

        assertEquals(listOf("a", "x"), ids(reopened))
        assertEquals(vitals.steps, reopened.readProfile("x").vitals.steps)
        assertNotNull(vault.read(vitalsKey), "vitals of a live profile are not swept")
    }

    @Test
    fun vitalsOfAnUnreadableUnlistedRecordSurviveEveryLaterLaunch() = runTest {
        val repository = open()
        repository.createProfile(Profile("a", "Ana", createdAt = created))
        repository.createProfile(Profile("p", "Pia", createdAt = created))
        repository.replaceVitals("p", sampleProfileData("V").vitals)
        vault.current[recordKey("p")] = "garbage".encodeToByteArray()
        vault.previous.remove(recordKey("p"))
        vault.current[INDEX_KEY] = "garbage".encodeToByteArray()

        repeat(3) { open() }

        assertNotNull(vault.read("repository-vitals-p-v1"))
    }

    @Test
    fun recoveryFindsRecordsThroughStoredAssociatedDataWhenFileNamesHideKeys() = runTest {
        val repository = open()
        repository.createProfile(Profile("a", "Ana", createdAt = created))
        repository.createProfile(Profile("p", "Pia", createdAt = created))
        vault.listing = false
        vault.scanAssociatedData = true
        vault.current[INDEX_KEY] = "garbage".encodeToByteArray()

        assertEquals(listOf("a", "p"), ids(open()))
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
