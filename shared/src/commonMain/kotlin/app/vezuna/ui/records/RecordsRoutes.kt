package app.vezuna.ui.records

import androidx.compose.runtime.Composable
import app.vezuna.ui.AppScreen
import app.vezuna.ui.nav.RouteContext

/** Lane L2 (Records): browse and search, report overview, original file, result detail. */
object RecordsRoutes {
    @Composable
    fun Labs(ctx: RouteContext) = RecordsScreen(ctx)

    @Composable
    fun ReportDetail(screen: AppScreen.ReportDetail, ctx: RouteContext) = ReportDetailScreen(ctx, screen.reportId)

    @Composable
    fun SourceViewer(screen: AppScreen.SourceViewer, ctx: RouteContext) = SourceViewerScreen(ctx, screen.documentId)

    @Composable
    fun ResultDetail(screen: AppScreen.ResultDetail, ctx: RouteContext) = ResultDetailPage(ctx, screen.observationId)

    @Composable
    fun HealthRecords(screen: AppScreen.HealthRecords, ctx: RouteContext) = HealthRecordListScreen(ctx, screen.kind)

    @Composable
    fun HealthRecordForm(screen: AppScreen.HealthRecordForm, ctx: RouteContext) = HealthRecordFormScreen(ctx, screen.kind, screen.recordId)
    fun Documents(ctx: RouteContext) = DocumentsScreen(ctx)

    @Composable
    fun DocumentDetail(screen: AppScreen.DocumentDetail, ctx: RouteContext) = DocumentDetailScreen(ctx, screen.documentId)
}
