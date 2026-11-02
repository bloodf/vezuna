package app.vezuna.ui.security

import androidx.compose.runtime.Composable
import app.vezuna.ui.SettingsScreen
import app.vezuna.ui.nav.LegacyPushed
import app.vezuna.ui.nav.RouteContext

/** Lane L6 (security, lock, vault, backup). Backup and app lock live in Settings today, so the stubs show it. */
object SecurityRoutes {
    @Composable
    private fun Legacy(ctx: RouteContext) = LegacyPushed(ctx) {
        SettingsScreen(ctx.state, ctx.controller, ctx.scope, ctx.backupFiles, ctx.reminders)
    }

    @Composable
    fun Backups(ctx: RouteContext) = Legacy(ctx)

    @Composable
    fun LockSettings(ctx: RouteContext) = Legacy(ctx)

    @Composable
    fun DataPrivacy(ctx: RouteContext) = Legacy(ctx)
}
