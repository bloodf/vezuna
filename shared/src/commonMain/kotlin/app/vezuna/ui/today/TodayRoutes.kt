package app.vezuna.ui.today

import androidx.compose.runtime.Composable
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import app.vezuna.ui.AppScreen
import app.vezuna.ui.DashboardScreen
import app.vezuna.ui.nav.LegacyPushed
import app.vezuna.ui.nav.RootScaffold
import app.vezuna.ui.nav.RouteContext

/**
 * Lane L1 (Today, onboarding, profiles). R0 stubs: each route renders the current screen so the app keeps
 * working; L1 replaces the bodies here and never edits the shell or ui/nav/Routes.kt.
 * Profile management currently lives on the dashboard, so the profile routes show it.
 */
object TodayRoutes {
    @Composable
    fun Dashboard(ctx: RouteContext) = RootScaffold(ctx, vitaText(L10nKey.NAV_TODAY)) {
        DashboardScreen(ctx.state, ctx.controller, ctx.scope)
    }

    @Composable
    fun Welcome(ctx: RouteContext) = LegacyPushed(ctx) { DashboardScreen(ctx.state, ctx.controller, ctx.scope) }

    @Composable
    fun FirstProfile(ctx: RouteContext) = LegacyPushed(ctx) { DashboardScreen(ctx.state, ctx.controller, ctx.scope) }

    @Composable
    fun Profiles(ctx: RouteContext) = LegacyPushed(ctx) { DashboardScreen(ctx.state, ctx.controller, ctx.scope) }

    @Composable
    fun ProfileEdit(@Suppress("UNUSED_PARAMETER") screen: AppScreen.ProfileEdit, ctx: RouteContext) = LegacyPushed(ctx) {
        DashboardScreen(ctx.state, ctx.controller, ctx.scope)
    }
}
