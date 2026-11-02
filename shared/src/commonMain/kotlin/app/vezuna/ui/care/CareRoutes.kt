package app.vezuna.ui.care

import androidx.compose.runtime.Composable
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import app.vezuna.ui.AppScreen
import app.vezuna.ui.MedicationsScreen
import app.vezuna.ui.nav.LegacyPushed
import app.vezuna.ui.nav.RootScaffold
import app.vezuna.ui.nav.RouteContext

/** Lane L4 (Care). R0 stubs render the current medications screen; L4 replaces the bodies. */
object CareRoutes {
    @Composable
    fun Medications(ctx: RouteContext) = RootScaffold(ctx, vitaText(L10nKey.NAV_CARE)) {
        MedicationsScreen(ctx.state, ctx.controller, ctx.scope, ctx.reminders)
    }

    @Composable
    private fun Legacy(ctx: RouteContext) = LegacyPushed(ctx) { MedicationsScreen(ctx.state, ctx.controller, ctx.scope, ctx.reminders) }

    @Composable
    fun MedicationDetail(@Suppress("UNUSED_PARAMETER") screen: AppScreen.MedicationDetail, ctx: RouteContext) = Legacy(ctx)

    @Composable
    fun MedicationForm(@Suppress("UNUSED_PARAMETER") screen: AppScreen.MedicationForm, ctx: RouteContext) = Legacy(ctx)

    @Composable
    fun DoseHistory(@Suppress("UNUSED_PARAMETER") screen: AppScreen.DoseHistory, ctx: RouteContext) = Legacy(ctx)
}
