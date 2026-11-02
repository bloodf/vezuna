package app.vezuna.ui.security

import androidx.compose.runtime.Composable
import app.vezuna.ui.nav.RouteContext

/** Lane L6 (security, lock, vault, backup). The lock screen and vault recovery live outside the routes (AppLockUi, VaultRecoveryScreen). */
object SecurityRoutes {
    @Composable
    fun Backups(ctx: RouteContext) = BackupsScreen(ctx)

    @Composable
    fun LockSettings(ctx: RouteContext) = LockSettingsScreen(ctx)

    @Composable
    fun DataPrivacy(ctx: RouteContext) = DataPrivacyScreen(ctx)
}
