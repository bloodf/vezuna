package app.vezuna.ui.security

import androidx.compose.runtime.Composable
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.localization.vitaText
import app.vezuna.security.BackupKind
import app.vezuna.security.BackupMember
import app.vezuna.security.OmittedDocument
import app.vezuna.security.OpenedBackup
import app.vezuna.ui.VezunaTheme
import app.vezuna.ui.components.FocusedTaskScaffold
import kotlin.time.Instant

/**
 * Screenshot-only screens for the backup leave-out flow (debug launch argument `-VezunaBackupPreview` with `choice`,
 * `restore`, `replace` or `result`). Synthetic names only; nothing is read from or written to the device.
 */
@Composable
fun BackupLeaveOutPreview(kind: String) {
    val ana = ProfileData(Profile("ana", "Ana", createdAt = Instant.parse("2026-01-01T00:00:00Z")))
    val omitted = listOf(OmittedDocument("d1", "MRI knee 2025.pdf"), OmittedDocument("d2", "Discharge summary.pdf"))
    val opened = OpenedBackup(BackupKind.PORTABLE, listOf(BackupMember(ana, emptyMap(), omitted)))
    VezunaTheme(animationsEnabled = false) {
        when (kind) {
            "choice" -> FocusedTaskScaffold(title = vitaText(L10nKey.BACKUP_CREATE_ACTION), onBack = {}) {
                LeaveOutPage(
                    LeaveOutProposal(
                        listOf(
                            LeftOutDocument("ana", "Ana", "d1", "MRI knee 2025.pdf", 12_582_912, 16_777_216),
                            LeftOutDocument("ana", "Ana", "d2", "Discharge summary.pdf", 3_145_728, 4_194_304),
                        ),
                    ),
                    onAgree = {}, onCancel = {},
                )
            }
            "result" -> RestoreResultPage(
                RestoreResult(false, UserText.Key(L10nKey.BACKUP_RESTORED), "Ana", asNew = false, severalProfiles = false, leftOut = opened.members.leftOutLines()),
                onDone = {}, onOpenToday = {}, onRetry = {},
            )
            else -> FocusedTaskScaffold(title = vitaText(L10nKey.BACKUP_RESTORE), onBack = {}) {
                PreviewPage(opened, profileExists = { kind == "replace" }, recovery = false, onRestore = {})
            }
        }
    }
}
