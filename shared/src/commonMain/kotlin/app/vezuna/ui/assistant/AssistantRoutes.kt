package app.vezuna.ui.assistant

import androidx.compose.runtime.Composable
import app.vezuna.ui.AppScreen
import app.vezuna.ui.nav.RouteContext

/** Lane L8 (assistant and local models), reached from More. Chat and model management are optional; the empty catalog is the truthful default. */
object AssistantRoutes {
    @Composable
    fun AiChat(ctx: RouteContext) = AssistantChat(ctx)

    @Composable
    fun Models(ctx: RouteContext) = AssistantModels(ctx)

    @Composable
    fun ModelDetail(@Suppress("UNUSED_PARAMETER") screen: AppScreen.ModelDetail, ctx: RouteContext) = Models(ctx)

    @Composable
    fun Activation(ctx: RouteContext) = Models(ctx)
}
