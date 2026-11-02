package app.vezuna.ui.security

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.semantics.Role
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.localization.resolve
import app.vezuna.localization.vitaText
import app.vezuna.platform.currentInstant
import app.vezuna.security.BackupMember
import app.vezuna.ui.AppScreen
import app.vezuna.ui.Category
import app.vezuna.ui.RestoreMode
import app.vezuna.ui.components.FocusedTaskScaffold
import app.vezuna.ui.components.GroupedRows
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.components.StatusKind
import app.vezuna.ui.nav.Destination
import app.vezuna.ui.nav.RouteContext
import kotlinx.coroutines.launch
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_bell
import vezuna.shared.generated.resources.ic_history
import vezuna.shared.generated.resources.ic_lock
import vezuna.shared.generated.resources.ic_user
import vezuna.shared.generated.resources.ic_users
import vezuna.shared.generated.resources.ill_backup
import vezuna.shared.generated.resources.ill_file_error
import vezuna.shared.generated.resources.ill_success

/** What a restore started from Backups did, kept by the controller because the restore resets navigation. */
class RestoreResult(
    val failed: Boolean,
    val message: UserText,
    val names: String,
    val asNew: Boolean,
    val severalProfiles: Boolean,
) {
    val vitalsMissing: Boolean get() = (message as? UserText.Key)?.key == L10nKey.BACKUP_RESTORED_WITHOUT_VITALS
}

private enum class BackupsPage { OVERVIEW, CREATE, RESTORE }

/** Image 084 and the create/restore pages behind it (085-095). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun BackupsScreen(ctx: RouteContext) {
    val controller = ctx.controller
    val service = controller.backup
    val files = ctx.backupFiles
    val result by controller.restoreResult.collectAsState()
    var history by remember(files) { mutableStateOf(BackupHistory.decode(files?.loadHistory())) }
    var page by remember { mutableStateOf(BackupsPage.OVERVIEW) }
    val updateHistory: (BackupHistory) -> Unit = { next -> history = next; files?.saveHistory(next.encode()) }

    result?.let { outcome ->
        RestoreResultPage(
            outcome,
            onDone = { controller.showRestoreResult(null); page = BackupsPage.OVERVIEW },
            onOpenToday = { controller.showRestoreResult(null); controller.selectRoot(Destination.TODAY) },
            onRetry = { controller.showRestoreResult(null); page = BackupsPage.RESTORE },
        )
        return
    }
    val data = ctx.state.data
    when {
        service == null || files == null || page == BackupsPage.OVERVIEW || (page == BackupsPage.CREATE && data == null) ->
            Overview(ctx, history, available = service != null && files != null, updateHistory) { page = it }
        page == BackupsPage.CREATE && data != null -> {
            run {
                BackHandler { page = BackupsPage.OVERVIEW }
                CreateBackupFlow(
                    service, data, files,
                    household = if (ctx.state.profiles.size > 1 || data.documents.any { it.vaultKey != null }) controller::householdForBackup else null,
                    profileCount = ctx.state.profiles.size,
                    onSaved = { household ->
                        updateHistory(history.copy(savedAt = currentInstant(), household = household))
                        controller.announce(UserText.Key(L10nKey.BACKUP_SAVED))
                        page = BackupsPage.OVERVIEW
                    },
                    onClose = { page = BackupsPage.OVERVIEW },
                )
            }
        }
        else -> {
            RestoreBackupFlow(
                service = service,
                files = files,
                profileExists = { id -> ctx.state.profiles.any { it.profileId == id } },
                recovery = false,
                onConfirmed = { opened, mode -> ctx.scope.launch { restore(ctx, opened.members, mode) } },
                onClose = { page = BackupsPage.OVERVIEW },
            )
        }
    }
}

/**
 * Runs in the shell scope: a successful restore switches profile, which resets every back stack and leaves this
 * route. The outcome is parked in the controller and Backups is reopened to show it.
 */
private suspend fun restore(ctx: RouteContext, members: List<BackupMember>, mode: RestoreMode) {
    val controller = ctx.controller
    controller.restoreHousehold(members, mode)
    val state = controller.state.value
    controller.showRestoreResult(
        RestoreResult(
            failed = state.error != null,
            message = state.error ?: state.notice ?: UserText.Key(L10nKey.BACKUP_RESTORED),
            names = members.joinToString(", ") { it.data.profile.displayName },
            asNew = mode == RestoreMode.RESTORE_AS_NEW,
            severalProfiles = members.size > 1,
        ),
    )
    if (controller.navigator.state.value.current != AppScreen.Backups) controller.navigate(AppScreen.Backups) else controller.clearMessages()
}

@Composable
private fun Overview(ctx: RouteContext, history: BackupHistory, available: Boolean, onHistory: (BackupHistory) -> Unit, open: (BackupsPage) -> Unit) {
    val data = ctx.state.data
    val now = currentInstant()
    val householdOffered = ctx.state.profiles.size > 1 || data?.documents?.any { it.vaultKey != null } == true
    FocusedTaskScaffold(title = vitaText(L10nKey.BACKUP_SCREEN_TITLE), onBack = ctx::back) {
        SecurityPage {
            SecurityPanel(vitaText(L10nKey.BACKUP_PANEL_TITLE), vitaText(L10nKey.BACKUP_PANEL_BODY), Res.drawable.ill_backup)
            if (!available) SecurityNotice(vitaText(L10nKey.BACKUP_UNAVAILABLE), StatusKind.INFO)
            if (available) {
                GroupedRows {
                    ListRow(vitaText(L10nKey.BACKUP_LAST_TITLE), supporting = lastBackupText(history, now, data != null), leadingIcon = Res.drawable.ic_history, category = Category.RECORDS)
                }
                if (history.reminderDue(now, data != null)) {
                    SecurityNotice(
                        vitaText(L10nKey.BACKUP_DUE_TITLE),
                        StatusKind.WARNING,
                        detail = vitaText(if (history.savedAt == null) L10nKey.BACKUP_DUE_NEVER else L10nKey.BACKUP_DUE_OLD, BackupHistory.REMINDER_DAYS),
                    )
                }
            }
            PrimaryButton(vitaText(L10nKey.BACKUP_CREATE), { open(BackupsPage.CREATE) }, Modifier.fillMaxWidth(), enabled = available && data != null)
            SecondaryButton(vitaText(L10nKey.BACKUP_RESTORE), { open(BackupsPage.RESTORE) }, Modifier.fillMaxWidth(), enabled = available)
            GroupedRows(title = vitaText(L10nKey.BACKUP_HOLDS_TITLE)) {
                ListRow(vitaText(L10nKey.BACKUP_SCOPE_PROFILE), supporting = vitaText(L10nKey.BACKUP_SCOPE_PROFILE_BODY), leadingIcon = Res.drawable.ic_user, category = Category.RECORDS)
                if (householdOffered) {
                    ListRow(vitaText(L10nKey.BACKUP_SCOPE_HOUSEHOLD), supporting = vitaText(L10nKey.BACKUP_SCOPE_HOUSEHOLD_BODY), leadingIcon = Res.drawable.ic_users, category = Category.TRENDS)
                }
                ListRow(vitaText(L10nKey.BACKUP_RESTORE_SAFETY_TITLE), supporting = vitaText(L10nKey.BACKUP_RESTORE_SAFETY_BODY), leadingIcon = Res.drawable.ic_lock, category = Category.ACTIVITY)
            }
            if (available) {
                GroupedRows {
                    ListRow(
                        vitaText(L10nKey.BACKUP_REMIND_TITLE),
                        supporting = vitaText(L10nKey.BACKUP_REMIND_BODY, BackupHistory.REMINDER_DAYS),
                        leadingIcon = Res.drawable.ic_bell,
                        category = Category.CARE,
                        showChevron = false,
                        trailing = { Switch(checked = history.remind, onCheckedChange = null) },
                        modifier = Modifier.toggleable(value = history.remind, role = Role.Switch) { onHistory(history.copy(remind = it)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RestoreResultPage(result: RestoreResult, onDone: () -> Unit, onOpenToday: () -> Unit, onRetry: () -> Unit) {
    FocusedTaskScaffold(title = vitaText(L10nKey.BACKUP_RESTORE), onBack = onDone) {
        SecurityPage {
            if (result.failed) {
                SecurityHero(Res.drawable.ill_file_error, vitaText(L10nKey.BACKUP_RESTORE_FAILED_TITLE), result.message.resolve(), live = true)
                SecurityNotice(
                    vitaText(L10nKey.BACKUP_RESTORE_FAILED_KEPT),
                    StatusKind.INFO,
                    detail = if (result.severalProfiles) vitaText(L10nKey.BACKUP_RESTORE_FAILED_PARTIAL) else null,
                )
                PrimaryButton(vitaText(L10nKey.BACKUP_CHOOSE_ANOTHER), onRetry, Modifier.fillMaxWidth())
                SecondaryButton(vitaText(L10nKey.DONE_ACTION), onDone, Modifier.fillMaxWidth())
            } else {
                SecurityHero(
                    Res.drawable.ill_success,
                    vitaText(L10nKey.BACKUP_RESTORE_DONE_TITLE),
                    vitaText(if (result.asNew) L10nKey.BACKUP_RESTORED_AS_NEW else L10nKey.BACKUP_RESTORED_REPLACED, result.names),
                    live = true,
                )
                if (result.vitalsMissing) SecurityNotice(result.message.resolve(), StatusKind.WARNING)
                PrimaryButton(vitaText(L10nKey.BACKUP_OPEN_TODAY), onOpenToday, Modifier.fillMaxWidth())
                SecondaryButton(vitaText(L10nKey.DONE_ACTION), onDone, Modifier.fillMaxWidth())
            }
        }
    }
}
