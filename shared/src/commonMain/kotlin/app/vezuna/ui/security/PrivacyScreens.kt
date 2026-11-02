package app.vezuna.ui.security

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import app.vezuna.platform.currentInstant
import app.vezuna.ui.AppLockProblems
import app.vezuna.ui.AppLockToggle
import app.vezuna.ui.AppScreen
import app.vezuna.ui.Category
import app.vezuna.ui.LocalAppLock
import app.vezuna.ui.components.FocusedTaskScaffold
import app.vezuna.ui.components.GroupedRows
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.StatusKind
import app.vezuna.ui.nav.RouteContext
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_archive
import vezuna.shared.generated.resources.ic_clock
import vezuna.shared.generated.resources.ic_file_text
import vezuna.shared.generated.resources.ic_fingerprint
import vezuna.shared.generated.resources.ic_lock
import vezuna.shared.generated.resources.ic_upload
import vezuna.shared.generated.resources.ic_window
import vezuna.shared.generated.resources.ill_lock
import vezuna.shared.generated.resources.ill_privacy_device

/**
 * Images 096/097. The toggle authenticates through the system prompt; when the device has no screen lock the
 * truthful unavailable state shows instead (there is no in-app way to open the system security settings).
 */
@Composable
internal fun LockSettingsScreen(ctx: RouteContext) {
    val appLock = LocalAppLock.current
    FocusedTaskScaffold(title = vitaText(L10nKey.APP_LOCK_TITLE), onBack = ctx::back) {
        SecurityPage {
            SecurityPanel(vitaText(L10nKey.APP_LOCK_PANEL_TITLE), vitaText(L10nKey.APP_LOCK_PANEL_BODY), Res.drawable.ill_lock)
            if (appLock == null) {
                SecurityNotice(vitaText(L10nKey.APP_LOCK_UNAVAILABLE_TITLE), StatusKind.WARNING, detail = vitaText(L10nKey.APP_LOCK_NOT_OFFERED))
                return@SecurityPage
            }
            val state by appLock.state.collectAsState()
            GroupedRows { AppLockToggle(appLock) }
            AppLockProblems(state)
            GroupedRows(title = vitaText(L10nKey.APP_LOCK_WHAT_IT_DOES)) {
                ListRow(vitaText(L10nKey.APP_LOCK_TIMEOUT_TITLE), supporting = vitaText(L10nKey.APP_LOCK_TIMEOUT_BODY), leadingIcon = Res.drawable.ic_clock, category = Category.RECORDS)
                ListRow(vitaText(L10nKey.APP_LOCK_SWITCHER_TITLE), supporting = vitaText(L10nKey.APP_LOCK_SWITCHER_BODY), leadingIcon = Res.drawable.ic_window, category = Category.TRENDS)
            }
            if (state.enabled && !state.locked) {
                PrimaryButton(vitaText(L10nKey.APP_LOCK_NOW), appLock::lockNow, Modifier.fillMaxWidth(), icon = Res.drawable.ic_lock)
            }
        }
    }
}

/** Image 083: what stays on the device and where each privacy control lives. */
@Composable
internal fun DataPrivacyScreen(ctx: RouteContext) {
    val appLock = LocalAppLock.current
    val lockState = appLock?.state?.collectAsState()?.value
    val files = ctx.backupFiles
    val history = remember(files) { BackupHistory.decode(files?.loadHistory()) }
    FocusedTaskScaffold(title = vitaText(L10nKey.DATA_PRIVACY_TITLE), onBack = ctx::back) {
        SecurityPage {
            SecurityPanel(vitaText(L10nKey.DATA_PRIVACY_PANEL_TITLE), vitaText(L10nKey.DATA_PRIVACY_PANEL_BODY), Res.drawable.ill_privacy_device)
            GroupedRows {
                ListRow(vitaText(L10nKey.DATA_PRIVACY_ENCRYPTION), supporting = vitaText(L10nKey.DATA_PRIVACY_ENCRYPTION_BODY), leadingIcon = Res.drawable.ic_lock, category = Category.RECORDS)
                ListRow(
                    vitaText(L10nKey.APP_LOCK_TITLE),
                    supporting = vitaText(
                        when {
                            lockState == null -> L10nKey.APP_LOCK_STATUS_UNAVAILABLE
                            lockState.enabled -> L10nKey.APP_LOCK_STATUS_ON
                            else -> L10nKey.APP_LOCK_STATUS_OFF
                        },
                    ),
                    leadingIcon = Res.drawable.ic_fingerprint,
                    category = Category.CARE,
                    onClick = { ctx.navigate(AppScreen.LockSettings) },
                )
                ListRow(vitaText(L10nKey.DATA_PRIVACY_EXPORT), supporting = vitaText(L10nKey.DATA_PRIVACY_EXPORT_BODY), leadingIcon = Res.drawable.ic_upload, category = Category.TRENDS, onClick = { ctx.navigate(AppScreen.ExportHub) })
                ListRow(
                    vitaText(L10nKey.BACKUP_SCREEN_TITLE),
                    supporting = if (files != null && ctx.controller.backup != null) lastBackupText(history, currentInstant(), ctx.state.data != null) else vitaText(L10nKey.BACKUP_UNAVAILABLE),
                    leadingIcon = Res.drawable.ic_archive,
                    category = Category.ACTIVITY,
                    onClick = { ctx.navigate(AppScreen.Backups) },
                )
            }
            SecurityNotice(vitaText(L10nKey.DATA_PRIVACY_SHARING), StatusKind.INFO, detail = vitaText(L10nKey.DATA_PRIVACY_SHARING_BODY))
            GroupedRows {
                ListRow(vitaText(L10nKey.MORE_PRIVACY_POLICY), supporting = vitaText(L10nKey.DATA_PRIVACY_POLICY_BODY), leadingIcon = Res.drawable.ic_file_text, onClick = { ctx.navigate(AppScreen.PrivacyPolicy) })
            }
        }
    }
}
