package app.vezuna.ui.entry

import androidx.compose.runtime.Composable
import app.vezuna.ui.AppScreen
import app.vezuna.ui.nav.RouteContext

/**
 * Lane L3: add a record (system camera, photos and files, or manual entry), local extraction, review of drafts,
 * and correction of confirmed results. The same shared screens run on Android and iOS.
 */
object EntryRoutes {
    @Composable
    fun ImportReview(ctx: RouteContext) = ReviewQueueScreen(ctx)

    @Composable
    fun ImportSource(ctx: RouteContext) = ImportSourceScreen(ctx)

    /** Extraction progress and failures live on the source screen, so retry and the other sources stay one tap away. */
    @Composable
    fun ImportExtract(ctx: RouteContext) = ImportSourceScreen(ctx)

    @Composable
    fun CandidateReview(screen: AppScreen.CandidateReview, ctx: RouteContext) = CandidateReviewScreen(ctx, screen.candidateId)

    @Composable
    fun ManualEntry(ctx: RouteContext) = ManualEntryScreen(ctx)

    @Composable
    fun ResultEdit(screen: AppScreen.ResultEdit, ctx: RouteContext) = ResultEditScreen(ctx, screen.observationId)

    @Composable
    fun ResultHistory(screen: AppScreen.ResultHistory, ctx: RouteContext) = ResultHistoryScreen(ctx, screen.observationId)
}
