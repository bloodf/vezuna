package app.vezuna.ui.trends

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.vezuna.domain.ProfileData
import app.vezuna.domain.trends.VitalSeriesKind
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import app.vezuna.ui.AppScreen
import app.vezuna.ui.components.EmptyState
import app.vezuna.ui.components.FocusedTaskScaffold
import app.vezuna.ui.components.SegmentedControl
import app.vezuna.ui.nav.RootScaffold
import app.vezuna.ui.nav.RouteContext
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ill_trend_empty

/** Lane L5: Trends (labs and device measurements), one test or measurement over time, and device connections. */
object TrendsRoutes {
    @Composable
    fun Trends(ctx: RouteContext) = RootScaffold(ctx, vitaText(L10nKey.NAV_TRENDS), vitaText(L10nKey.TR_SUBTITLE)) {
        WithProfile(ctx) { data ->
            var device by rememberSaveable { mutableStateOf(false) }
            TrendColumn {
                SegmentedControl(listOf(vitaText(L10nKey.TR_TAB_LABS), vitaText(L10nKey.TR_TAB_DEVICE)), if (device) 1 else 0, { device = it == 1 })
                if (device) DeviceOverview(ctx, data) else LabsOverview(ctx, data, onShowDevice = { device = true })
            }
        }
    }

    @Composable
    fun TrendDetail(screen: AppScreen.TrendDetail, ctx: RouteContext) = FocusedTaskScaffold(null, ctx::back) {
        WithProfile(ctx) { data -> TrendDetailBody(ctx, data, screen.testCode) }
    }

    @Composable
    fun Vitals(ctx: RouteContext) = FocusedTaskScaffold(vitaText(L10nKey.TR_DEVICE_TITLE), ctx::back) {
        WithProfile(ctx) { data -> DeviceMeasurementsBody(ctx, data) }
    }

    @Composable
    fun VitalsDetail(screen: AppScreen.VitalsDetail, ctx: RouteContext) {
        val kind = VitalSeriesKind.entries.firstOrNull { it.name == screen.metric }
        FocusedTaskScaffold(vitaText(kind?.let { it.copy().title } ?: L10nKey.TR_DEVICE_TITLE), ctx::back) {
            WithProfile(ctx) { data -> if (kind == null) DeviceMeasurementsBody(ctx, data) else VitalDetailBody(ctx, data, kind) }
        }
    }

    @Composable
    fun Connections(ctx: RouteContext) = FocusedTaskScaffold(vitaText(L10nKey.TR_CONNECTIONS_TITLE), ctx::back) {
        WithProfile(ctx) { data -> ConnectionsBody(ctx, data) }
    }

    @Composable
    private fun WithProfile(ctx: RouteContext, content: @Composable (ProfileData) -> Unit) {
        val data = ctx.state.data
        if (data == null) {
            TrendColumn { EmptyState(Res.drawable.ill_trend_empty, vitaText(L10nKey.NO_TRENDS), vitaText(L10nKey.SELECT_PROFILE_FIRST)) }
        } else {
            content(data)
        }
    }
}
