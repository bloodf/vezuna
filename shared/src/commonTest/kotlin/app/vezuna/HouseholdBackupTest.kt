package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.data.VaultBackedRepository
import app.vezuna.data.VaultSourceFileStore
import app.vezuna.data.restoreMembers
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.localization.L10nKey
import app.vezuna.localization.VitaMessageException
import app.vezuna.security.BackupMember
import app.vezuna.security.BackupService
import app.vezuna.ui.RestoreMode
import app.vezuna.ui.SourceFileStore
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemorySourceStore : SourceFileStore {
    val files = mutableMapOf<String, ByteArray>()
    override suspend fun save(profileId: String, documentId: String, bytes: ByteArray): String {
        val key = "source-document-$documentId"
        files[key] = bytes
        return key
    }
    override suspend fun discard(key: String) { files.remove(key) }
    override suspend fun load(profileId: String, documentId: String, key: String) = files[key]
}

private val pass = "correct horse battery".toCharArray()
private val t = Instant.parse("2026-01-01T00:00:00Z")

private fun household(): List<BackupMember> {
    val ana = sampleProfileData("A")
    val bia = sampleProfileData("B").let { it.copy(profile = it.profile.copy(displayName = "Bia")) }
    return listOf(
        BackupMember(ana, mapOf("A-doc" to byteArrayOf(1, 2, 3, -4))),
        BackupMember(bia),
    )
}

class HouseholdBackupTest {
    private val service = BackupService()

    @Test
    fun householdBackupCarriesEveryProfileItsVitalsAndOriginalFiles() = runTest {
        val file = service.createHousehold(household(), pass.copyOf())

        val opened = service.open(file, pass.copyOf())

        assertEquals(listOf("A-profile", "B-profile"), opened.members.map { it.data.profile.profileId })
        assertEquals(household().map { it.data }, opened.members.map { it.data })
        assertTrue(opened.members.all { !it.data.vitals.isEmpty }, "vitals travel with each profile")
        assertContentEquals(byteArrayOf(1, 2, 3, -4), opened.members[0].files.getValue("A-doc"))
        assertTrue(opened.members[1].files.isEmpty())
    }

    @Test
    fun singleProfileBackupKeepsWritingTheOlderPayloadAndStillOpens() = runTest {
        val data = sampleProfileData("A")
        val opened = service.open(service.create(data, pass.copyOf()), pass.copyOf())
        assertEquals(listOf(data), opened.members.map { it.data })
        assertEquals(3, BackupService.SINGLE_SCHEMA_VERSION)
        assertEquals(4, BackupService.SCHEMA_VERSION)
    }

    @Test
    fun oversizedHouseholdIsRefusedBeforeAnythingIsWritten() = runTest {
        val huge = ByteArray(51 * 1024 * 1024)
        val members = listOf(BackupMember(sampleProfileData("A"), mapOf("A-doc" to huge)))

        val failure = assertFailsWith<VitaMessageException> { service.createHousehold(members, pass.copyOf()) }

        assertEquals(L10nKey.BACKUP_TOO_LARGE, failure.userText.key)
    }

    @Test
    fun restoreStoresFilesAndPointsDocumentsAtThem() = runTest {
        val repository = InMemoryVezunaRepository()
        val store = MemorySourceStore()

        val outcome = repository.restoreMembers(household(), RestoreMode.REPLACE_EXISTING, store) { "unused" }

        assertEquals(listOf("A-profile", "B-profile"), outcome.profileIds)
        val doc = repository.readProfile("A-profile").documents.single()
        assertEquals("source-document-A-doc", doc.vaultKey)
        assertContentEquals(byteArrayOf(1, 2, 3, -4), store.files.getValue("source-document-A-doc"))
        assertNull(repository.readProfile("B-profile").documents.single().vaultKey, "no file in the backup, no dangling key")
    }

    @Test
    fun restoreAsNewRemapsDocumentsAndKeepsTheirFilesAligned() = runTest {
        val repository = InMemoryVezunaRepository()
        val store = MemorySourceStore()
        repository.restoreMembers(household(), RestoreMode.REPLACE_EXISTING, store) { "unused" }
        var n = 0

        val outcome = repository.restoreMembers(household(), RestoreMode.RESTORE_AS_NEW, store) { "new-${n++}" }

        val copy = repository.readProfile(outcome.profileIds.first())
        assertNotEquals("A-profile", copy.profile.profileId)
        val key = assertNotNull(copy.documents.single().vaultKey)
        assertNotEquals("source-document-A-doc", key)
        assertContentEquals(byteArrayOf(1, 2, 3, -4), store.files.getValue(key))
        assertEquals(4, repository.profiles.value.size)
    }

    @Test
    fun failedRecordWriteRemovesTheFilesWrittenForThatMember() = runTest {
        val store = MemorySourceStore()
        val broken = object : VezunaRepositoryDelegate(InMemoryVezunaRepository()) {
            override suspend fun restoreProfile(data: ProfileData) = error("disk full")
        }

        assertFailsWith<IllegalStateException> { broken.restoreMembers(household(), RestoreMode.REPLACE_EXISTING, store) { "x" } }

        assertTrue(store.files.isEmpty())
    }
}

class SourceFileStorageTest {
    private val vault = GenerationalMemoryVault()

    @Test
    fun originalIsStoredEncryptedAndComesBackIntact() = runTest {
        val store = VaultSourceFileStore(vault)
        val original = ByteArray(2000) { (it % 251).toByte() }

        val key = store.save("p", "d", original)

        assertTrue(!vault.current.getValue(key).contentEquals(original))
        assertContentEquals(original, store.load("p", "d", key))
        assertFailsWith<IllegalArgumentException> { store.load("p", "other-document", key) }
    }

    @Test
    fun deletingTheDocumentRemovesItsStoredFileAndKeepsOtherFiles() = runTest {
        val repository = VaultBackedRepository(vault).also { it.load() }
        val store = VaultSourceFileStore(vault)
        repository.createProfile(Profile("p", "Ana", createdAt = t))
        val keyA = store.save("p", "a", byteArrayOf(1))
        val keyB = store.save("p", "b", byteArrayOf(2))
        repository.addDocument(app.vezuna.domain.SourceDocument("p", "a", "a.pdf", "application/pdf", "h", t, vaultKey = keyA))
        repository.addDocument(app.vezuna.domain.SourceDocument("p", "b", "b.pdf", "application/pdf", "h", t, vaultKey = keyB))

        repository.deleteDocument("p", "a")

        assertNull(vault.read(keyA))
        assertNotNull(vault.read(keyB))
        repository.deleteProfile("p")
        assertNull(vault.read(keyB))
    }

    @Test
    fun filesWrittenBeforeTheBinaryEnvelopeStillOpen() = runTest {
        val legacyEnvelope = vault.encrypt(byteArrayOf(9, 8, 7), "Vezuna source v1:p:d".encodeToByteArray())
        vault.write("source-document-d", kotlinx.serialization.json.Json.encodeToString(app.vezuna.security.CipherEnvelope.serializer(), legacyEnvelope).encodeToByteArray())

        assertContentEquals(byteArrayOf(9, 8, 7), VaultSourceFileStore(vault).load("p", "d", "source-document-d"))
    }
}

/** Forwards everything to [inner]; tests override the one call they want to break. */
internal open class VezunaRepositoryDelegate(private val inner: app.vezuna.data.VezunaRepository) : app.vezuna.data.VezunaRepository by inner
