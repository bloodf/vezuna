package app.vezuna.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.vezuna.domain.ProfileData
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import app.vezuna.platform.currentInstant
import app.vezuna.security.BackupService
import app.vezuna.ui.components.GroupedRows
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.security.BackupHistory
import app.vezuna.ui.security.lastBackupText
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_archive

/** Native file access, supplied by each platform shell (SAF on Android, document picker and share sheet on iOS). */
class BackupFileAccess(
    /** Bytes of the chosen file, or null when the user cancelled. */
    val pick: suspend () -> ByteArray?,
    /** True when the file was saved or shared, false when the user cancelled. */
    val save: suspend (fileName: String, bytes: ByteArray) -> Boolean,
    /** Encoded [BackupHistory] from a plain device preference (never the vault); null when none was stored. */
    val loadHistory: () -> String? = { null },
    val saveHistory: (String) -> Unit = {},
)

/** Settings entry: opens Backups and shows when the household last saved one. */
@Composable
fun BackupSettingsSection(state: AppUiState, controller: AppController, files: BackupFileAccess?) {
    if (controller.backup == null || files == null) return
    val history = remember(files) { BackupHistory.decode(files.loadHistory()) }
    GroupedRows(title = vitaText(L10nKey.BACKUP_SECTION_TITLE)) {
        ListRow(
            title = vitaText(L10nKey.BACKUP_SCREEN_TITLE),
            supporting = lastBackupText(history, currentInstant(), state.data != null),
            leadingIcon = Res.drawable.ic_archive,
            category = Category.RECORDS,
            onClick = { controller.navigate(AppScreen.Backups) },
        )
    }
}

internal fun backupFileName(data: ProfileData): String {
    val safe = data.profile.displayName.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("").take(40).ifBlank { "profile" }
    val date = currentInstant().toLocalDateTime(TimeZone.currentSystemDefault()).date
    return "vezuna-$safe-$date.${BackupService.FILE_EXTENSION}"
}

internal fun householdBackupFileName(): String {
    val date = currentInstant().toLocalDateTime(TimeZone.currentSystemDefault()).date
    return "vezuna-household-$date.${BackupService.FILE_EXTENSION}"
}
