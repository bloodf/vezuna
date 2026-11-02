package app.vezuna.ui.trends

import androidx.compose.runtime.Composable
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import app.vezuna.ui.AppScreen
import app.vezuna.ui.TrendsScreen
import app.vezuna.ui.VitalsScreen
import app.vezuna.ui.nav.LegacyPushed
import app.vezuna.ui.nav.RootScaffold
import app.vezuna.ui.nav.RouteContext

/** Lane L5 (Trends, device measurements, connections). R0 stubs render the current screens. */
object TrendsRoutes {
    @Composable
    fun Trends(ctx: RouteContext) = RootScaffold(ctx, vitaText(L10nKey.NAV_TRENDS)) {
        TrendsScreen(ctx.state, ctx.controller)
    }

    @Composable
    fun TrendDetail(@Suppress("UNUSED_PARAMETER") screen: AppScreen.TrendDetail, ctx: RouteContext) = LegacyPushed(ctx) {
        TrendsScreen(ctx.state, ctx.controller)
    }

    @Composable
    fun Vitals(ctx: RouteContext) = LegacyPushed(ctx) { VitalsScreen(ctx.state, ctx.controller, ctx.scope) }

    @Composable
    fun VitalsDetail(@Suppress("UNUSED_PARAMETER") screen: AppScreen.VitalsDetail, ctx: RouteContext) = Vitals(ctx)

    @Composable
    fun Connections(ctx: RouteContext) = Vitals(ctx)
}
