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
import kotlin.time.Instant
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
        val huge = ByteArray(25 * 1024 * 1024)
        val members = listOf(BackupMember(sampleProfileData("A"), mapOf("A-doc" to huge)))

        val failure = assertFailsWith<VitaMessageException> { service.createHousehold(members, pass.copyOf()) }

        assertEquals(L10nKey.BACKUP_TOO_LARGE, failure.userText.key)
    }

    @Test
    fun readingAcceptsFilesUpToTheOldLimitWhileWritingStopsAt24MiB() = runTest {
        assertEquals(64 * 1024 * 1024, BackupService.MAX_BACKUP_READ_BYTES)
        assertEquals(24 * 1024 * 1024, BackupService.MAX_BACKUP_WRITE_BYTES)
        // A 40 MiB file from a released build is still recognised as a backup (kind is decided before any decryption).
        val sealed = service.create(sampleProfileData("A"), pass.copyOf())
        val big = sealed.copyOf(40 * 1024 * 1024)
        assertEquals(app.vezuna.security.BackupKind.PORTABLE, service.kind(big))
        assertEquals(app.vezuna.security.BackupKind.UNKNOWN, service.kind(sealed.copyOf(BackupService.MAX_BACKUP_READ_BYTES + 1)))
        // New files above the write limit are refused.
        val members = listOf(BackupMember(sampleProfileData("A"), mapOf("A-doc" to ByteArray(19 * 1024 * 1024))))
        assertEquals(L10nKey.BACKUP_TOO_LARGE, assertFailsWith<VitaMessageException> { service.createHousehold(members, pass.copyOf()) }.userText.key)
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
            override suspend fun restoreProfileAndVitals(data: ProfileData): Boolean = error("disk full")
        }

        assertFailsWith<IllegalStateException> { broken.restoreMembers(household(), RestoreMode.REPLACE_EXISTING, store) { "x" } }

        assertTrue(store.files.isEmpty())
    }
}

class HouseholdBackupSafetyTest {
    private val t = Instant.parse("2026-01-01T00:00:00Z")

    private class CountingStore(val failOn: Set<String> = emptySet(), val size: Int = 1) : SourceFileStore {
        var loads = 0
        override suspend fun save(profileId: String, documentId: String, bytes: ByteArray) = "k-$documentId"
        override suspend fun discard(key: String) {}
        override suspend fun load(profileId: String, documentId: String, key: String): ByteArray {
            loads++
            if (documentId in failOn) error("unreadable original")
            return ByteArray(size)
        }
    }

    private suspend fun repositoryWithDocuments(count: Int): InMemoryVezunaRepository {
        val repository = InMemoryVezunaRepository()
        repository.createProfile(Profile("p", "Ana", createdAt = t))
        (1..count).forEach { repository.addDocument(app.vezuna.domain.SourceDocument("p", "d$it", "d.pdf", "application/pdf", "h", t, vaultKey = "k-d$it")) }
        return repository
    }

    @Test
    fun sizeLimitIsEnforcedWhileOriginalsLoadNotAfterwards() = runTest {
        val store = CountingStore(size = 5 * 1024 * 1024)
        val controller = labController(repositoryWithDocuments(10), store)

        val failure = assertFailsWith<VitaMessageException> { controller.householdForBackup() }

        assertEquals(L10nKey.BACKUP_TOO_LARGE_NAMED, failure.userText.key)
        assertTrue(store.loads < 10, "stopped after ${store.loads} loads")
    }

    @Test
    fun oneUnreadableOriginalDoesNotBlockTheBackup() = runTest {
        val store = CountingStore(failOn = setOf("d2"))
        val members = labController(repositoryWithDocuments(3), store).householdForBackup()

        assertEquals(setOf("d1", "d3"), members.single().files.keys)
    }
}

class RestoreSafetyTest {
    private val t = Instant.parse("2026-01-01T00:00:00Z")
    private val vault = GenerationalMemoryVault()
    private var ids = 0
    private fun newId() = "fresh-${ids++}"

    private fun profileWithDocuments(profileId: String, vararg documentIds: String) = ProfileData(
        Profile(profileId, "Name $profileId", createdAt = t),
        documents = documentIds.map { app.vezuna.domain.SourceDocument(profileId, it, "$it.pdf", "application/pdf", "h", t) },
    )

    @Test
    fun restoreAfterTheRecordIsDurableNeverRemovesItsOriginals() = runTest {
        val repository = VaultBackedRepository(vault).also { it.load() }
        val store = VaultSourceFileStore(vault)
        repository.restoreMembers(listOf(BackupMember(profileWithDocuments("r", "d1"), mapOf("d1" to byteArrayOf(1)))), RestoreMode.REPLACE_EXISTING, store, ::newId)
        // From now on the profile index cannot be written (disk full), after the record itself was stored.
        vault.failingWrites += "repository-profile-index-v1"

        repository.restoreMembers(
            listOf(BackupMember(profileWithDocuments("r", "d1", "d2"), mapOf("d1" to byteArrayOf(1), "d2" to byteArrayOf(2)))),
            RestoreMode.REPLACE_EXISTING, store, ::newId,
        )

        vault.failingWrites.clear()
        val reopened = VaultBackedRepository(vault).also { it.load() }
        val docs = reopened.readProfile("r").documents
        assertEquals(listOf("d1", "d2"), docs.map { it.documentId })
        docs.forEach { assertNotNull(store.load("r", it.documentId, assertNotNull(it.vaultKey))) }
    }

    @Test
    fun backupDocumentIdUsedByAnotherProfileCannotOverwriteItsOriginal() = runTest {
        val repository = VaultBackedRepository(vault).also { it.load() }
        val store = VaultSourceFileStore(vault)
        repository.restoreMembers(listOf(BackupMember(profileWithDocuments("a", "d1"), mapOf("d1" to byteArrayOf(1, 1)))), RestoreMode.REPLACE_EXISTING, store, ::newId)
        val keyA = assertNotNull(repository.readProfile("a").documents.single().vaultKey)

        val outcome = repository.restoreMembers(listOf(BackupMember(profileWithDocuments("b", "d1"), mapOf("d1" to byteArrayOf(9)))), RestoreMode.REPLACE_EXISTING, store, ::newId)

        assertContentEquals(byteArrayOf(1, 1), store.load("a", "d1", keyA), "the other profile's original is untouched and still readable")
        val restored = repository.readProfile(outcome.profileIds.single())
        assertNotEquals("d1", restored.documents.single().documentId)
        assertContentEquals(byteArrayOf(9), store.load(restored.profile.profileId, restored.documents.single().documentId, assertNotNull(restored.documents.single().vaultKey)))
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
        assertFailsWith<app.vezuna.security.VaultUnreadableException> { store.load("p", "other-document", key) }
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

class MultiPageDocumentTest {
    private val t = Instant.parse("2026-01-01T00:00:00Z")
    private val service = BackupService()
    private var ids = 0
    private fun newId() = "fresh-${ids++}"

    private fun threePages(profileId: String, documentId: String = "d") = ProfileData(
        Profile(profileId, "Name $profileId", createdAt = t),
        documents = listOf(
            app.vezuna.domain.SourceDocument(
                profileId, documentId, "scan.jpg", "image/jpeg", "h", t,
                vaultKey = "source-document-$documentId",
                pageVaultKeys = listOf("source-document-$documentId-page-2", "source-document-$documentId-page-3"),
            ),
        ),
    )

    private fun pageFiles(documentId: String = "d") = mapOf(
        documentId to byteArrayOf(1), "$documentId-page-2" to byteArrayOf(2), "$documentId-page-3" to byteArrayOf(3),
    )

    @Test
    fun backupCarriesEveryPageAndRejectsFilesForPagesThatDoNotExist() = runTest {
        val member = BackupMember(threePages("p"), pageFiles())
        val opened = service.open(service.createHousehold(listOf(member), "correct horse battery".toCharArray()), "correct horse battery".toCharArray())
        assertEquals(setOf("d", "d-page-2", "d-page-3"), opened.members.single().files.keys)
        assertContentEquals(byteArrayOf(3), opened.members.single().files.getValue("d-page-3"))

        val bogus = BackupMember(threePages("p"), mapOf("d-page-9" to byteArrayOf(9)))
        val file = service.createHousehold(listOf(bogus), "correct horse battery".toCharArray())
        assertEquals(L10nKey.BACKUP_INVALID_CONTENT, assertFailsWith<VitaMessageException> { service.open(file, "correct horse battery".toCharArray()) }.userText.key)
    }

    @Test
    fun restoreStoresEachPageAndPointsTheDocumentAtThem() = runTest {
        val vault = GenerationalMemoryVault()
        val repository = VaultBackedRepository(vault).also { it.load() }
        val store = VaultSourceFileStore(vault)

        repository.restoreMembers(listOf(BackupMember(threePages("p"), pageFiles())), RestoreMode.REPLACE_EXISTING, store, ::newId)

        val doc = repository.readProfile("p").documents.single()
        assertContentEquals(byteArrayOf(1), store.load("p", "d", assertNotNull(doc.vaultKey)))
        assertEquals(2, doc.pageVaultKeys.size)
        doc.pageVaultKeys.forEachIndexed { index, key -> assertContentEquals(byteArrayOf((index + 2).toByte()), store.load("p", "d-page-${index + 2}", key)) }
    }

    @Test
    fun restoreAsNewStoresPagesUnderTheNewDocumentId() = runTest {
        val vault = GenerationalMemoryVault()
        val repository = VaultBackedRepository(vault).also { it.load() }
        val store = VaultSourceFileStore(vault)
        repository.restoreMembers(listOf(BackupMember(threePages("p"), pageFiles())), RestoreMode.REPLACE_EXISTING, store, ::newId)

        val copyId = repository.restoreMembers(listOf(BackupMember(threePages("p"), pageFiles())), RestoreMode.RESTORE_AS_NEW, store, ::newId).profileIds.single()

        val copy = repository.readProfile(copyId).documents.single()
        assertNotEquals("d", copy.documentId)
        assertEquals(2, copy.pageVaultKeys.size)
        copy.pageVaultKeys.forEachIndexed { index, key -> assertContentEquals(byteArrayOf((index + 2).toByte()), store.load(copyId, "${copy.documentId}-page-${index + 2}", key)) }
        // The original profile's pages are untouched.
        assertEquals(3, repository.readProfile("p").documents.single().let { 1 + it.pageVaultKeys.size })
    }

    @Test
    fun conflictingFullReplacementFailsBeforeAnyMemberWritesAndKeepsOriginals() = runTest {
        for (failure in listOf("repository-profile-p-v1", "source-document-d-page-3")) {
            val vault = GenerationalMemoryVault()
            val repository = VaultBackedRepository(vault).also { it.load() }
            val store = VaultSourceFileStore(vault)
            val original = threePages("p")
            repository.restoreMembers(listOf(BackupMember(original, pageFiles())), RestoreMode.REPLACE_EXISTING, store, ::newId)
            val before = repository.readProfile("p")
            val writes = vault.writes.toList()
            vault.failingWrites += failure
            val changed = original.copy(documents = original.documents.map { it.copy(sha256 = "different") })
            val error = assertFailsWith<Exception> {
                repository.restoreMembers(listOf(
                    BackupMember(threePages("q", "e"), pageFiles("e")),
                    BackupMember(changed, pageFiles().mapValues { byteArrayOf(9) }),
                ), RestoreMode.REPLACE_EXISTING, store, ::newId)
            }
            // Check the actual bytes before checking the error: failed rollback must not erase either vault generation.
            pageFiles().forEach { (id, bytes) -> assertContentEquals(bytes, store.load("p", id, "source-document-$id")) }
            assertEquals(before, repository.readProfile("p"))
            assertEquals(writes, vault.writes, "all members are checked before any files or records are written")
            assertEquals(L10nKey.BACKUP_RESTORE_FILE_CONFLICT, (error as VitaMessageException).userText.key)
            assertEquals(listOf("p"), repository.profiles.value.map { it.profileId })
        }
    }

    @Test
    fun anIncludedDocumentCannotOverwriteOnlyAnExistingPage() = runTest {
        val vault = GenerationalMemoryVault()
        val repository = VaultBackedRepository(vault).also { it.load() }
        val store = VaultSourceFileStore(vault)
        val original = threePages("p")
        repository.restoreMembers(listOf(BackupMember(original, pageFiles())), RestoreMode.REPLACE_EXISTING, store, ::newId)
        val incoming = original.copy(documents = listOf(original.documents.single().copy(documentId = "d-page-2", sha256 = "different", pageVaultKeys = emptyList())))
        val writes = vault.writes.toList()
        assertEquals(L10nKey.BACKUP_RESTORE_FILE_CONFLICT, assertFailsWith<VitaMessageException> {
            repository.restoreMembers(listOf(BackupMember(incoming, mapOf("d-page-2" to byteArrayOf(9)))), RestoreMode.REPLACE_EXISTING, store, ::newId)
        }.userText.key)
        assertEquals(writes, vault.writes)
        pageFiles().forEach { (id, bytes) -> assertContentEquals(bytes, store.load("p", id, "source-document-$id")) }
    }

    @Test
    fun sameContentFullReplaceKeepsLocalFilesAndDifferentContentCanRestoreAsNew() = runTest {
        val vault = GenerationalMemoryVault()
        val repository = VaultBackedRepository(vault).also { it.load() }
        val store = VaultSourceFileStore(vault)
        val original = threePages("p")
        repository.restoreMembers(listOf(BackupMember(original, pageFiles())), RestoreMode.REPLACE_EXISTING, store, ::newId)
        val before = repository.readProfile("p")
        val writes = vault.writes.size
        repository.restoreMembers(listOf(BackupMember(original, pageFiles())), RestoreMode.REPLACE_EXISTING, store, ::newId)
        assertTrue(vault.writes.drop(writes).none { it.startsWith("source-document-") })
        val changed = original.copy(documents = original.documents.map { it.copy(sha256 = "different") })
        val fresh = repository.restoreMembers(listOf(BackupMember(changed, pageFiles().mapValues { byteArrayOf(9) })), RestoreMode.RESTORE_AS_NEW, store, ::newId).profileIds.single()
        val doc = repository.readProfile(fresh).documents.single()
        assertContentEquals(byteArrayOf(9), store.load(fresh, doc.documentId, assertNotNull(doc.vaultKey)))
        assertEquals(before, repository.readProfile("p"))
        pageFiles().forEach { (id, bytes) -> assertContentEquals(bytes, store.load("p", id, "source-document-$id")) }
    }

    @Test
    fun incompletePagesAreDroppedRatherThanMisnumbered() = runTest {
        val vault = GenerationalMemoryVault()
        val repository = VaultBackedRepository(vault).also { it.load() }
        val files = pageFiles() - "d-page-2"

        repository.restoreMembers(listOf(BackupMember(threePages("p"), files)), RestoreMode.REPLACE_EXISTING, VaultSourceFileStore(vault), ::newId)

        val doc = repository.readProfile("p").documents.single()
        assertNotNull(doc.vaultKey)
        assertTrue(doc.pageVaultKeys.isEmpty())
    }

    @Test
    fun anotherProfilesPagesAreNeverOverwrittenByACollidingDocumentId() = runTest {
        val vault = GenerationalMemoryVault()
        val repository = VaultBackedRepository(vault).also { it.load() }
        val store = VaultSourceFileStore(vault)
        repository.restoreMembers(listOf(BackupMember(threePages("a"), pageFiles())), RestoreMode.REPLACE_EXISTING, store, ::newId)
        val before = repository.readProfile("a").documents.single()

        val other = pageFiles().mapValues { byteArrayOf(7) }
        repository.restoreMembers(listOf(BackupMember(threePages("b"), other)), RestoreMode.REPLACE_EXISTING, store, ::newId)

        before.pageVaultKeys.forEachIndexed { index, key -> assertContentEquals(byteArrayOf((index + 2).toByte()), store.load("a", "d-page-${index + 2}", key)) }
    }

    @Test
    fun aDocumentIdThatLooksLikeAPageFileCannotOverwriteAnotherProfilesPage() = runTest {
        val vault = GenerationalMemoryVault()
        val repository = VaultBackedRepository(vault).also { it.load() }
        val store = VaultSourceFileStore(vault)
        repository.restoreMembers(listOf(BackupMember(threePages("a", "X"), pageFiles("X"))), RestoreMode.REPLACE_EXISTING, store, ::newId)
        val a = repository.readProfile("a").documents.single()

        // Same restore path as a backup that slipped past validation: the file ID "X-page-2" belongs to profile a's page 2.
        val crafted = ProfileData(
            Profile("b", "Name b", createdAt = t),
            documents = listOf(app.vezuna.domain.SourceDocument("b", "X-page-2", "x.jpg", "image/jpeg", "h", t)),
        )
        val outcome = repository.restoreMembers(listOf(BackupMember(crafted, mapOf("X-page-2" to byteArrayOf(66)))), RestoreMode.REPLACE_EXISTING, store, ::newId)

        assertContentEquals(byteArrayOf(2), store.load("a", "X-page-2", a.pageVaultKeys[0]))
        assertContentEquals(byteArrayOf(3), store.load("a", "X-page-3", a.pageVaultKeys[1]))
        assertNotEquals("X-page-2", repository.readProfile(outcome.profileIds.single()).documents.single().documentId)
    }

    @Test
    fun aDocumentIdClashingWithItsOwnProfilesPageIsRemapped() = runTest {
        val vault = GenerationalMemoryVault()
        val repository = VaultBackedRepository(vault).also { it.load() }
        val data = threePages("p", "X").let {
            it.copy(documents = it.documents + app.vezuna.domain.SourceDocument("p", "X-page-2", "y.jpg", "image/jpeg", "h", t, vaultKey = "source-document-X-page-2"))
        }

        repository.restoreMembers(listOf(BackupMember(data, pageFiles("X") + ("X-page-2" to byteArrayOf(9)))), RestoreMode.REPLACE_EXISTING, VaultSourceFileStore(vault), ::newId)

        val stored = repository.profiles.value.single().profileId
        assertNotEquals("p", stored)
    }

    @Test
    fun backupsWithPageLikeDocumentIdsOrAbsurdPageCountsAreRejected() = runTest {
        val like = threePages("p", "X-page-3")
        val file = service.createHousehold(listOf(BackupMember(like)), "correct horse battery".toCharArray())
        assertEquals(L10nKey.BACKUP_INVALID_CONTENT, assertFailsWith<VitaMessageException> { service.open(file, "correct horse battery".toCharArray()) }.userText.key)

        val many = threePages("p").let { it.copy(documents = it.documents.map { d -> d.copy(pageVaultKeys = (1..11).map { n -> "k$n" }) }) }
        val file2 = service.createHousehold(listOf(BackupMember(many)), "correct horse battery".toCharArray())
        assertEquals(L10nKey.BACKUP_INVALID_CONTENT, assertFailsWith<VitaMessageException> { service.open(file2, "correct horse battery".toCharArray()) }.userText.key)
    }

    @Test
    fun deletingTheDocumentOrProfileRemovesEveryPageFile() = runTest {
        val vault = GenerationalMemoryVault()
        val repository = VaultBackedRepository(vault).also { it.load() }
        val store = VaultSourceFileStore(vault)
        repository.restoreMembers(listOf(BackupMember(threePages("p"), pageFiles())), RestoreMode.REPLACE_EXISTING, store, ::newId)
        repository.restoreMembers(listOf(BackupMember(threePages("q", "e"), pageFiles("e"))), RestoreMode.REPLACE_EXISTING, store, ::newId)
        val keys = repository.readProfile("p").documents.single().let { listOfNotNull(it.vaultKey) + it.pageVaultKeys }
        val otherKeys = repository.readProfile("q").documents.single().let { listOfNotNull(it.vaultKey) + it.pageVaultKeys }

        repository.deleteDocument("p", "d")
        keys.forEach { assertNull(vault.read(it)) }
        otherKeys.forEach { assertNotNull(vault.read(it)) }
        repository.deleteProfile("q")
        otherKeys.forEach { assertNull(vault.read(it)) }
    }

    @Test
    fun householdExportIncludesEveryPage() = runTest {
        val repository = InMemoryVezunaRepository()
        repository.restoreProfile(threePages("p"))
        val store = object : SourceFileStore {
            override suspend fun save(profileId: String, documentId: String, bytes: ByteArray) = error("unused")
            override suspend fun discard(key: String) {}
            override suspend fun load(profileId: String, documentId: String, key: String) = documentId.encodeToByteArray()
        }

        val files = labController(repository, store).householdForBackup().single().files

        assertEquals(setOf("d", "d-page-2", "d-page-3"), files.keys)
        assertContentEquals("d-page-3".encodeToByteArray(), files.getValue("d-page-3"))
    }
}
