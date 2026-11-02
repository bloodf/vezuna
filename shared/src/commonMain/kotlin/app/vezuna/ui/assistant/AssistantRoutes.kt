package app.vezuna.ui.assistant

import androidx.compose.runtime.Composable
import app.vezuna.ui.AiChatScreen
import app.vezuna.ui.AppScreen
import app.vezuna.ui.ModelsScreen
import app.vezuna.ui.nav.LegacyPushed
import app.vezuna.ui.nav.RouteContext

/** Lane L8 (assistant and local models), reached from More. R0 stubs render the current screens. */
object AssistantRoutes {
    @Composable
    fun AiChat(ctx: RouteContext) = LegacyPushed(ctx) {
        AiChatScreen(ctx.state, ctx.loadedModelId, ctx.selectedModelId, ctx.controller, ctx.scope)
    }

    @Composable
    fun Models(ctx: RouteContext) = LegacyPushed(ctx) {
        ModelsScreen(ctx.state, ctx.controller, ctx.scope, ctx.modelPackLoader)
    }

    @Composable
    fun ModelDetail(@Suppress("UNUSED_PARAMETER") screen: AppScreen.ModelDetail, ctx: RouteContext) = Models(ctx)

    @Composable
    fun Activation(ctx: RouteContext) = Models(ctx)
}
