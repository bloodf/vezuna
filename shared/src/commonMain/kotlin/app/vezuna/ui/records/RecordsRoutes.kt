package app.vezuna.ui.records

import androidx.compose.runtime.Composable
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import app.vezuna.ui.AppScreen
import app.vezuna.ui.LabsScreen
import app.vezuna.ui.ResultDetailScreen
import app.vezuna.ui.nav.LegacyPushed
import app.vezuna.ui.nav.RootScaffold
import app.vezuna.ui.nav.RouteContext

/** Lane L2 (Records). R0 stubs render the current screens; L2 replaces the bodies. */
object RecordsRoutes {
    @Composable
    fun Labs(ctx: RouteContext) = RootScaffold(ctx, vitaText(L10nKey.NAV_RECORDS)) {
        LabsScreen(ctx.state, ctx.controller)
    }

    @Composable
    fun ReportDetail(@Suppress("UNUSED_PARAMETER") screen: AppScreen.ReportDetail, ctx: RouteContext) = LegacyPushed(ctx) {
        LabsScreen(ctx.state, ctx.controller)
    }

    @Composable
    fun SourceViewer(@Suppress("UNUSED_PARAMETER") screen: AppScreen.SourceViewer, ctx: RouteContext) = LegacyPushed(ctx) {
        LabsScreen(ctx.state, ctx.controller)
    }

    @Composable
    fun ResultDetail(screen: AppScreen.ResultDetail, ctx: RouteContext) = LegacyPushed(ctx) {
        ResultDetailScreen(ctx.state, screen.observationId, ctx.controller)
    }
}
