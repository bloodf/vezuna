package app.vezuna.ui.today

import androidx.compose.runtime.Composable
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import app.vezuna.ui.AppScreen
import app.vezuna.ui.nav.RootScaffold
import app.vezuna.ui.nav.RouteContext

/** Lane L1: Today, first run and profiles. The shell and ui/nav/Routes.kt dispatch here. */
object TodayRoutes {
    /** Fresh installs see the first run here (no tabs) until they finish or skip it; it never gates records. */
    @Composable
    fun Dashboard(ctx: RouteContext) {
        if (ctx.state.onboarding && ctx.state.data != null) return OnboardingFlow(ctx)
        RootScaffold(ctx, vitaText(L10nKey.NAV_TODAY), subtitle = todayLabel()) { TodayScreen(ctx) }
    }

    /** Direct entries to the first-run steps (debug deep links); the app itself enters them through [Dashboard]. */
    @Composable
    fun Welcome(ctx: RouteContext) = WelcomeScreen(ctx, onStart = { ctx.navigate(AppScreen.FirstProfile) })

    @Composable
    fun FirstProfile(ctx: RouteContext) = FirstProfileScreen(ctx, onBack = ctx::back, onDone = ctx::back)

    @Composable
    fun Profiles(ctx: RouteContext) = ProfilesScreen(ctx)

    @Composable
    fun ProfileEdit(screen: AppScreen.ProfileEdit, ctx: RouteContext) = ProfileEditScreen(screen.profileId, ctx)
}
