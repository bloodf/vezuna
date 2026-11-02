package app.vezuna.ui.security

import app.vezuna.data.restoreMembers
import app.vezuna.localization.L10nKey
import app.vezuna.localization.failMessage
import app.vezuna.localization.message
import app.vezuna.domain.ProfileData
import app.vezuna.platform.randomId
import app.vezuna.security.BackupMember
import app.vezuna.security.BackupService
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
     * Every profile with its vitals and original source files, for a household backup. A missing or unreadable file is
     * left out; loading stops with BACKUP_TOO_LARGE as soon as the originals cannot fit in a backup.
     */
    suspend fun householdForBackup(): List<BackupMember> {
        var encoded = 0L
        return core.repository.profiles.value.map { profile ->
            val data = core.repository.peekProfile(profile.profileId)
            val files = mutableMapOf<String, ByteArray>()
            data.documents.forEach { document ->
                (listOfNotNull(document.vaultKey) + document.pageVaultKeys).forEachIndexed { index, key ->
                    val fileId = backupFileId(document.documentId, index + 1)
                    val bytes = try {
                        core.sourceStore?.load(document.profileId, fileId, key)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (unreadable: Exception) {
                        null
                    } ?: return@forEachIndexed
                    encoded += (bytes.size + 2L) / 3 * 4
                    if (encoded > BackupService.MAX_BACKUP_WRITE_BYTES) failMessage(L10nKey.BACKUP_TOO_LARGE)
                    files[fileId] = bytes
                }
            }
            BackupMember(data, files)
        }
    }
}
