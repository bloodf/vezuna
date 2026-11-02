package app.vezuna.ui.entry

import androidx.compose.runtime.Composable
import app.vezuna.ui.AppScreen
import app.vezuna.ui.ImportReviewScreen
import app.vezuna.ui.ResultDetailScreen
import app.vezuna.ui.nav.LegacyPushed
import app.vezuna.ui.nav.RouteContext

/**
 * Lane L3 (import, review, result entry and editing). R0 stubs render the current screens: the import
 * screen covers source choice, extraction, review and manual entry; result detail holds the edit dialog.
 */
object EntryRoutes {
    @Composable
    private fun Import(ctx: RouteContext) = LegacyPushed(ctx) {
        ImportReviewScreen(ctx.state, ctx.controller, ctx.scope, ctx.sourcePicker)
    }

    @Composable
    fun ImportReview(ctx: RouteContext) = Import(ctx)

    @Composable
    fun ImportSource(ctx: RouteContext) = Import(ctx)

    @Composable
    fun ImportExtract(ctx: RouteContext) = Import(ctx)

    @Composable
    fun CandidateReview(@Suppress("UNUSED_PARAMETER") screen: AppScreen.CandidateReview, ctx: RouteContext) = Import(ctx)

    @Composable
    fun ManualEntry(ctx: RouteContext) = Import(ctx)

    @Composable
    fun ResultEdit(screen: AppScreen.ResultEdit, ctx: RouteContext) = LegacyPushed(ctx) {
        ResultDetailScreen(ctx.state, screen.observationId, ctx.controller)
    }

    @Composable
    fun ResultHistory(screen: AppScreen.ResultHistory, ctx: RouteContext) = LegacyPushed(ctx) {
        ResultDetailScreen(ctx.state, screen.observationId, ctx.controller)
    }
}
