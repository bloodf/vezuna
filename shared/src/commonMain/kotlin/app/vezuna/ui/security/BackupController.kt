package app.vezuna.ui.security

import app.vezuna.data.restoreMembers
import app.vezuna.localization.L10nKey
import app.vezuna.localization.VitaMessageException
import app.vezuna.localization.failMessage
import app.vezuna.localization.message
import app.vezuna.domain.ProfileData
import app.vezuna.domain.SourceDocument
import app.vezuna.platform.randomId
import app.vezuna.security.BackupMember
import app.vezuna.domain.allDocuments
import app.vezuna.security.BackupService
import app.vezuna.security.fileIds
import app.vezuna.security.OmittedDocument
import app.vezuna.security.backupFileId
import app.vezuna.ui.ControllerCore
import app.vezuna.ui.DashboardRefreshSignal
import app.vezuna.ui.RestoreMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class BackupController(
    private val core: ControllerCore,
    private val switchProfile: suspend (String) -> Unit,
    private val afterRestore: suspend (List<String>) -> Unit = {},
) {
    private val mutableRestoreResult = MutableStateFlow<RestoreResult?>(null)

    /** Outcome of the last restore started from Backups, shown there until dismissed (the restore resets navigation). */
    val restoreResult: StateFlow<RestoreResult?> = mutableRestoreResult.asStateFlow()

    fun showRestoreResult(result: RestoreResult?) {
        mutableRestoreResult.value = result
    }

    /** Profile-change clearing: the previous profile's restore outcome must not show for another. */
    fun onProfileChanged() {
        mutableRestoreResult.value = null
    }

    /** Backup restore. A profile that already exists is either replaced or the backup is stored under fresh IDs. */
    suspend fun restoreBackup(data: ProfileData, mode: RestoreMode) = restoreHousehold(listOf(BackupMember(data)), mode)

    /** Restores every profile of a backup, with the original files it carries. */
    suspend fun restoreHousehold(members: List<BackupMember>, mode: RestoreMode) = core.operation {
        val outcome = core.repository.restoreMembers(members, mode, core.sourceStore, ::randomId)
        core.mutableState.value = core.mutableState.value.copy(profiles = core.repository.profiles.value)
        switchProfile(outcome.profileIds.first())
        afterRestore(outcome.profileIds)
        DashboardRefreshSignal.notifyRepositoryChanged()
        core.mutableState.value = core.mutableState.value.copy(
            notice = message(if (outcome.allVitalsRestored) L10nKey.BACKUP_RESTORED else L10nKey.BACKUP_RESTORED_WITHOUT_VITALS),
        )
    }

    /**
     * Every profile with its vitals and original source files, for a household backup. A lab report's missing or
     * unreadable original is left out (its results stay, and restore says so). A vault document is nothing but its
     * file, so one that cannot be read stops the backup and names the document. Loading stops as soon as the files
     * cannot fit in a backup, naming the largest document seen so far. [omit] (profile ID to document ID) lists vault
     * documents the person agreed to leave out (see [proposeLeaveOut]); their bytes are not loaded; records remain and absent files are recorded on [BackupMember.omitted].
     */
    suspend fun householdForBackup(omit: Set<Pair<String, String>> = emptySet(), leaveOutChat: Boolean = false): List<BackupMember> {
        var encoded = 0L
        var largestName = ""
        var largestSize = -1L
        val budget = fileBudget(omit, leaveOutChat)
        return core.repository.profiles.value.map { profile ->
            val data = core.repository.peekProfile(profile.profileId)
            val files = mutableMapOf<String, ByteArray>()
            suspend fun collect(document: SourceDocument, required: Boolean) {
                var documentSize = 0L
                (listOfNotNull(document.vaultKey) + document.pageVaultKeys).forEachIndexed { index, key ->
                    val fileId = backupFileId(document.documentId, index + 1)
                    val bytes = readFile(document, fileId, key)
                    if (bytes == null) {
                        if (required) failMessage(L10nKey.BACKUP_DOCUMENT_UNREADABLE, document.displayName)
                        return@forEachIndexed
                    }
                    encoded += encodedSize(bytes)
                    documentSize += bytes.size
                    if (documentSize > largestSize) {
                        largestSize = documentSize
                        largestName = document.displayName
                    }
                    if (encoded > budget) failMessage(L10nKey.BACKUP_TOO_LARGE_NAMED, largestName)
                    files[fileId] = bytes
                }
            }
            val (left, kept) = data.vaultDocuments.partition { (profile.profileId to it.source.documentId) in omit }
            data.documents.forEach { collect(it, required = false) }
            kept.forEach { collect(it.source, required = true) }
            if (left.size + kept.count { it.source.vaultKey == null } > BackupService.MAX_OMITTED_DOCUMENTS) failMessage(L10nKey.BACKUP_CANNOT_FIT)
            BackupMember(
                if (leaveOutChat) data.copy(chat = emptyList()) else data,
                files,
                (left + kept.filter { it.source.vaultKey == null }).map { OmittedDocument(it.source.documentId, app.vezuna.domain.DocumentTags.cleanName(it.source.displayName)) },
            )
        }
    }

    /**
     * Which vault documents to leave out so a household backup fits: the largest first, one at a time, until the rest
     * fits. Nothing is left out by this call; the person decides. Fails with [L10nKey.BACKUP_CANNOT_FIT] when the lab
     * report originals alone do not fit, because leaving documents out would not help.
     */
    // ponytail: the store exposes no size metadata; decrypt one file at a time. Add authenticated sizes if proposal latency matters.
    suspend fun proposeLeaveOut(leaveOutChat: Boolean = false): LeaveOutProposal {
        var base = 0L
        val documents = mutableListOf<LeftOutDocument>()
        core.repository.profiles.value.forEach { profile ->
            val data = core.repository.peekProfile(profile.profileId)
            data.documents.forEach { document ->
                (listOfNotNull(document.vaultKey) + document.pageVaultKeys).forEachIndexed { index, key ->
                    readFile(document, backupFileId(document.documentId, index + 1), key)?.let { base += encodedSize(it) }
                }
            }
            data.vaultDocuments.forEach { vault ->
                val document = vault.source
                var size = 0L
                var raw = 0L
                (listOfNotNull(document.vaultKey) + document.pageVaultKeys).forEachIndexed { index, key ->
                    val bytes = readFile(document, backupFileId(document.documentId, index + 1), key)
                        ?: failMessage(L10nKey.BACKUP_DOCUMENT_UNREADABLE, document.displayName)
                    size += encodedSize(bytes)
                    raw += bytes.size
                }
                documents += LeftOutDocument(profile.profileId, profile.displayName, document.documentId, app.vezuna.domain.DocumentTags.cleanName(document.displayName), raw, size)
            }
        }
        var total = base + documents.sumOf { it.encodedBytes }
        val dropped = mutableListOf<LeftOutDocument>()
        for (document in documents.sortedWith(compareByDescending<LeftOutDocument> { it.encodedBytes }.thenBy { it.displayName }.thenBy { it.profileId }.thenBy { it.documentId })) {
            val keys = dropped.mapTo(HashSet()) { it.profileId to it.documentId }
            if (total <= fileBudget(keys, leaveOutChat)) break
            if (document.encodedBytes == 0L) continue
            dropped += document
            total -= document.encodedBytes
        }
        val keys = dropped.mapTo(HashSet()) { it.profileId to it.documentId }
        if (total > fileBudget(keys, leaveOutChat)) failMessage(L10nKey.BACKUP_CANNOT_FIT)
        if (dropped.groupingBy { it.profileId }.eachCount().any { it.value > BackupService.MAX_OMITTED_DOCUMENTS }) failMessage(L10nKey.BACKUP_CANNOT_FIT)
        return LeaveOutProposal(dropped)
    }

    /** Recheck consent against today's names and IDs before collecting any bytes for encryption. */
    suspend fun prepareHouseholdBackup(agreed: LeaveOutProposal?, leaveOutChat: Boolean): PreparedHouseholdBackup {
        val current = proposeLeaveOut(leaveOutChat)
        if (current.documents.isNotEmpty() && !current.sameDocumentsAs(agreed)) return PreparedHouseholdBackup(proposal = current)
        val members = householdForBackup(current.keys, leaveOutChat)
        val actual = LeaveOutProposal(current.documents.map { proposed ->
            val member = members.single { it.data.profile.profileId == proposed.profileId }
            val document = member.data.vaultDocuments.single { it.source.documentId == proposed.documentId }.source
            LeftOutDocument(proposed.profileId, member.data.profile.displayName, proposed.documentId, app.vezuna.domain.DocumentTags.cleanName(document.displayName), proposed.bytes, proposed.encodedBytes)
        })
        if (actual.documents.isNotEmpty() && !actual.sameDocumentsAs(agreed)) return PreparedHouseholdBackup(proposal = actual)
        return PreparedHouseholdBackup(members = members)
    }

    /** Room for the Base64 of the files: the limit minus everything else the sealed file holds (measured, see [BackupService.fixedOverhead]). */
    private suspend fun fileBudget(omit: Set<Pair<String, String>>, leaveOutChat: Boolean): Long {
        val shells = core.repository.profiles.value.map { profile ->
            val data = core.repository.peekProfile(profile.profileId)
            val (left, kept) = data.vaultDocuments.partition { (profile.profileId to it.source.documentId) in omit }
            BackupMember(if (leaveOutChat) data.copy(chat = emptyList()) else data, (data.documents + kept.map { it.source }).flatMap { it.fileIds() }.associateWith { ByteArray(0) }, (left + kept.filter { it.source.vaultKey == null }).map { OmittedDocument(it.source.documentId, app.vezuna.domain.DocumentTags.cleanName(it.source.displayName)) })
        }
        return BackupService.MAX_BACKUP_WRITE_BYTES - BackupService().fixedOverhead(shells)
    }

    private suspend fun readFile(document: SourceDocument, fileId: String, key: String): ByteArray? = try {
        core.sourceStore?.load(document.profileId, fileId, key)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (unreadable: Exception) {
        null
    }

    private fun encodedSize(bytes: ByteArray): Long = (bytes.size + 2L) / 3 * 4

    /** False only when size is the reason; a backup blocked by something else says so when it is made. */
    suspend fun householdFitsBackup(): Boolean = try {
        householdForBackup()
        true
    } catch (failure: VitaMessageException) {
        failure.userText.key != L10nKey.BACKUP_TOO_LARGE_NAMED
    } catch (failure: app.vezuna.security.VaultUnreadableException) {
        true
    }
}

/** A vault document that would be left out of a household backup. [bytes] is its size on disk, [encodedBytes] what it adds to the file. */
class LeftOutDocument(
    val profileId: String,
    val profileName: String,
    val documentId: String,
    val displayName: String,
    val bytes: Long,
    val encodedBytes: Long,
)

/** The documents proposed to leave out, largest first. Empty means everything fits. */
class LeaveOutProposal(val documents: List<LeftOutDocument>) {
    val keys: Set<Pair<String, String>> get() = documents.mapTo(HashSet()) { it.profileId to it.documentId }
    fun sameDocumentsAs(other: LeaveOutProposal?): Boolean = other != null &&
        documents.map { listOf(it.profileId, it.profileName, it.documentId, it.displayName) }.toSet() ==
        other.documents.map { listOf(it.profileId, it.profileName, it.documentId, it.displayName) }.toSet()
}

/** Exactly one of members ready to encrypt or a fresh consent request. No file is written here. */
class PreparedHouseholdBackup(val members: List<BackupMember>? = null, val proposal: LeaveOutProposal? = null)
