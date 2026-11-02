package app.vezuna.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vezuna.domain.AnalyteConversions
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabComparator
import app.vezuna.domain.LabObservation
import app.vezuna.domain.ProfileData
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatLabValue
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.vitaPlural
import app.vezuna.localization.vitaText
import app.vezuna.ui.AppScreen
import app.vezuna.ui.Category
import app.vezuna.ui.ChartPoint
import app.vezuna.ui.LineChart
import app.vezuna.domain.TrendSeries
import app.vezuna.domain.referenceBand
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.CategoryPanel
import app.vezuna.ui.components.EmptyState
import app.vezuna.ui.components.FocusedTaskScaffold
import app.vezuna.ui.components.GroupedRows
import app.vezuna.ui.components.HistoryRange
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.RangeChips
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.components.SectionHeading
import app.vezuna.ui.components.StatusTag
import app.vezuna.ui.components.TertiaryButton
import app.vezuna.ui.nav.RouteContext
import app.vezuna.ui.observationValueText
import app.vezuna.ui.reportDisplayName
import app.vezuna.ui.tabular
import app.vezuna.ui.uiRangeText
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_calendar
import vezuna.shared.generated.resources.ic_check_circle
import vezuna.shared.generated.resources.ic_download
import vezuna.shared.generated.resources.ic_edit
import vezuna.shared.generated.resources.ic_file_text
import vezuna.shared.generated.resources.ic_history
import vezuna.shared.generated.resources.ic_info
import vezuna.shared.generated.resources.ic_records
import vezuna.shared.generated.resources.ill_file_error
import kotlin.math.abs

/**
 * One result (images 023 numeric, 024 text, 029 unavailable). The original value and unit come first, then the
 * collection day, the report's own reference and range wording, history, source and review state.
 */
@Composable
internal fun ResultDetailPage(ctx: RouteContext, observationId: String) = FocusedTaskScaffold(title = null, onBack = ctx::back) {
    val data = ctx.state.data ?: return@FocusedTaskScaffold
    val observation = data.observations.firstOrNull { it.observationId == observationId }
    if (observation == null) {
        EmptyState(
            Res.drawable.ill_file_error, vitaText(L10nKey.RESULT_UNAVAILABLE), vitaText(L10nKey.RESULT_UNAVAILABLE_EXPLANATION),
            Modifier.padding(VezunaTokens.Gutter), actionLabel = vitaText(L10nKey.RESULT_DETAIL_BACK), onAction = ctx::back,
        )
        return@FocusedTaskScaffold
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = VezunaTokens.Gutter, vertical = VezunaTokens.SpaceMd),
        verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
    ) {
        ValueCard(observation)
        if (!observation.isQualitative) History(ctx, data, observation)
        Details(ctx, data, observation)
        PrimaryButton(vitaText(L10nKey.EDIT_RESULT), { ctx.navigate(AppScreen.ResultEdit(observationId)) }, Modifier.fillMaxWidth(), icon = Res.drawable.ic_edit)
        SecondaryButton(vitaText(L10nKey.REPORT_DETAIL_CREATE_EXPORT), { ctx.navigate(AppScreen.ExportHub) }, Modifier.fillMaxWidth(), icon = Res.drawable.ic_download)
    }
}

/** Name and day on the Records panel; the clinical value itself on an opaque card below it. */
@Composable
private fun ValueCard(observation: LabObservation) {
    CategoryPanel(Category.RECORDS) {
        Text(observation.displayName, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(formatUiDate(observation.collectedAt), style = MaterialTheme.typography.titleMedium)
    }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(VezunaTokens.CardRadius), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
            Text(vitaText(L10nKey.ORIGINAL_RESULT), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(observationValueText(observation), style = MaterialTheme.typography.displaySmall.tabular())
            if (observation.isQualitative) {
                Text(vitaText(L10nKey.RESULT_DETAIL_RECORDED_AS_TEXT), style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            val assessment = LabComparator.assess(observation)
            StatusTag(vitaText(rangeKey(assessment)), rangeKind(assessment))
            Text(
                "${vitaText(L10nKey.RESULT_DETAIL_REPORT_RANGE)}: ${uiRangeText(observation.referenceRange)}",
                style = MaterialTheme.typography.bodyLarge.tabular(),
            )
            if (observation.normalizedUnit != observation.originalUnit || observation.normalizedValue != observation.originalValue) {
                Text(vitaText(L10nKey.NORMALIZED, formatLabValue(observation.normalizedValue), observation.normalizedUnit), style = MaterialTheme.typography.bodyMedium)
            }
            AnalyteConversions.alternatives(observation.testCode, observation.originalValue, observation.originalUnit).forEach {
                Text(vitaText(L10nKey.CONVERTED_VALUE, "${formatLabValue(it.value)} ${it.unit}", formatLabValue(it.factor)), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Chart plus the same points as text, and the arithmetic change since the previous compatible result. */
@Composable
private fun History(ctx: RouteContext, data: ProfileData, observation: LabObservation) {
    var range by rememberSaveable { mutableStateOf(HistoryRange.ALL) }
    val full = RecordsListing.history(data, observation, null) ?: return
    SectionHeading(vitaText(L10nKey.RESULT_DETAIL_HISTORY))
    if (full.points.size < 2) {
        Text(vitaText(L10nKey.RESULT_DETAIL_ONE_RESULT), style = MaterialTheme.typography.bodyLarge)
        return
    }
    RangeChips(range, { range = it })
    val windowed = RecordsListing.history(data, observation, range.months) ?: full
    if (windowed.points.size >= 2) {
        TrendChart(windowed, Modifier.fillMaxWidth().height(200.dp))
    }
    RecordsListing.changeSincePrevious(full, observation.observationId)?.let { change ->
        val sign = when {
            change.delta > 0 -> "+"
            change.delta < 0 -> "−"
            else -> ""
        }
        val amount = "$sign${formatLabValue(abs(change.delta), change.decimals)} ${change.unit}".trim()
        Text(vitaText(L10nKey.RESULT_DETAIL_CHANGE_SINCE, amount, formatUiDate(change.since)), style = MaterialTheme.typography.titleMedium.tabular())
    }
    GroupedRows {
        windowed.points.asReversed().forEach { point ->
            val converted = if (point.converted) " (${vitaText(L10nKey.RESULT_DETAIL_CONVERTED)})" else ""
            ListRow(
                title = "${formatLabValue(point.y, point.observation.valueDecimals.takeUnless { point.converted })} ${windowed.unit}$converted",
                supporting = formatUiDate(point.observation.collectedAt),
            )
        }
    }
    TertiaryButton(vitaText(L10nKey.RESULT_DETAIL_OPEN_TRENDS), { ctx.navigate(AppScreen.TrendDetail(full.testCode)) })
}

@Composable
private fun Details(ctx: RouteContext, data: ProfileData, observation: LabObservation) {
    val report = data.reports.firstOrNull { it.reportId == observation.reportId }
    GroupedRows {
        ListRow(vitaText(L10nKey.COLLECTION_DATE), supporting = formatUiDate(observation.collectedAt), leadingIcon = Res.drawable.ic_calendar)
        if (observation.isQualitative) {
            ListRow(vitaText(L10nKey.RESULT_DETAIL_REFERENCE_TEXT), supporting = uiRangeText(observation.referenceRange), leadingIcon = Res.drawable.ic_file_text)
            ListRow(vitaText(L10nKey.RESULT_DETAIL_ASSESSMENT), supporting = vitaText(L10nKey.RANGE_UNKNOWN), leadingIcon = Res.drawable.ic_info)
        }
        val confirmed = observation.confirmation == ConfirmationStatus.CONFIRMED
        ListRow(
            vitaText(L10nKey.RESULT_DETAIL_REVIEW_STATUS),
            supporting = vitaText(if (confirmed) L10nKey.RECORDS_STATUS_CONFIRMED else L10nKey.STATUS_NEEDS_REVIEW),
            leadingIcon = Res.drawable.ic_check_circle,
        )
        if (report != null) {
            ListRow(
                vitaText(L10nKey.RESULT_DETAIL_SOURCE),
                supporting = reportDisplayName(report, data, vitaText(L10nKey.MANUAL_ENTRY_REPORT)),
                leadingIcon = Res.drawable.ic_records,
                onClick = { ctx.navigate(AppScreen.ReportDetail(report.reportId)) },
            )
        }
        if (observation.revisions.isNotEmpty()) {
            ListRow(
                vitaText(L10nKey.RESULT_DETAIL_CORRECTIONS),
                supporting = vitaPlural(L10nKey.RESULT_DETAIL_CORRECTIONS_COUNT, observation.revisions.size),
                leadingIcon = Res.drawable.ic_history,
                onClick = { ctx.navigate(AppScreen.ResultHistory(observation.observationId)) },
            )
        }
    }
}

/** Dated chart for a result's history, drawn with the shared [LineChart] at actual elapsed-time positions. */
@Composable
private fun TrendChart(series: TrendSeries, modifier: Modifier) {
    val points = series.points
    val values = points.map { it.y }
    val first = points.first().xEpochSeconds
    val span = (points.last().xEpochSeconds - first).coerceAtLeast(1L).toFloat()
    val convertedCount = points.count { it.converted }
    val summary = vitaPlural(
        L10nKey.TREND_CHART_ACCESSIBILITY, points.size, series.unit,
        formatLabValue(values.min()), formatLabValue(values.max()), formatLabValue(values.last()),
    )
    val description = if (convertedCount == 0) summary else "$summary ${vitaText(L10nKey.CHART_CONVERTED_POINTS, convertedCount)}"
    LineChart(
        points = points.map { ChartPoint((it.xEpochSeconds - first) / span, it.y, hollow = it.converted) },
        identity = "${series.testCode}|${series.unit}",
        description = description,
        modifier = modifier,
        unit = series.unit,
        band = series.referenceBand(),
    )
}
