package app.vezuna.data

import app.vezuna.domain.keepingOnlyAvailableSourceFiles
import app.vezuna.domain.remappedAsNew
import app.vezuna.domain.vaultKeys
import app.vezuna.domain.SourceDocument
import app.vezuna.security.BackupMember
import app.vezuna.security.backupFileId
import app.vezuna.ui.RestoreMode
import app.vezuna.ui.SourceFileStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** What a restore stored: the profile IDs it wrote (in backup order) and whether any vitals could not be written. */
class RestoreOutcome(val profileIds: List<String>, val allVitalsRestored: Boolean)

/**
 * Restores backup members one by one. A profile that already exists is replaced or stored under fresh IDs ([mode]).
 * Original files carried by the backup are written to [files] before the records that point at them; if a member's
 * records cannot be stored, the files written for it are removed again (files of a replaced profile are never touched).
 * A member whose document IDs collide with another profile's is stored under fresh IDs instead of overwriting that profile's files.
 * Members stored before a failure stay stored.
 */
suspend fun VezunaRepository.restoreMembers(
    members: List<BackupMember>,
    mode: RestoreMode,
    files: SourceFileStore?,
    newId: () -> String,
): RestoreOutcome {
    val stored = mutableListOf<String>()
    var allVitals = true
    members.forEach { member ->
        val data = member.data
        val existing = if (profiles.value.any { it.profileId == data.profile.profileId }) peekProfile(data.profile.profileId) else null
        // Original-file keys derive from the document ID, so an ID already used by another profile would overwrite its file.
        val usedByOthers = profiles.value.filter { it.profileId != data.profile.profileId }
            .flatMapTo(mutableSetOf()) { peekProfile(it.profileId).documents.map { document -> document.documentId } }
        val collides = data.documents.any { it.documentId in usedByOthers }
        val toStore = when {
            mode == RestoreMode.RESTORE_AS_NEW || collides -> data.remappedAsNew(newId)
            existing == null -> data.keepingOnlyAvailableSourceFiles(null)
            else -> data.keepingOnlyAvailableSourceFiles(existing)
        }
        val keptKeys = existing?.takeIf { toStore.profile.profileId == it.profile.profileId }?.vaultKeys().orEmpty()
        val written = mutableListOf<String>()
        try {
            // Remapping keeps document order, so the n-th document of the backup is the n-th document to store.
            val withFiles = toStore.copy(documents = toStore.documents.mapIndexed { index, document ->
                if (files == null) document else files.restoreOriginals(document, data.documents[index], member.files, keptKeys, written)
            })
            if (!restoreWithVitals(withFiles)) allVitals = false
        } catch (error: Throwable) {
            withContext(NonCancellable) { written.forEach { files?.discard(it) } }
            throw error
        }
        stored += toStore.profile.profileId
    }
    return RestoreOutcome(stored, allVitals)
}

/**
 * Stores the page files [original] carried in the backup under [document]'s IDs and points [document] at them.
 * A kept original of the profile being replaced is already stored and is never rewritten. Pages are all-or-nothing:
 * a missing page would shift the numbering the page keys are bound to, so then only page 1 is restored.
 */
private suspend fun SourceFileStore.restoreOriginals(
    document: SourceDocument,
    original: SourceDocument,
    backupFiles: Map<String, ByteArray>,
    keptKeys: Set<String>,
    written: MutableList<String>,
): SourceDocument {
    if (document.vaultKey in keptKeys) return document
    val first = backupFiles[original.documentId] ?: return document
    val pages = (2..original.pageVaultKeys.size + 1).map { backupFiles[backupFileId(original.documentId, it)] }
    val key = save(document.profileId, document.documentId, first).also { written += it }
    val pageKeys = if (pages.all { it != null }) {
        pages.mapIndexed { index, bytes -> save(document.profileId, backupFileId(document.documentId, index + 2), bytes!!).also { written += it } }
    } else {
        emptyList()
    }
    return document.copy(vaultKey = key, pageVaultKeys = pageKeys)
}
