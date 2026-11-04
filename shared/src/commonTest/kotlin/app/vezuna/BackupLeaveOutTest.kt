package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.data.VaultBackedRepository
import app.vezuna.data.VaultSourceFileStore
import app.vezuna.domain.Profile
import app.vezuna.domain.SourceDocument
import app.vezuna.domain.keepingOnlyAvailableSourceFiles
import app.vezuna.ui.SourceFileRead
import kotlin.test.assertIs
import app.vezuna.localization.L10nKey
import app.vezuna.localization.VitaMessageException
import app.vezuna.security.BackupMember
import app.vezuna.security.BackupService
import app.vezuna.security.OmittedDocument
import app.vezuna.security.PassphraseBackupCodec
import app.vezuna.ui.AppController
import app.vezuna.ui.PickedSource
import app.vezuna.ui.RestoreMode
import app.vezuna.ui.SourceFileStore
import app.vezuna.ui.entry.SourceKind
import app.vezuna.ui.security.withoutChat
import app.vezuna.ui.security.leftOutLines
import app.vezuna.ui.security.replacedWithLeftOut
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val pass = "correct horse battery".toCharArray()
private const val MIB = 1024 * 1024

private class Household {
    val vault = GenerationalMemoryVault()
    val repository = VaultBackedRepository(vault)
    lateinit var controller: AppController

    suspend fun start(): Household {
        repository.load()
        controller = labController(repository, VaultSourceFileStore(vault))
        controller.initialize()
        return this
    }

    val data get() = checkNotNull(controller.state.value.data)
    suspend fun add(name: String, size: Int, fill: Byte) =
        controller.addDocumentFrom(SourceKind.FILE) { PickedSource(name, "application/pdf", ByteArray(size) { fill }) }

    /** small 1 MiB, big 12 MiB, medium 6 MiB: 25 MiB encoded, over the 24 MiB limit; leaving out "big" fits. */
    suspend fun overLimit(): Household = start().also { it.add("small.pdf", MIB, 1); it.add("big.pdf", 12 * MIB, 2); it.add("medium.pdf", 6 * MIB, 3) }
}

private suspend fun schemaOf(file: ByteArray): Int =
    Json.parseToJsonElement(PassphraseBackupCodec.open(file, pass.copyOf()).decodeToString()).jsonObject.getValue("schemaVersion").jsonPrimitive.int

class BackupLeaveOutTest {
    private val service = BackupService()

    @Test
    fun overTheLimitListsTheDocumentsAndChangesNothing() = runTest(timeout = 5.minutes) {
        val h = Household().overLimit()
        val before = h.data

        assertEquals(L10nKey.BACKUP_TOO_LARGE_NAMED, assertFailsWith<VitaMessageException> { h.controller.householdForBackup() }.userText.key, "no backup members, so no file, until agreed")
        val proposal = h.controller.proposeLeaveOut()

        assertEquals(listOf("big.pdf"), proposal.documents.map { it.displayName })
        assertEquals(listOf(before.profile.displayName), proposal.documents.map { it.profileName })
        assertEquals(12L * MIB, proposal.documents.single().bytes)
        // Cancel is simply not acting on the proposal: documents, tags and files are as they were.
        assertEquals(before, h.data)
        assertEquals(3, h.data.vaultDocuments.size)
    }

    @Test
    fun anAgreedBackupLeavesOutOnlyTheListedDocumentsAndRecordsThem() = runTest(timeout = 10.minutes) {
        val h = Household().overLimit()
        val small = h.data.vaultDocuments.single { it.source.displayName == "small.pdf" }.source.documentId
        h.controller.updateDocument(small, "small.pdf", listOf("Rx"))
        val proposal = h.controller.proposeLeaveOut()
        val members = h.controller.householdForBackup(proposal.keys)
        val member = members.single()

        assertEquals(setOf("small.pdf", "medium.pdf", "big.pdf"), member.data.vaultDocuments.map { it.source.displayName }.toSet())
        assertEquals(listOf("big.pdf"), member.omitted.map { it.displayName })
        assertEquals(2, member.files.size)

        val file = service.createHousehold(members, pass.copyOf())
        assertEquals(BackupService.OMISSION_SCHEMA_VERSION, schemaOf(file), "older builds answer unsupported, not damaged")
        assertFalse(file.decodeToString(throwOnInvalidSequence = false).contains("big.pdf"), "the record lives inside the encryption")
        val opened = service.open(file, pass.copyOf())
        assertEquals(listOf("big.pdf"), opened.members.single().omitted.map { it.displayName })
        assertEquals(listOf("big.pdf · ${member.data.profile.displayName}"), opened.members.leftOutLines())

        val target = Household().start()
        target.controller.restoreHousehold(opened.members, RestoreMode.REPLACE_EXISTING)
        assertNull(target.controller.state.value.error)
        val restored = target.repository.readProfile(opened.members.single().data.profile.profileId)
        assertEquals(setOf("small.pdf", "medium.pdf", "big.pdf"), restored.vaultDocuments.map { it.source.displayName }.toSet())
        assertEquals(member.data.profile, restored.profile)
        assertEquals(member.data.reports, restored.reports)
        assertEquals(member.data.medications, restored.medications)
        assertEquals(listOf("Rx"), restored.vaultDocuments.single { it.source.displayName == "small.pdf" }.tags, "names and tags of what stayed")
        val smallKey = restored.vaultDocuments.single { it.source.displayName == "small.pdf" }.source.vaultKey!!
        assertContentEquals(ByteArray(MIB) { 1 }, VaultSourceFileStore(target.vault).load(restored.profile.profileId, restored.vaultDocuments.single { it.source.displayName == "small.pdf" }.source.documentId, smallKey))
    }

    @Test
    fun aBackupThatFitsHasNoOmissionRecordAndKeepsItsOldSchema() = runTest(timeout = 10.minutes) {
        val h = Household().overLimit()
        val big = h.data.vaultDocuments.single { it.source.displayName == "big.pdf" }.source.documentId
        h.controller.deleteDocument(big)

        val members = h.controller.householdForBackup()
        assertTrue(members.single().omitted.isEmpty())
        val file = service.createHousehold(members, pass.copyOf())
        assertEquals(BackupService.NEWER_SCHEMA_VERSION, schemaOf(file))
        assertFalse(PassphraseBackupCodec.open(file, pass.copyOf()).decodeToString().contains("omitted"), "older builds read it unchanged")
        assertTrue(service.open(file, pass.copyOf()).members.single().omitted.isEmpty())
    }

    @Test
    fun aNewerSchemaIsRefusedAsUnsupportedNotDamaged() = runTest {
        val newer = """{"schemaVersion":8,"exportedAt":"2026-01-01T00:00:00Z","members":[]}"""
        val failure = assertFailsWith<VitaMessageException> {
            service.open(PassphraseBackupCodec.seal(newer.encodeToByteArray(), pass.copyOf()), pass.copyOf())
        }
        assertEquals(L10nKey.BACKUP_UNSUPPORTED_SCHEMA, failure.userText.key)
    }

    @Test
    fun anOmissionWithSimultaneousFileIsDamaged() = runTest(timeout = 5.minutes) {
        val h = Household().start()
        h.add("a.pdf", 10, 1)
        val present = h.data.vaultDocuments.single().source.documentId
        val member = BackupMember(h.data, mapOf(present to byteArrayOf(1)), listOf(OmittedDocument(present, "a.pdf")))
        val file = service.createHousehold(listOf(member), pass.copyOf())
        assertEquals(L10nKey.BACKUP_INVALID_CONTENT, assertFailsWith<VitaMessageException> { service.open(file, pass.copyOf()) }.userText.key)
    }

    @Test
    fun replacingAProfileWithLeftOutDocumentsIsFlagged() {
        val withOmission = BackupMember(sampleProfileData("A"), emptyMap(), listOf(OmittedDocument("d", "x.pdf")))
        val plain = BackupMember(sampleProfileData("B"))
        val members = listOf(withOmission, plain)
        assertEquals(listOf("Ana"), members.replacedWithLeftOut { it == "A-profile" })
        assertEquals(emptyList(), members.replacedWithLeftOut { it == "B-profile" }, "a plain member replaces nothing that was left out")
        assertEquals(emptyList(), members.replacedWithLeftOut { false }, "restoring as new loses nothing")
    }

    @Test
    fun whenThePartsThatMustStayAlreadyExceedTheLimitNoBackupIsOffered() = runTest {
        val t = Instant.parse("2026-01-01T00:00:00Z")
        val repository = InMemoryVezunaRepository()
        repository.createProfile(Profile("p", "Ana", createdAt = t))
        repository.addDocument(SourceDocument("p", "lab", "lab.pdf", "application/pdf", "h", t, vaultKey = "k-lab"))
        val store = object : SourceFileStore {
            override suspend fun save(profileId: String, documentId: String, bytes: ByteArray) = "k-$documentId"
            override suspend fun discard(key: String) {}
            override suspend fun load(profileId: String, documentId: String, key: String) = ByteArray(BackupService.MAX_BACKUP_WRITE_BYTES)
        }
        val controller = labController(repository, store)
        assertEquals(L10nKey.BACKUP_CANNOT_FIT, assertFailsWith<VitaMessageException> { controller.proposeLeaveOut() }.userText.key)
    }

    @Test
    fun theFilesAloneJustUnderTheLimitStillNeedALeaveOutBecauseOfTheRecordsAndSeal() = runTest(timeout = 10.minutes) {
        val h = Household().start()
        // 4 x 4.5 MiB encodes to exactly 24 MiB of Base64: the limit before the records and the seal are counted.
        (1..4).forEach { h.add("d$it.pdf", 4 * MIB + MIB / 2, it.toByte()) }
        assertEquals(L10nKey.BACKUP_TOO_LARGE_NAMED, assertFailsWith<VitaMessageException> { h.controller.householdForBackup() }.userText.key)

        val proposal = h.controller.proposeLeaveOut()
        assertEquals(1, proposal.documents.size)
        val file = service.createHousehold(h.controller.householdForBackup(proposal.keys), pass.copyOf())
        assertTrue(file.size <= BackupService.MAX_BACKUP_WRITE_BYTES, "what was agreed to really fits: ${file.size}")
    }
    @Test
    fun sameDeviceReplacePreservesSoleCopyAndAsNewLeavesOriginalUntouched() = runTest(timeout = 10.minutes) {
        val h = Household().overLimit()
        val before = h.data
        val omitted = before.vaultDocuments.single { it.source.displayName == "big.pdf" }.source
        val proposal = h.controller.proposeLeaveOut()
        val opened = service.open(service.createHousehold(h.controller.householdForBackup(proposal.keys), pass.copyOf()), pass.copyOf())
        h.controller.restoreHousehold(opened.members, RestoreMode.REPLACE_EXISTING)
        assertNull(h.controller.state.value.error)
        val kept = h.repository.readProfile(before.profile.profileId).vaultDocuments.single { it.source.documentId == omitted.documentId }.source
        assertEquals(omitted.vaultKey, kept.vaultKey)
        assertContentEquals(ByteArray(12 * MIB) { 2 }, VaultSourceFileStore(h.vault).load(kept.profileId, kept.documentId, kept.vaultKey!!))
        h.controller.restoreHousehold(opened.members, RestoreMode.RESTORE_AS_NEW)
        assertNull(h.controller.state.value.error)
        assertEquals(2, h.repository.profiles.value.size)
        assertEquals(kept, h.repository.readProfile(before.profile.profileId).vaultDocuments.single { it.source.documentId == kept.documentId }.source)
        val fresh = h.repository.profiles.value.single { it.profileId != before.profile.profileId }
        assertNull(h.repository.readProfile(fresh.profileId).vaultDocuments.single { it.source.displayName == "big.pdf" }.source.vaultKey)
    }

    @Test
    fun consentRechecksNamesAndFilesAndNeverWritesBeforeAgreement() = runTest(timeout = 10.minutes) {
        val h = Household().overLimit()
        val first = h.controller.prepareHouseholdBackup(null, false)
        assertNull(first.members)
        val agreed = checkNotNull(first.proposal)
        val big = h.data.vaultDocuments.single { it.source.displayName == "big.pdf" }.source.documentId
        h.controller.updateDocument(big, "renamed.pdf", emptyList())
        val stale = h.controller.prepareHouseholdBackup(agreed, false)
        assertNull(stale.members)
        assertEquals("renamed.pdf", stale.proposal!!.documents.single().displayName)
        assertTrue(h.controller.prepareHouseholdBackup(stale.proposal, true).members!!.single().data.chat.isEmpty())
        h.controller.deleteDocument(big)
        val fits = h.controller.prepareHouseholdBackup(stale.proposal, false)
        assertNull(fits.proposal)
        assertTrue(fits.members!!.single().omitted.isEmpty())
    }

    @Test
    fun missingRecordSurvivesFreshRestoreRefillAndChatExclusion() = runTest(timeout = 10.minutes) {
        val h = Household().start()
        h.add("a.pdf", 10, 1)
        val doc = h.data.vaultDocuments.single().source
        val members = listOf(BackupMember(h.data, emptyMap(), listOf(OmittedDocument(doc.documentId, "a.pdf"))))
        val opened = service.open(service.createHousehold(members.withoutChat(), pass.copyOf()), pass.copyOf())
        val target = Household().start()
        target.controller.restoreHousehold(opened.members, RestoreMode.REPLACE_EXISTING)
        val restored = target.repository.readProfile(doc.profileId)
        assertNull(restored.vaultDocuments.single().source.vaultKey)
        assertEquals(doc.documentId, restored.vaultDocuments.single().source.documentId)
        target.add("a.pdf", 10, 1)
        assertEquals(1, target.data.vaultDocuments.size)
        assertTrue(target.data.vaultDocuments.single().source.vaultKey != null)
        assertTrue(target.controller.householdForBackup().all { it.omitted.isEmpty() })
    }

    @Test
    fun replacingAMissingBackupAfterRefillKeepsEveryLocalPage() = runTest(timeout = 10.minutes) {
        for (additionalPages in listOf(emptyList(), listOf(byteArrayOf(2), byteArrayOf(3)))) {
            val h = Household().start()
            val source = PickedSource("a.pdf", "application/pdf", byteArrayOf(1), additionalPages = additionalPages)
            h.controller.addDocumentFrom(SourceKind.FILE) { source }
            val doc = h.data.vaultDocuments.single().source
            val omitted = listOf(BackupMember(h.data, emptyMap(), listOf(OmittedDocument(doc.documentId, "a.pdf"))))
            val original = service.open(service.createHousehold(omitted, pass.copyOf()), pass.copyOf())
            val target = Household().start()
            target.controller.restoreHousehold(original.members, RestoreMode.REPLACE_EXISTING)
            assertNull(target.controller.state.value.error)
            assertNull(target.data.vaultDocuments.single().source.vaultKey)

            val missingMembers = target.controller.householdForBackup().filter { it.data.profile.profileId == doc.profileId }
            val missing = service.open(service.createHousehold(missingMembers, pass.copyOf()), pass.copyOf())
            assertNull(missing.members.single().data.vaultDocuments.single().source.vaultKey)
            assertEquals(doc.documentId, missing.members.single().omitted.single().documentId)
            target.controller.addDocumentFrom(SourceKind.FILE) { source }
            assertNull(target.controller.state.value.error)
            val refilled = target.data.vaultDocuments.single().source
            assertEquals(additionalPages.size, refilled.pageVaultKeys.size)
            assertTrue(refilled.vaultKey != null)

            target.controller.restoreHousehold(missing.members, RestoreMode.REPLACE_EXISTING)
            assertNull(target.controller.state.value.error)
            val kept = target.repository.readProfile(doc.profileId).vaultDocuments.single().source
            assertEquals(refilled.vaultKey, kept.vaultKey)
            assertEquals(refilled.pageVaultKeys, kept.pageVaultKeys)
            val read = assertIs<SourceFileRead.Available>(target.controller.readSourceFile(doc.documentId))
            assertEquals(1 + additionalPages.size, read.pages.size)
            (listOf(source.bytes) + additionalPages).zip(read.pages).forEach { (expected, actual) -> assertContentEquals(expected, actual) }
        }
    }

    @Test
    fun retentionRequiresMatchingProfileDocumentAndNonblankContentHash() = runTest {
        val h = Household().start()
        h.add("a.pdf", 10, 1)
        val current = h.data.vaultDocuments.single().source
        for (different in listOf(
            current.copy(sha256 = "different"),
            current.copy(sha256 = ""),
            current.copy(sha256 = " "),
            current.copy(profileId = "other"),
            current.copy(documentId = "other"),
        )) {
            val incoming = h.data.copy(documents = listOf(different), vaultDocuments = emptyList())
            val result = incoming.keepingOnlyAvailableSourceFiles(h.data).documents.single()
            assertNull(result.vaultKey)
            assertTrue(result.pageVaultKeys.isEmpty())
        }
        val blank = h.data.copy(documents = listOf(current.copy(sha256 = " ")), vaultDocuments = emptyList())
        assertNull(blank.keepingOnlyAvailableSourceFiles(blank).documents.single().vaultKey, "equal blank hashes are not content identity")
        val incoming = h.data.copy(documents = listOf(current.copy(vaultKey = null, pageVaultKeys = emptyList())), vaultDocuments = emptyList())
        assertEquals(current.vaultKey, incoming.keepingOnlyAvailableSourceFiles(h.data).documents.single().vaultKey, "lab and vault documents share retention")
    }

    @Test
    fun hostileOrDuplicateOmissionsAreRejected() = runTest(timeout = 10.minutes) {
        val h = Household().start()
        h.add("a.pdf", 10, 1)
        val id = h.data.vaultDocuments.single().source.documentId
        listOf(
            listOf(OmittedDocument("unknown", "a.pdf")),
            listOf(OmittedDocument(id, "a.pdf"), OmittedDocument(id, "a.pdf")),
            listOf(OmittedDocument(id, "\u202Ea.pdf")),
            listOf(OmittedDocument(id, "a\nb.pdf")),
            listOf(OmittedDocument(id, "x".repeat(256))),
        ).forEach { omissions ->
            val file = service.createHousehold(listOf(BackupMember(h.data, emptyMap(), omissions)), pass.copyOf())
            assertEquals(L10nKey.BACKUP_INVALID_CONTENT, assertFailsWith<VitaMessageException> { service.open(file, pass.copyOf()) }.userText.key)
        }
    }

    @Test
    fun chatIsExcludedBeforeNearLimitProposalAndAcrossProfiles() = runTest(timeout = 10.minutes) {
        val h = Household().start()
        h.add("near.pdf", 17 * MIB, 1)
        val original = h.data
        h.repository.restoreProfile(original.copy(chat = listOf(app.vezuna.domain.ChatMessage(
            original.profile.profileId, "chat", app.vezuna.domain.ChatMessage.Role.USER, "x".repeat(2 * MIB), original.profile.createdAt,
        ))))
        assertEquals(1, h.controller.prepareHouseholdBackup(null, false).proposal!!.documents.size)
        val without = h.controller.prepareHouseholdBackup(null, true)
        assertNull(without.proposal)
        assertTrue(without.members!!.single().data.chat.isEmpty())
        val second = sampleProfileData("second").copy(documents = emptyList(), reports = emptyList(), observations = emptyList(), ocrCandidates = emptyList())
        h.repository.restoreProfile(second)
        val profiles = h.controller.prepareHouseholdBackup(null, true).members!!
        assertEquals(2, profiles.size)
        assertTrue(profiles.all { it.data.chat.isEmpty() })
        assertEquals(2, service.open(service.createHousehold(profiles, pass.copyOf()), pass.copyOf()).members.size)
    }

    @Test
    fun lateGrowthFailsClosedBeforeAFileCanBeReturned() = runTest(timeout = 10.minutes) {
        val h = Household().overLimit()
        val agreement = h.controller.proposeLeaveOut()
        val members = h.controller.prepareHouseholdBackup(agreement, false).members!!
        val oversized = members.map { BackupMember(it.data, it.files + ("late" to ByteArray(BackupService.MAX_BACKUP_WRITE_BYTES)), it.omitted) }
        assertEquals(L10nKey.BACKUP_TOO_LARGE, assertFailsWith<VitaMessageException> { service.createHousehold(oversized, pass.copyOf()) }.userText.key)
    }

    @Test
    fun cancellationAtPreparationBoundaryPropagatesWithoutConsentOrMembers() = runTest {
        val repository = InMemoryVezunaRepository()
        val time = Instant.parse("2026-01-01T00:00:00Z")
        repository.createProfile(Profile("p", "Ana", createdAt = time))
        repository.addDocument(SourceDocument("p", "lab", "lab.pdf", "application/pdf", "h", time, vaultKey = "key"))
        val store = object : SourceFileStore {
            override suspend fun save(profileId: String, documentId: String, bytes: ByteArray): String = error("must not write")
            override suspend fun discard(key: String) = error("must not discard")
            override suspend fun load(profileId: String, documentId: String, key: String): ByteArray? = throw kotlinx.coroutines.CancellationException("cancel")
        }
        val controller = labController(repository, store)
        assertFailsWith<kotlinx.coroutines.CancellationException> { controller.prepareHouseholdBackup(null, false) }
        assertEquals("key", repository.peekProfile("p").documents.single().vaultKey)
    }

    @Test
    fun duplicateFileEntriesCannotHideBehindMapDecoding() = runTest {
        val data = sampleProfileData("A")
        val encoded = Json { encodeDefaults = true }.encodeToString(app.vezuna.domain.ProfileData.serializer(), data)
        val payload = """{"schemaVersion":7,"exportedAt":"2026-01-01T00:00:00Z","members":[{"data":$encoded,"files":[{"documentId":"A-doc","content":"AQ=="},{"documentId":"A-doc","content":"Ag=="}]}]}"""
        val file = PassphraseBackupCodec.seal(payload.encodeToByteArray(), pass.copyOf())
        assertEquals(L10nKey.BACKUP_INVALID_CONTENT, assertFailsWith<VitaMessageException> { service.open(file, pass.copyOf()) }.userText.key)
    }

}
