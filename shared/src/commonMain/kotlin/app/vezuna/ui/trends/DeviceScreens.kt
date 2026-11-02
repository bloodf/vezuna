package app.vezuna.ui.trends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vezuna.domain.ProfileData
import app.vezuna.domain.trends.DateWindow
import app.vezuna.domain.trends.HistorySeries
import app.vezuna.domain.trends.VitalSeriesKind
import app.vezuna.domain.trends.VitalTrends
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDateTime
import app.vezuna.localization.vitaPlural
import app.vezuna.localization.vitaText
import app.vezuna.ui.AppScreen
import app.vezuna.ui.Category
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.CategoryPanel
import app.vezuna.ui.components.EmptyState
import app.vezuna.ui.components.HistoryRange
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.components.SectionHeading
import app.vezuna.ui.nav.NativeAction
import app.vezuna.ui.nav.RouteContext
import app.vezuna.ui.tabular
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.DrawableResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_heart
import vezuna.shared.generated.resources.ic_refresh
import vezuna.shared.generated.resources.ic_steps
import vezuna.shared.generated.resources.ic_weight
import vezuna.shared.generated.resources.ill_activity_shoe
import vezuna.shared.generated.resources.ill_device_link
import vezuna.shared.generated.resources.ill_heart_rate
import vezuna.shared.generated.resources.ill_weight_scale

internal data class VitalCopy(val title: L10nKey, val unit: L10nKey, val granularity: L10nKey, val category: Category, val icon: DrawableResource, val illustration: DrawableResource)

internal fun VitalSeriesKind.copy(): VitalCopy = when (this) {
    VitalSeriesKind.STEPS -> VitalCopy(L10nKey.VITAL_STEPS, L10nKey.VITAL_UNIT_STEPS, L10nKey.TR_STEPS_DAILY_TOTAL, Category.ACTIVITY, Res.drawable.ic_steps, Res.drawable.ill_activity_shoe)
    VitalSeriesKind.HEART_RATE_DAILY_AVERAGE -> VitalCopy(L10nKey.TR_HR_DAILY_AVERAGE, L10nKey.VITAL_UNIT_BPM, L10nKey.TR_HR_GRANULARITY, Category.CARE, Res.drawable.ic_heart, Res.drawable.ill_heart_rate)
    VitalSeriesKind.WEIGHT -> VitalCopy(L10nKey.VITAL_WEIGHT, L10nKey.VITAL_UNIT_KG, L10nKey.TR_WEIGHT_GRANULARITY, Category.TRENDS, Res.drawable.ic_weight, Res.drawable.ill_weight_scale)
}

private fun vitalSeries(kind: VitalSeriesKind, data: ProfileData, range: HistoryRange, today: LocalDate): HistorySeries =
    VitalTrends.series(kind, data.vitals, range.toTrendRange(), today, TimeZone.currentSystemDefault())

/** Device values keep their recorded precision; steps are whole counts. */
@Composable
private fun vitalValue(value: Double, decimals: Int?): String = labValue(value, decimals)

/**
 * Image 072 / 074 / 076: device measurements of the current profile. Data exists only for the linked profile,
 * so another profile sees who owns it and an explicit switch, never someone else's numbers.
 */
@Composable
internal fun ColumnScope.DeviceOverview(ctx: RouteContext, data: ProfileData, today: LocalDate = localToday()) {
    val linkedId = ctx.state.healthLinkedProfileId
    val linked = ctx.state.profiles.firstOrNull { it.profileId == linkedId }
    if (linked != null && linked.profileId != data.profile.profileId) {
        LinkedElsewhere(ctx, linked.displayName, linked.profileId, data.profile.displayName, data.profile.profileId)
        return
    }
    if (data.vitals.isEmpty) {
        CategoryPanel(Category.ACTIVITY) {
            EmptyState(Res.drawable.ill_device_link, vitaText(L10nKey.TR_VITALS_EMPTY_TITLE), vitaText(L10nKey.TR_VITALS_EMPTY_BODY))
            PrimaryButton(vitaText(L10nKey.TR_CONNECT_DEVICE), { ctx.navigate(AppScreen.Connections) }, Modifier.fillMaxWidth(), container = VezunaTokens.Pine, content = Color.White)
        }
        return
    }
    LastImport(ctx, data)
    var range by remember(data.profile.profileId) { mutableStateOf(HistoryRange.ALL) }
    val all = VitalSeriesKind.entries.map { it to vitalSeries(it, data, range, today) }
    val available = all.mapNotNull { it.second.available }.takeIf { it.isNotEmpty() }?.let { w -> DateWindow(w.minOf { it.start }, w.maxOf { it.end }) }
    RangeSection(range, { range = it }, all.firstNotNullOfOrNull { it.second.window }, available)
    all.forEach { (kind, series) -> VitalCard(kind, series) { ctx.navigate(AppScreen.VitalsDetail(kind.name)) } }
    MutedText(vitaText(L10nKey.VITALS_RETENTION_NOTE))
}

@Composable
private fun LastImport(ctx: RouteContext, data: ProfileData) {
    TrendCard {
        val last = data.vitals.lastSync
        Text(
            if (last != null) vitaText(L10nKey.TR_LAST_IMPORT, formatUiDateTime(last)) else vitaText(L10nKey.TR_NO_IMPORT_YET),
            style = MaterialTheme.typography.titleMedium,
        )
        if (NativeAction.HEALTH in ctx.nativeActions) {
            SecondaryButton(vitaText(L10nKey.TR_IMPORT_NOW), { ctx.nativeActions.perform(NativeAction.HEALTH) }, Modifier.fillMaxWidth(), icon = Res.drawable.ic_refresh)
        }
    }
}

/** Image 076: the link belongs to someone else. */
@Composable
internal fun LinkedElsewhere(ctx: RouteContext, linkedName: String, linkedId: String, currentName: String, currentId: String) {
    var confirm by remember { mutableStateOf(false) }
    CategoryPanel(Category.ACTIVITY) {
        Text(vitaText(L10nKey.TR_LINKED_ELSEWHERE, linkedName), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(vitaText(L10nKey.TR_LINKED_ELSEWHERE_BODY, linkedName), style = MaterialTheme.typography.bodyLarge)
    }
    PrimaryButton(vitaText(L10nKey.SWITCH_TO_PROFILE, linkedName), { ctx.switchProfile(linkedId) }, Modifier.fillMaxWidth())
    SecondaryButton(vitaText(L10nKey.TR_RELINK, currentName), { confirm = true }, Modifier.fillMaxWidth())
    if (confirm) RelinkDialog(ctx, currentName, currentId, linkedName) { confirm = false }
}

@Composable
private fun VitalCard(kind: VitalSeriesKind, series: HistorySeries, onOpen: () -> Unit) {
    val copy = kind.copy()
    val unit = vitaText(copy.unit)
    CategoryPanel(copy.category, illustration = copy.illustration, onClick = onOpen) {
        Column(Modifier.fillMaxWidth().padding(end = 96.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceXs)) {
            Text(vitaText(copy.title), style = MaterialTheme.typography.titleLarge)
            Text(vitaText(copy.granularity), style = MaterialTheme.typography.bodyMedium)
            val latest = series.comparison.latest
            if (latest == null) {
                Text(vitaText(L10nKey.TR_STATUS_NO_READING), style = MaterialTheme.typography.titleMedium)
            } else {
                Text("${vitalValue(latest.value, latest.valueDecimals)} $unit", style = MaterialTheme.typography.headlineMedium.tabular())
                Text(dateText(latest.date), style = MaterialTheme.typography.bodyMedium)
                Text(vitaPlural(L10nKey.TR_RECORDED_COUNT, series.points.size), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Image 073: one device measurement with its chart and the dated daily values. */
@Composable
internal fun VitalDetailBody(ctx: RouteContext, data: ProfileData, kind: VitalSeriesKind, today: LocalDate = localToday()) {
    val copy = kind.copy()
    val unit = vitaText(copy.unit)
    val title = vitaText(copy.title)
    var range by remember(data.profile.profileId, kind) { mutableStateOf(HistoryRange.ALL) }
    val series = vitalSeries(kind, data, range, today)
    var selected by remember(series.key, range) { mutableStateOf<Int?>(null) }
    TrendColumn {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(vitaText(copy.granularity), style = MaterialTheme.typography.bodyLarge)
        val linkedId = ctx.state.healthLinkedProfileId
        val linked = ctx.state.profiles.firstOrNull { it.profileId == linkedId }
        if (linked != null && linked.profileId != data.profile.profileId) {
            LinkedElsewhere(ctx, linked.displayName, linked.profileId, data.profile.displayName, data.profile.profileId)
            return@TrendColumn
        }
        if (kind == VitalSeriesKind.HEART_RATE_DAILY_AVERAGE) {
            CategoryPanel(Category.CARE) { Text(vitaText(L10nKey.TR_HR_NOT_RESTING), style = MaterialTheme.typography.bodyLarge) }
        }
        RangeSection(range, { range = it }, series.window, series.available)
        if (series.points.isEmpty()) {
            NoReadingInPeriod(if (range != HistoryRange.ALL) ({ range = HistoryRange.ALL }) else null)
            return@TrendColumn
        }
        SeriesSummary(series, unit, { v, d -> vitalValue(v, d) }, vitaText(L10nKey.TR_LATEST))
        SeriesChart(series, title, unit, selected, { selected = it }) { v, d -> vitalValue(v, d) }
        ReadingTable(
            series, vitaText(if (kind == VitalSeriesKind.WEIGHT) L10nKey.TR_READINGS else L10nKey.TR_DAILY_VALUES), unit, selected, { selected = it },
        ) { v, d -> vitalValue(v, d) }
    }
}

/** Vitals route body (pushed from Connections / deep links). */
@Composable
internal fun DeviceMeasurementsBody(ctx: RouteContext, data: ProfileData) {
    TrendColumn {
        SectionHeading(vitaText(L10nKey.TR_DEVICE_TITLE))
        DeviceOverview(ctx, data)
    }
}
