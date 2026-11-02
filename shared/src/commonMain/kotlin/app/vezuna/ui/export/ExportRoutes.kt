package app.vezuna.ui.export

import androidx.compose.runtime.Composable
import app.vezuna.ui.AppScreen
import app.vezuna.ui.SettingsScreen
import app.vezuna.ui.nav.LegacyPushed
import app.vezuna.ui.nav.RouteContext

/** Lane L9 (exports and PDF). Doctor export lives in Settings today, so the stubs show it. */
object ExportRoutes {
    @Composable
    private fun Legacy(ctx: RouteContext) = LegacyPushed(ctx) {
        SettingsScreen(ctx.state, ctx.controller, ctx.scope, ctx.backupFiles, ctx.reminders)
    }

    @Composable
    fun ExportHub(ctx: RouteContext) = Legacy(ctx)

    @Composable
    fun ExportOptions(@Suppress("UNUSED_PARAMETER") screen: AppScreen.ExportOptions, ctx: RouteContext) = Legacy(ctx)

    @Composable
    fun ExportSelection(@Suppress("UNUSED_PARAMETER") screen: AppScreen.ExportSelection, ctx: RouteContext) = Legacy(ctx)

    @Composable
    fun ExportPreview(@Suppress("UNUSED_PARAMETER") screen: AppScreen.ExportPreview, ctx: RouteContext) = Legacy(ctx)
}
