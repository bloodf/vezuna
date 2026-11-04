package app.vezuna.ui.records

import app.vezuna.domain.DocumentTags
import app.vezuna.domain.SourceDocument
import app.vezuna.domain.VaultDocument
import app.vezuna.localization.L10nKey
import app.vezuna.localization.failMessage
import app.vezuna.localization.message
import app.vezuna.platform.currentInstant
import app.vezuna.platform.randomId
import app.vezuna.security.backupFileId
import app.vezuna.ui.AppScreen
import app.vezuna.ui.ControllerCore
import app.vezuna.ui.PickedSource
import app.vezuna.ui.SourceFileStore
import app.vezuna.ui.entry.ImportFailure
import app.vezuna.ui.entry.ImportFailureReason
import app.vezuna.ui.entry.ImportSession
import app.vezuna.ui.entry.SourceKind
import app.vezuna.ui.nav.Navigator
import app.vezuna.ui.sourceIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Non-lab documents: add through the platform picker with extraction switched off, rename, tag, delete.
 * The bytes are only hashed (to spot the same file twice), encrypted and stored; no lab record is ever created.
 */
internal class DocumentVaultController(
    private val core: ControllerCore,
    private val navigator: Navigator,
    private val session: ImportSession,
    /** Whether a household backup still fits, checked after a file is added. */
    private val householdFitsBackup: suspend () -> Boolean,
    /** Extraction used to reject files that cannot be opened; here this does, because a stored file has no other way out. */
    private val fileOpens: suspend (String, List<ByteArray>) -> Boolean,
) {
    private val repository get() = core.repository
    private val mutableTagFilter = MutableStateFlow<String?>(null)

    /** Kept here, not in saved UI state: a tag is health data and must not reach the saved-instance Bundle. */
    val tagFilter: StateFlow<String?> = mutableTagFilter.asStateFlow()

    fun filterByTag(tag: String?) {
        mutableTagFilter.value = tag
    }

    fun onProfileChanged() {
        mutableTagFilter.value = null
    }

    /** A cancelled picker returns silently. A file picked for one profile never lands in another. */
    suspend fun addFrom(kind: SourceKind, picker: suspend () -> PickedSource?) {
        val profileId = core.mutableState.value.data?.profile?.profileId
        core.clearMessages()
        session.requestFileOnly(kind)
        val source = try {
            picker()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ImportFailure) {
            core.mutableState.value = core.mutableState.value.copy(error = message(failure.reason.documentFailure()))
            return
        } catch (error: Exception) {
            core.mutableState.value = core.mutableState.value.copy(error = message(L10nKey.DOCUMENTS_UNREADABLE))
            return
        }
        if (source == null) return
        if (profileId == null || core.mutableState.value.data?.profile?.profileId != profileId) {
            return core.announce(message(L10nKey.ENTRY_PROFILE_CHANGED))
        }
        // Once saving starts it always finishes: a file and its record land together or not at all.
        withContext(NonCancellable) { store(source) }
    }

    private suspend fun store(source: PickedSource) = core.operation {
        val data = core.requireData()
        val profileId = data.profile.profileId
        val files = core.sourceStore
        if (files == null || source.fileName.isBlank() || source.bytes.isEmpty()) failMessage(L10nKey.DOCUMENTS_UNREADABLE)
        if (!fileOpens(source.mediaType, listOf(source.bytes) + source.additionalPages)) failMessage(L10nKey.DOCUMENTS_UNREADABLE)
        val hash = sourceIdentity(source)
        val existing = data.vaultDocuments.firstOrNull { it.source.sha256 == hash }
        val documentId = existing?.source?.documentId ?: randomId()
        // The record may have no file (a backup that carried none) or point at one that is gone or damaged: the same file fills the gap.
        val refill = existing != null && !fileIsIntact(files, existing.source)
        when {
            existing == null -> saveFiles(files, profileId, documentId, source) { keys ->
                val name = source.fileName.trim().take(DocumentTags.MAX_NAME_LENGTH)
                repository.addVaultDocument(
                    VaultDocument(SourceDocument(profileId, documentId, name, source.mediaType, hash, currentInstant(), keys.first(), keys.drop(1))),
                )
            }
            refill -> saveFiles(files, profileId, documentId, source) { keys ->
                repository.updateVaultDocument(existing.copy(source = existing.source.copy(vaultKey = keys.first(), pageVaultKeys = keys.drop(1))))
            }
        }
        core.reloadCurrentProfile()
        navigator.navigate(AppScreen.DocumentDetail(documentId))
        val stored = existing == null || refill
        val notice = when {
            stored && !householdFitsBackup() -> L10nKey.DOCUMENTS_STORED_BACKUP_FULL
            existing == null -> L10nKey.DOCUMENTS_ADDED
            refill -> L10nKey.DOCUMENTS_FILE_RESTORED
            else -> L10nKey.DOCUMENTS_ALREADY_STORED
        }
        core.mutableState.value = core.mutableState.value.copy(notice = message(notice))
    }

    private suspend fun fileIsIntact(files: SourceFileStore, source: SourceDocument): Boolean {
        val keys = listOfNotNull(source.vaultKey) + source.pageVaultKeys
        return try {
            keys.isNotEmpty() && keys.withIndex().all { (index, key) -> files.load(source.profileId, backupFileId(source.documentId, index + 1), key) != null }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unreadable: Exception) {
            false
        }
    }

    /** A stored file must never outlive a record that was not written. */
    private suspend fun saveFiles(files: SourceFileStore, profileId: String, documentId: String, source: PickedSource, record: suspend (List<String>) -> Unit) {
        val saved = mutableListOf<String>()
        try {
            saved += files.save(profileId, documentId, source.bytes)
            source.additionalPages.forEachIndexed { index, page -> saved += files.save(profileId, backupFileId(documentId, index + 2), page) }
            record(saved)
        } catch (error: Throwable) {
            withContext(NonCancellable) { saved.forEach { files.discard(it) } }
            throw error
        }
    }

    suspend fun update(documentId: String, name: String, tags: List<String>) = core.operation {
        val data = core.requireData()
        val current = data.vaultDocuments.firstOrNull { it.source.documentId == documentId } ?: failMessage(L10nKey.DOCUMENTS_NOT_FOUND)
        val cleanName = name.trim().take(DocumentTags.MAX_NAME_LENGTH)
        if (cleanName.isEmpty()) failMessage(L10nKey.DOCUMENTS_NAME_REQUIRED)
        repository.updateVaultDocument(current.copy(source = current.source.copy(displayName = cleanName), tags = DocumentTags.clean(tags)))
        core.reloadCurrentProfile()
        core.mutableState.value = core.mutableState.value.copy(notice = message(L10nKey.DOCUMENTS_UPDATED))
    }

    /**
     * The repository removes the record first and the encrypted file once that is durable, so a failed write keeps both.
     * ponytail: a file delete that fails after the record is gone leaves that encrypted stray with no retry, like lab
     * originals (the person sees the error); add a key sweep at load if that ever shows up. The profile reloads either way.
     */
    suspend fun delete(documentId: String) = core.operation {
        val data = core.requireData()
        if (data.vaultDocuments.none { it.source.documentId == documentId }) failMessage(L10nKey.DOCUMENTS_NOT_FOUND)
        try {
            repository.deleteVaultDocument(data.profile.profileId, documentId)
        } finally {
            withContext(NonCancellable) { core.reloadCurrentProfile() }
        }
        core.mutableState.value = core.mutableState.value.copy(notice = message(L10nKey.DOCUMENTS_DELETED))
    }

    private fun ImportFailureReason.documentFailure(): L10nKey = when (this) {
        ImportFailureReason.TOO_LARGE -> L10nKey.ENTRY_TOO_LARGE_BODY
        ImportFailureReason.CAMERA_UNAVAILABLE -> L10nKey.ENTRY_CAMERA_UNAVAILABLE_BODY
        ImportFailureReason.CAMERA_DENIED -> L10nKey.ENTRY_CAMERA_DENIED_BODY
        ImportFailureReason.UNREADABLE, ImportFailureReason.TOO_MANY_PAGES -> L10nKey.DOCUMENTS_UNREADABLE
    }
}
