package app.vezuna.data

import app.vezuna.domain.keepingOnlyAvailableSourceFiles
import app.vezuna.domain.remappedAsNew
import app.vezuna.domain.vaultKeys
import app.vezuna.security.BackupMember
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
        val toStore = when {
            existing == null -> data.keepingOnlyAvailableSourceFiles(null)
            mode == RestoreMode.RESTORE_AS_NEW -> data.remappedAsNew(newId)
            else -> data.keepingOnlyAvailableSourceFiles(existing)
        }
        val keptKeys = existing?.takeIf { toStore.profile.profileId == it.profile.profileId }?.vaultKeys().orEmpty()
        val written = mutableListOf<String>()
        try {
            // Remapping keeps document order, so the n-th document of the backup is the n-th document to store.
            val withFiles = toStore.copy(documents = toStore.documents.mapIndexed { index, document ->
                val bytes = member.files[data.documents[index].documentId]
                if (files == null || bytes == null) {
                    document
                } else {
                    val key = files.save(document.profileId, document.documentId, bytes)
                    if (key !in keptKeys) written += key
                    document.copy(vaultKey = key)
                }
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
