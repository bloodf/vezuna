package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.data.VaultBackedRepository
import app.vezuna.data.VaultSourceFileStore
import app.vezuna.data.VezunaRepository
import app.vezuna.domain.DocumentTags
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.SourceDocument
import app.vezuna.domain.VaultDocument
import app.vezuna.domain.allDocuments
import app.vezuna.domain.isInternallyConsistent
import app.vezuna.domain.remappedAsNew
import app.vezuna.domain.vaultKeys
import app.vezuna.export.DoctorExportContent
import app.vezuna.export.ExportOptions
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.localization.VitaMessageException
import app.vezuna.security.BackupMember
import app.vezuna.security.BackupService
import app.vezuna.ui.AppController
import app.vezuna.ui.AppScreen
import app.vezuna.ui.ExtractedCandidate
import app.vezuna.ui.PickedSource
import app.vezuna.ui.RestoreMode
import app.vezuna.ui.SourceFileRead
import app.vezuna.ui.SourceFileStore
import app.vezuna.ui.entry.ImportFailure
import app.vezuna.ui.entry.ImportFailureReason
import app.vezuna.ui.entry.SourceKind
import app.vezuna.ui.security.BackupContents
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private val t = Instant.parse("2026-02-03T10:00:00Z")
private val pass = "correct horse battery".toCharArray()
private val json = Json { encodeDefaults = true }

private fun pdf(name: String = "rx.pdf", bytes: ByteArray = name.encodeToByteArray()) = PickedSource(name, "application/pdf", bytes)
private fun vaultDoc(profileId: String, id: String, key: String? = null, tags: List<String> = emptyList()) =
    VaultDocument(SourceDocument(profileId, id, "$id.pdf", "application/pdf", "hash-$id", t, key), tags)

private class Fixture {
    val vault = GenerationalMemoryVault()
    val repository = VaultBackedRepository(vault)
    val store = VaultSourceFileStore(vault)
    lateinit var controller: AppController

    suspend fun start(): Fixture {
        repository.load()
        controller = labController(repository, store)
        controller.initialize()
        return this
    }

    val profileId get() = assertNotNull(controller.state.value.data).profile.profileId
    val data get() = assertNotNull(controller.state.value.data)
    suspend fun add(source: PickedSource) = controller.addDocumentFrom(SourceKind.FILE) { source }
    val documents get() = data.vaultDocuments
}

class DocumentVaultTest {
    // --- Serialization and old data ---

    @Test
    fun aProfileWrittenBeforeTheVaultLoadsUnchanged() {
        // Stored or backed-up JSON from before this field existed, verbatim.
        val old = """{"profile":{"profileId":"p","displayName":"Ana","birthDate":null,"createdAt":"2026-01-01T00:00:00Z"},"documents":[{"profileId":"p","documentId":"d","displayName":"a.pdf","mediaType":"application/pdf","sha256":"h","importedAt":"2026-01-01T00:00:00Z","vaultKey":"source-document-d","pageVaultKeys":[]}],"reports":[],"observations":[],"ocrCandidates":[],"medications":[],"doseEvents":[],"chat":[],"vitals":{}}"""
        val data = json.decodeFromString(ProfileData.serializer(), old)
        assertEquals(emptyList(), data.vaultDocuments)
        assertEquals(setOf("source-document-d"), data.vaultKeys())
        assertTrue(data.isInternallyConsistent())
    }

    @Test
    fun aProfileWithoutDocumentsSerializesWithoutTheField() {
        val data = ProfileData(Profile("p", "Ana", createdAt = t))
        assertFalse("vaultDocuments" in json.encodeToString(ProfileData.serializer(), data), "older builds can still open these files")
        val withDocument = data.copy(vaultDocuments = listOf(vaultDoc("p", "v", "k", listOf("Receita", "Dr. Exemplo"))))
        assertEquals(withDocument, json.decodeFromString(ProfileData.serializer(), json.encodeToString(ProfileData.serializer(), withDocument)))
    }

    @Test
    fun consistencyCoversVaultDocuments() {
        val base = ProfileData(Profile("p", "Ana", createdAt = t))
        assertTrue(base.copy(vaultDocuments = listOf(vaultDoc("p", "v"))).isInternallyConsistent())
        assertFalse(base.copy(vaultDocuments = listOf(vaultDoc("other", "v"))).isInternallyConsistent(), "another profile's document")
        assertFalse(base.copy(vaultDocuments = listOf(vaultDoc("p", "v"), vaultDoc("p", "v"))).isInternallyConsistent(), "repeated ID")
        assertFalse(base.copy(documents = listOf(vaultDoc("p", "v").source), vaultDocuments = listOf(vaultDoc("p", "v"))).isInternallyConsistent(), "ID shared with a lab document: same file key")
        val labOnVault = app.vezuna.domain.LabReport("p", "r", "v", importedAt = t)
        assertFalse(base.copy(reports = listOf(labOnVault), vaultDocuments = listOf(vaultDoc("p", "v"))).isInternallyConsistent(), "a report can never point at a vault document")
    }

    // --- Tags ---

    @Test
    fun tagsAreStoredAsWrittenWithoutBlanksOrRepeats() {
        assertEquals(listOf("Dr. Silva", "RM de joelho"), DocumentTags.clean(listOf("  Dr. Silva ", "", "   ", "dr. silva", "RM de joelho")))
        assertEquals(DocumentTags.MAX_LENGTH, DocumentTags.clean(listOf("x".repeat(99))).single().length)
        assertEquals(DocumentTags.MAX_PER_DOCUMENT, DocumentTags.clean((1..50).map { "t$it" }).size)
    }

    @Test
    fun filterMatchesAnySpellingNewestFirst() {
        val older = vaultDoc("p", "a", tags = listOf("Imaging"))
        val newer = VaultDocument(vaultDoc("p", "b").source.copy(importedAt = t.plus(kotlin.time.Duration.parse("1d"))), listOf("imaging", "Knee"))
        val other = vaultDoc("p", "c", tags = listOf("Rx"))
        val all = listOf(older, other, newer)
        assertEquals(listOf("Imaging", "Knee", "Rx"), DocumentTags.inUse(all))
        assertEquals(listOf("b", "a"), DocumentTags.filter(all, "IMAGING").map { it.source.documentId })
        assertEquals(3, DocumentTags.filter(all, null).size)
    }

    @Test
    fun theTagFilterEndsWithTheProfile() = runTest {
        val f = Fixture().start()
        f.controller.filterDocumentsByTag("Imaging")
        assertEquals("Imaging", f.controller.documentTagFilter.value)
        val other = "other-profile"
        f.repository.createProfile(Profile(other, "Bia", createdAt = t))
        f.controller.switchProfile(other)
        assertNull(f.controller.documentTagFilter.value)
    }

    // --- Adding: nothing lab, nothing read ---

    @Test
    fun addingNeverCreatesLabRecordsAndAsksThePickerForStoreOnly() = runTest {
        val f = Fixture().start()
        var storeOnlyAtPick = false
        // Even a source that carries candidates produces none: the vault path never looks at them.
        val withCandidates = pdf().copy(candidates = listOf(ExtractedCandidate("HGB", "Hemoglobin", "13", "g/dL", collectedAt = t)), laboratoryName = "Lab")
        f.controller.addDocumentFrom(SourceKind.CAMERA) {
            storeOnlyAtPick = f.controller.importSession.storeOnly
            assertEquals(SourceKind.CAMERA, f.controller.importSession.requestedKind)
            withCandidates
        }
        assertTrue(storeOnlyAtPick)
        assertNull(f.controller.state.value.error)
        val data = f.data
        assertEquals(1, data.vaultDocuments.size)
        assertEquals(emptyList(), data.documents)
        assertEquals(emptyList(), data.reports)
        assertEquals(emptyList(), data.ocrCandidates)
        assertEquals(emptyList(), data.observations)
        assertEquals(L10nKey.DOCUMENTS_ADDED, (f.controller.state.value.notice as UserText.Key).key)
        assertEquals(AppScreen.DocumentDetail(data.vaultDocuments.single().source.documentId), f.controller.navigator.current)
    }

    @Test
    fun theLabImportAfterwardsStillExtracts() = runTest {
        val f = Fixture().start()
        f.add(pdf())
        f.controller.importSession.start(SourceKind.FILE)
        assertFalse(f.controller.importSession.storeOnly)
    }

    @Test
    fun theFileIsEncryptedBoundToTheProfileAndViewable() = runTest {
        val f = Fixture().start()
        val secret = "RX-PLAINTEXT-MARKER".encodeToByteArray()
        f.add(pdf(bytes = secret).copy(additionalPages = listOf("PAGE-2".encodeToByteArray())))
        val source = f.documents.single().source
        assertEquals(2, source.vaultKey.let { listOfNotNull(it) + source.pageVaultKeys }.size)
        val stored = assertNotNull(f.vault.current[source.vaultKey!!]).decodeToString()
        assertFalse("RX-PLAINTEXT-MARKER" in stored)
        val read = assertIs<SourceFileRead.Available>(f.controller.readSourceFile(source.documentId))
        assertContentEquals(secret, read.pages.first())
        assertContentEquals("PAGE-2".encodeToByteArray(), read.pages[1])
        assertFails { f.store.load("another-profile", source.documentId, source.vaultKey!!) }
    }

    private suspend fun assertFails(block: suspend () -> Unit) {
        val failed = try { block(); false } catch (expected: Exception) { true }
        assertTrue(failed, "loading under another profile must not decrypt")
    }

    @Test
    fun cancelledPickerStoresNothingAndFailuresAreExplained() = runTest {
        val f = Fixture().start()
        f.controller.addDocumentFrom(SourceKind.FILE) { null }
        assertEquals(emptyList(), f.documents)
        assertNull(f.controller.state.value.error)

        f.controller.addDocumentFrom(SourceKind.FILE) { throw ImportFailure(ImportFailureReason.TOO_LARGE, "big.pdf") }
        assertEquals(L10nKey.ENTRY_TOO_LARGE_BODY, (f.controller.state.value.error as UserText.Key).key)

        f.controller.addDocumentFrom(SourceKind.FILE) { throw IllegalStateException("boom") }
        assertEquals(L10nKey.DOCUMENTS_UNREADABLE, (f.controller.state.value.error as UserText.Key).key)
        assertEquals(emptyList(), f.documents)
        assertEquals(app.vezuna.ui.entry.ImportPhase.Idle, f.controller.importSession.phase.value)
    }

    @Test
    fun aFilePickedForOneProfileNeverLandsInAnother() = runTest {
        val f = Fixture().start()
        val first = f.profileId
        f.repository.createProfile(Profile("other", "Bia", createdAt = t))
        f.controller.addDocumentFrom(SourceKind.FILE) {
            f.controller.switchProfile("other")
            pdf()
        }
        assertEquals(emptyList(), f.repository.peekProfile(first).vaultDocuments)
        assertEquals(emptyList(), f.repository.peekProfile("other").vaultDocuments)
        assertEquals(L10nKey.ENTRY_PROFILE_CHANGED, (f.controller.state.value.notice as UserText.Key).key)
    }

    @Test
    fun withoutEncryptedStorageNothingIsRecorded() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = labController(repository)
        controller.initialize()
        controller.addDocumentFrom(SourceKind.FILE) { pdf() }
        assertEquals(L10nKey.DOCUMENTS_UNREADABLE, (controller.state.value.error as UserText.Key).key)
        assertEquals(emptyList(), assertNotNull(controller.state.value.data).vaultDocuments)
    }

    @Test
    fun aFailedRecordWriteRemovesEveryStoredPage() = runTest {
        val inner = InMemoryVezunaRepository()
        val repository = object : VezunaRepository by inner {
            override suspend fun addVaultDocument(document: VaultDocument) = error("disk full")
        }
        val store = RecordingSourceStore()
        val controller = labController(repository, store)
        controller.initialize()
        controller.addDocumentFrom(SourceKind.PHOTOS) { pdf().copy(additionalPages = listOf(byteArrayOf(2))) }
        assertNotNull(controller.state.value.error)
        assertEquals(2, store.saved.size)
        assertEquals(store.saved, store.discarded)
    }

    @Test
    fun theSameFileTwiceIsOneDocumentAndAFileLessRecordGetsItsFileBack() = runTest {
        val f = Fixture().start()
        f.add(pdf())
        f.add(pdf())
        assertEquals(1, f.documents.size)
        assertEquals(L10nKey.DOCUMENTS_ALREADY_STORED, (f.controller.state.value.notice as UserText.Key).key)

        val current = f.documents.single()
        f.repository.updateVaultDocument(current.copy(source = current.source.copy(vaultKey = null), tags = listOf("Keep")))
        f.controller.switchProfile(f.profileId)
        f.add(pdf())
        val back = f.documents.single()
        assertEquals(L10nKey.DOCUMENTS_FILE_RESTORED, (f.controller.state.value.notice as UserText.Key).key)
        assertEquals(listOf("Keep"), back.tags)
        assertIs<SourceFileRead.Available>(f.controller.readSourceFile(back.source.documentId))
    }

    @Test
    fun aLabImportOfTheSameFileStaysALabDocument() = runTest {
        val f = Fixture().start()
        f.add(pdf())
        f.controller.importSource(pdf().copy(candidates = listOf(ExtractedCandidate("HGB", "Hemoglobin", "13", "g/dL", collectedAt = t))))
        val data = f.data
        assertEquals(1, data.vaultDocuments.size)
        assertEquals(1, data.documents.size)
        assertNotEquals(data.vaultDocuments.single().source.documentId, data.documents.single().documentId)
        assertEquals(data.documents.single().documentId, data.reports.single().sourceDocumentId)
    }

    // --- Rename, tag ---

    @Test
    fun renameAndTagKeepTheFile() = runTest {
        val f = Fixture().start()
        f.add(pdf())
        val before = f.documents.single().source
        f.controller.updateDocument(before.documentId, "  Receita março ", listOf(" Receita ", "receita", "", "Dr. Silva"))
        val after = f.documents.single()
        assertEquals("Receita março", after.source.displayName)
        assertEquals(listOf("Receita", "Dr. Silva"), after.tags)
        assertEquals(before.vaultKey, after.source.vaultKey)
        assertIs<SourceFileRead.Available>(f.controller.readSourceFile(before.documentId))
    }

    @Test
    fun aBlankNameIsRefused() = runTest {
        val f = Fixture().start()
        f.add(pdf())
        f.controller.updateDocument(f.documents.single().source.documentId, "   ", emptyList())
        assertEquals(L10nKey.DOCUMENTS_NAME_REQUIRED, (f.controller.state.value.error as UserText.Key).key)
        assertEquals("rx.pdf", f.documents.single().source.displayName)
    }

    // --- Delete ---

    @Test
    fun deletingRemovesTheRecordAndEveryEncryptedFile() = runTest {
        val f = Fixture().start()
        f.add(pdf().copy(additionalPages = listOf(byteArrayOf(5))))
        val source = f.documents.single().source
        val keys = listOf(source.vaultKey!!) + source.pageVaultKeys
        keys.forEach { assertNotNull(f.vault.current[it]) }
        f.controller.deleteDocument(source.documentId)
        assertEquals(emptyList(), f.documents)
        keys.forEach { assertNull(f.vault.current[it]); assertNull(f.vault.previous[it]) }
        assertEquals(L10nKey.DOCUMENTS_DELETED, (f.controller.state.value.notice as UserText.Key).key)
        assertEquals(SourceFileRead.Missing, f.controller.readSourceFile(source.documentId))
    }

    @Test
    fun aFailedDeleteKeepsBothTheRecordAndTheFile() = runTest {
        val f = Fixture().start()
        f.add(pdf())
        val source = f.documents.single().source
        f.vault.failingWrites += "repository-profile-${f.profileId}-v1"
        f.controller.deleteDocument(source.documentId)
        assertNotNull(f.controller.state.value.error)
        f.vault.failingWrites.clear()
        assertEquals(1, f.documents.size, "the record stays")
        assertIs<SourceFileRead.Available>(f.controller.readSourceFile(source.documentId), "and still opens")
        assertEquals(1, VaultBackedRepository(f.vault).also { it.load() }.readProfile(f.profileId).vaultDocuments.size)
    }

    @Test
    fun aFileDeleteThatFailsLeavesNoRecordPointingAtNothingOnScreen() = runTest {
        val inner = InMemoryVezunaRepository()
        val repository = object : VezunaRepository by inner {
            override suspend fun deleteVaultDocument(profileId: String, documentId: String) {
                inner.deleteVaultDocument(profileId, documentId)
                error("file delete failed after the record went")
            }
        }
        val controller = labController(repository, RecordingSourceStore())
        controller.initialize()
        controller.addDocumentFrom(SourceKind.FILE) { pdf() }
        val data = assertNotNull(controller.state.value.data)
        controller.deleteDocument(data.vaultDocuments.single().source.documentId)
        assertNotNull(controller.state.value.error)
        assertEquals(emptyList(), assertNotNull(controller.state.value.data).vaultDocuments)
    }

    // --- Profiles ---

    @Test
    fun documentsStayInTheirProfileAndGoWithIt() = runTest {
        val f = Fixture().start()
        f.add(pdf())
        val first = f.profileId
        val key = f.documents.single().source.vaultKey!!
        f.repository.createProfile(Profile("other", "Bia", createdAt = t))
        f.controller.switchProfile("other")
        assertEquals(emptyList(), f.documents)
        assertEquals(SourceFileRead.Missing, f.controller.readSourceFile(f.repository.peekProfile(first).vaultDocuments.single().source.documentId))

        f.repository.deleteProfile(first)
        assertNull(f.vault.current[key], "deleting the profile removes the encrypted file")
        assertNull(f.vault.previous[key])
    }

    // --- Separation from lab features ---

    @Test
    fun doctorExportAndLabsNeverSeeAVaultDocument() {
        val data = ProfileData(
            Profile("p", "Ana", createdAt = t),
            vaultDocuments = listOf(vaultDoc("p", "secret-referral", "k")),
        )
        val rows = DoctorExportContent.sourceRows(data, data, ExportOptions(includeSourceAppendix = true))
        assertEquals(emptyList(), rows, "the appendix lists sources of exported results; a vault document is not one")
        assertEquals(emptyList(), data.documents)
        assertEquals(setOf("k"), data.vaultKeys())
    }

    // --- Backup ---

    private fun sealedService() = BackupService()

    @Test
    fun householdBackupRoundTripKeepsFilesNamesAndTags() = runTest {
        val f = Fixture().start()
        val profileId = f.profileId
        f.add(pdf(bytes = byteArrayOf(1, 2, 3)).copy(additionalPages = listOf(byteArrayOf(4))))
        val id = f.documents.single().source.documentId
        f.controller.updateDocument(id, "Receita", listOf("Dr. Silva"))
        val members = f.controller.householdForBackup()
        val opened = sealedService().open(sealedService().createHousehold(members, pass.copyOf()), pass.copyOf())
        assertEquals(setOf(id, "$id-page-2"), opened.members.single().files.keys)
        val contents = BackupContents.of(opened.members)
        assertEquals(1, contents.documents)
        assertEquals(1, contents.originalFiles)

        val target = Fixture().start()
        target.controller.restoreHousehold(opened.members, RestoreMode.REPLACE_EXISTING)
        assertNull(target.controller.state.value.error)
        val restored = target.repository.readProfile(profileId).vaultDocuments.single()
        assertEquals("Receita", restored.source.displayName)
        assertEquals(listOf("Dr. Silva"), restored.tags)
        target.controller.switchProfile(profileId)
        val read = assertIs<SourceFileRead.Available>(target.controller.readSourceFile(id))
        assertContentEquals(byteArrayOf(1, 2, 3), read.pages[0])
        assertContentEquals(byteArrayOf(4), read.pages[1])
    }

    @Test
    fun restoreAsNewGivesTheFilesFreshBindings() = runTest {
        val f = Fixture().start()
        val profileId = f.profileId
        f.add(pdf(bytes = byteArrayOf(7)))
        val id = f.documents.single().source.documentId
        val members = f.controller.householdForBackup()
        f.controller.restoreHousehold(members, RestoreMode.RESTORE_AS_NEW)
        assertNull(f.controller.state.value.error)
        val copy = f.data
        assertNotEquals(profileId, copy.profile.profileId)
        val document = copy.vaultDocuments.single()
        assertNotEquals(id, document.source.documentId)
        assertContentEquals(byteArrayOf(7), assertIs<SourceFileRead.Available>(f.controller.readSourceFile(document.source.documentId)).pages.single())
        assertEquals(1, f.repository.peekProfile(profileId).vaultDocuments.size, "the original is untouched")
        f.controller.switchProfile(profileId)
        assertContentEquals(byteArrayOf(7), assertIs<SourceFileRead.Available>(f.controller.readSourceFile(id)).pages.single())
    }

    @Test
    fun aSingleProfileBackupRestoresARecordThatSaysTheFileIsMissing() = runTest {
        val f = Fixture().start()
        val profileId = f.profileId
        f.add(pdf())
        val opened = sealedService().open(sealedService().create(f.data, pass.copyOf()), pass.copyOf())
        assertEquals(0, BackupContents.of(opened.members).originalFiles, "the restore screen reports it")

        val target = Fixture().start()
        target.controller.restoreHousehold(opened.members, RestoreMode.REPLACE_EXISTING)
        val restored = target.repository.readProfile(profileId).vaultDocuments.single().source
        assertNull(restored.vaultKey, "no dangling key")
        assertEquals(emptyList(), restored.pageVaultKeys)
    }

    @Test
    fun replacingAProfileFromAFileLessBackupKeepsFilesStillHeld() = runTest {
        val f = Fixture().start()
        val profileId = f.profileId
        f.add(pdf(bytes = byteArrayOf(9)))
        val id = f.documents.single().source.documentId
        val opened = sealedService().open(sealedService().create(f.data, pass.copyOf()), pass.copyOf())
        f.controller.restoreHousehold(opened.members, RestoreMode.REPLACE_EXISTING)
        assertEquals(profileId, f.profileId)
        assertContentEquals(byteArrayOf(9), assertIs<SourceFileRead.Available>(f.controller.readSourceFile(id)).pages.single())
    }

    @Test
    fun aDocumentThatCannotFitStopsTheBackupWithItsReason() = runTest {
        val f = Fixture().start()
        f.add(pdf(bytes = ByteArray(BackupService.MAX_BACKUP_WRITE_BYTES)))
        val failure = assertFailsWith<VitaMessageException> { f.controller.householdForBackup() }
        assertEquals(L10nKey.BACKUP_TOO_LARGE, failure.userText.key)
    }

    @Test
    fun filesTooLargeForTheBackupFileItselfAreRefusedNotDropped() = runTest {
        val member = BackupMember(
            ProfileData(Profile("p", "Ana", createdAt = t), vaultDocuments = listOf(vaultDoc("p", "v", "k"))),
            mapOf("v" to ByteArray(BackupService.MAX_BACKUP_WRITE_BYTES)),
        )
        assertEquals(L10nKey.BACKUP_TOO_LARGE, assertFailsWith<VitaMessageException> { sealedService().createHousehold(listOf(member), pass.copyOf()) }.userText.key)
    }

    @Test
    fun aDocumentThatCannotBeReadStopsTheBackupAndIsNamed() = runTest {
        val f = Fixture().start()
        f.add(pdf())
        val source = f.documents.single().source
        f.vault.delete(source.vaultKey!!)
        val failure = assertFailsWith<VitaMessageException> { f.controller.householdForBackup() }
        assertEquals(L10nKey.BACKUP_DOCUMENT_UNREADABLE, failure.userText.key)
        assertEquals(listOf("rx.pdf"), failure.userText.args)
    }

    @Test
    fun aFileLessDocumentDoesNotBlockTheBackup() = runTest {
        val f = Fixture().start()
        f.repository.addVaultDocument(vaultDoc(f.profileId, "no-file"))
        assertEquals(emptyMap(), f.controller.householdForBackup().single().files.mapValues { it.value.size })
    }

    @Test
    fun aBackupNamingAnotherFileOrAPageLikeIdIsRefused() = runTest {
        val base = ProfileData(Profile("p", "Ana", createdAt = t), vaultDocuments = listOf(vaultDoc("p", "v", "k")))
        val service = sealedService()
        val ok = service.open(service.createHousehold(listOf(BackupMember(base, mapOf("v" to byteArrayOf(1)))), pass.copyOf()), pass.copyOf())
        assertEquals(1, ok.members.single().files.size)
        val stray = service.createHousehold(listOf(BackupMember(base, mapOf("not-mine" to byteArrayOf(1)))), pass.copyOf())
        assertEquals(L10nKey.BACKUP_INVALID_CONTENT, assertFailsWith<VitaMessageException> { service.open(stray, pass.copyOf()) }.userText.key)
        val pageLike = base.copy(vaultDocuments = listOf(vaultDoc("p", "x-page-2")))
        val hostile = service.createHousehold(listOf(BackupMember(pageLike)), pass.copyOf())
        assertEquals(L10nKey.BACKUP_INVALID_CONTENT, assertFailsWith<VitaMessageException> { service.open(hostile, pass.copyOf()) }.userText.key)
    }

    @Test
    fun remappingKeepsTagsAndDropsKeys() {
        val data = ProfileData(Profile("p", "Ana", createdAt = t), vaultDocuments = listOf(vaultDoc("p", "v", "k", listOf("Id tag"))))
        var next = 0
        val remapped = data.remappedAsNew { "new-${next++}" }
        val document = remapped.vaultDocuments.single()
        assertEquals(remapped.profile.profileId, document.source.profileId)
        assertNull(document.source.vaultKey)
        assertEquals(listOf("Id tag"), document.tags)
        assertEquals(1, remapped.allDocuments().size)
    }
}
