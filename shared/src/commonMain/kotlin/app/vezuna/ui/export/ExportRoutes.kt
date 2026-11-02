package app.vezuna.ui.export

import androidx.compose.runtime.Composable
import app.vezuna.ui.AppScreen
import app.vezuna.ui.components.FocusedTaskScaffold
import app.vezuna.ui.nav.RouteContext

/** Lane L9 (exports and PDF): hub, options, test selection and the preview that saves or shares the same bytes. */
object ExportRoutes {
    @Composable
    fun ExportHub(ctx: RouteContext) = FocusedTaskScaffold(title = null, onBack = ctx::back) { ExportHubScreen(ctx) }

    @Composable
    fun ExportOptions(screen: AppScreen.ExportOptions, ctx: RouteContext) =
        FocusedTaskScaffold(title = null, onBack = ctx::back) { ExportOptionsScreen(ctx, screen.kind) }

    @Composable
    fun ExportSelection(screen: AppScreen.ExportSelection, ctx: RouteContext) =
        FocusedTaskScaffold(title = null, onBack = ctx::back) { ExportSelectionScreen(ctx, screen.kind) }

    @Composable
    fun ExportPreview(screen: AppScreen.ExportPreview, ctx: RouteContext) =
        FocusedTaskScaffold(title = null, onBack = ctx::back) { ExportPreviewScreen(ctx, screen.kind) }
}
