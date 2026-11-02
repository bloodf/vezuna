package app.vezuna.ui.records

import app.vezuna.ui.components.contentBottomInset
import app.vezuna.ui.components.clearOfArt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vezuna.domain.LabResultFilter
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.formatUiMonth
import app.vezuna.localization.vitaPlural
import app.vezuna.localization.vitaText
import app.vezuna.ui.AppScreen
import app.vezuna.ui.Category
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.CategoryPanel
import app.vezuna.ui.components.EmptyState
import app.vezuna.ui.components.GroupedRows
import app.vezuna.ui.components.LabeledField
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.components.SectionHeading
import app.vezuna.ui.components.SegmentedControl
import app.vezuna.ui.components.VezunaSheet
import app.vezuna.ui.nav.RootScaffold
import app.vezuna.ui.nav.RouteContext
import app.vezuna.ui.reportDisplayName
import kotlin.time.Instant
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_chevron_right
import vezuna.shared.generated.resources.ic_edit
import vezuna.shared.generated.resources.ic_filter
import vezuna.shared.generated.resources.ic_plus
import vezuna.shared.generated.resources.ic_records
import vezuna.shared.generated.resources.ill_record_empty
import vezuna.shared.generated.resources.ill_record_search
import vezuna.shared.generated.resources.ill_records

/** Two readable panes (list about 360dp, detail about 400dp) need this much content width (image 142). */
private val TwoPaneWidth = 760.dp

/** Records root (images 014-018, 142): search, filters, reports or results by month, review queue entry. */
@Composable
internal fun RecordsScreen(ctx: RouteContext) = RootScaffold(ctx, vitaText(L10nKey.NAV_RECORDS)) {
    val data = ctx.state.data ?: return@RootScaffold
    // Saved per profile by the shell, so returning from a detail restores the same query, filters and view.
    var query by rememberSaveable { mutableStateOf("") }
    var fromMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var toMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var outOfRangeOnly by rememberSaveable { mutableStateOf(false) }
    var showResults by rememberSaveable { mutableStateOf(false) }
    var selectedReportId by rememberSaveable { mutableStateOf<String?>(null) }
    var filtersOpen by remember { mutableStateOf(false) }
    val filter = LabResultFilter(query, fromMillis?.let(Instant::fromEpochMilliseconds), toMillis?.let(Instant::fromEpochMilliseconds), outOfRangeOnly)
    val clearFilters = { query = ""; fromMillis = null; toMillis = null; outOfRangeOnly = false }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoPane = maxWidth >= TwoPaneWidth
        val list = @Composable { modifier: Modifier ->
            RecordsList(
                ctx, filter, showResults, modifier,
                onQuery = { query = it },
                onShowResults = { showResults = it },
                onOpenFilters = { filtersOpen = true },
                onClearFilters = clearFilters,
                selectedReportId = selectedReportId.takeIf { twoPane },
                onOpenReport = { id -> if (twoPane) selectedReportId = id else ctx.navigate(AppScreen.ReportDetail(id)) },
            )
        }
        if (!twoPane) {
            list(Modifier.fillMaxSize())
        } else {
            Row(Modifier.fillMaxSize()) {
                list(Modifier.weight(0.45f).fillMaxHeight())
                Box(Modifier.weight(0.55f).fillMaxHeight()) {
                    val selected = selectedReportId?.takeIf { id -> data.reports.any { it.reportId == id } }
                    if (selected == null) {
                        Text(
                            vitaText(L10nKey.RECORDS_PANE_HINT),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center).padding(VezunaTokens.Gutter),
                        )
                    } else {
                        ReportDetailContent(ctx, selected, onDeleted = { selectedReportId = null })
                    }
                }
            }
        }
    }
    if (filtersOpen) {
        FilterSheet(filter, onApply = { applied ->
            fromMillis = applied.from?.toEpochMilliseconds()
            toMillis = applied.to?.toEpochMilliseconds()
            outOfRangeOnly = applied.outOfRangeOnly
            filtersOpen = false
        }, onDismiss = { filtersOpen = false })
    }
}

@Composable
private fun RecordsList(
    ctx: RouteContext,
    filter: LabResultFilter,
    showResults: Boolean,
    modifier: Modifier,
    onQuery: (String) -> Unit,
    onShowResults: (Boolean) -> Unit,
    onOpenFilters: () -> Unit,
    onClearFilters: () -> Unit,
    selectedReportId: String?,
    onOpenReport: (String) -> Unit,
) {
    val data = ctx.state.data ?: return
    val manualName = vitaText(L10nKey.MANUAL_ENTRY_REPORT)
    val nameOf = { entry: ReportEntry -> reportDisplayName(entry.report, data, manualName) }
    val reports = RecordsListing.reports(data, filter) { listOfNotNull(nameOf(it), it.document?.displayName) }
    val results = RecordsListing.results(data, filter)
    val pendingTotal = data.ocrCandidates.count { it.status == app.vezuna.domain.ConfirmationStatus.UNREVIEWED }
    val libraryEmpty = data.reports.isEmpty() && data.observations.isEmpty()
    val addRecord = { ctx.navigate(AppScreen.ImportSource) }

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(start = VezunaTokens.Gutter, end = VezunaTokens.Gutter, top = VezunaTokens.SpaceMd, bottom = VezunaTokens.SpaceMd + contentBottomInset()),
        verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
    ) {
        if (pendingTotal > 0) item { ReviewBanner(pendingTotal) { ctx.navigate(AppScreen.ImportReview) } }
        if (libraryEmpty) {
            item {
                EmptyState(
                    Res.drawable.ill_record_empty, vitaText(L10nKey.RECORDS_EMPTY_TITLE), vitaText(L10nKey.RECORDS_EMPTY_BODY),
                    actionLabel = vitaText(L10nKey.RECORDS_ADD), onAction = addRecord,
                )
            }
            return@LazyColumn
        }
        item { SearchAndFilters(filter, onQuery, onOpenFilters) }
        item {
            CategoryPanel(Category.RECORDS, illustration = Res.drawable.ill_records) {
                val confirmedReports = reports.count { it.confirmed.isNotEmpty() }
                Text(
                    if (filter.isActive) {
                        if (showResults) vitaPlural(L10nKey.LABS_RESULT_COUNT, results.size) else vitaPlural(L10nKey.RECORDS_REPORTS_FOUND, reports.size)
                    } else vitaPlural(L10nKey.RECORDS_REPORTS_COUNT, confirmedReports),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.clearOfArt().semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(vitaText(L10nKey.RECORDS_SAVED_ON_DEVICE), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.clearOfArt())
                SegmentedControl(
                    listOf(vitaText(L10nKey.RECORDS_VIEW_REPORTS), vitaText(L10nKey.RECORDS_VIEW_RESULTS)),
                    if (showResults) 1 else 0,
                    { onShowResults(it == 1) },
                    Modifier.padding(top = VezunaTokens.SpaceSm),
                )
            }
        }
        val nothing = if (showResults) results.isEmpty() else reports.isEmpty()
        if (nothing) {
            item {
                if (filter.isActive) {
                    val body = if (filter.text.isNotBlank()) vitaText(L10nKey.RECORDS_NO_MATCH_QUERY, filter.text.trim()) else vitaText(L10nKey.RECORDS_NO_MATCH_FILTERS)
                    EmptyState(
                        Res.drawable.ill_record_search, vitaText(L10nKey.RECORDS_NO_MATCH_TITLE), body,
                        actionLabel = vitaText(L10nKey.LABS_CLEAR_FILTERS), onAction = onClearFilters,
                    )
                } else {
                    EmptyState(Res.drawable.ill_record_empty, vitaText(L10nKey.NO_CONFIRMED_RESULTS), vitaText(L10nKey.LABS_EMPTY_EXPLANATION))
                }
            }
        } else if (showResults) {
            monthSections(RecordsListing.byMonth(results) { it.collectedAt }) { observation ->
                ResultListRow(observation, data) { ctx.navigate(AppScreen.ResultDetail(observation.observationId)) }
            }
        } else {
            monthSections(RecordsListing.byMonth(reports) { it.date }) { entry ->
                ReportRow(entry, nameOf(entry), isSelected = entry.report.reportId == selectedReportId) { onOpenReport(entry.report.reportId) }
            }
        }
        item { PrimaryButton(vitaText(L10nKey.RECORDS_ADD), addRecord, Modifier.fillMaxWidth(), icon = Res.drawable.ic_plus) }
    }
}

private fun <T> LazyListScope.monthSections(groups: List<Pair<Instant, List<T>>>, row: @Composable (T) -> Unit) {
    groups.forEach { (month, items) ->
        item { GroupedRows(title = formatUiMonth(month)) { items.forEach { row(it) } } }
    }
}

@Composable
private fun SearchAndFilters(filter: LabResultFilter, onQuery: (String) -> Unit, onOpenFilters: () -> Unit) = Column {
    LabeledField(vitaText(L10nKey.RECORDS_SEARCH_HINT), filter.text, onQuery)
    val active = listOf(filter.from != null, filter.to != null, filter.outOfRangeOnly).count { it }
    SecondaryButton(
        if (active == 0) vitaText(L10nKey.RECORDS_FILTERS) else vitaPlural(L10nKey.RECORDS_FILTERS_ACTIVE, active),
        onOpenFilters,
        Modifier.padding(top = VezunaTokens.SpaceSm),
        icon = Res.drawable.ic_filter,
    )
}

@Composable
private fun ReviewBanner(count: Int, onClick: () -> Unit) {
    CategoryPanel(Category.CARE, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd)) {
            Text(vitaPlural(L10nKey.RECORDS_REVIEW_BANNER, count), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Icon(painterResource(Res.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ReportRow(entry: ReportEntry, name: String, isSelected: Boolean, onClick: () -> Unit) {
    val details = buildList {
        add(formatUiDate(entry.date))
        if (entry.confirmed.isEmpty()) add(vitaText(L10nKey.RECORDS_NO_CONFIRMED_YET))
        else add(entry.confirmed.map { it.displayName }.distinct().joinToString(", "))
        if (entry.pending.isNotEmpty()) add(vitaPlural(L10nKey.RECORDS_REVIEW_BANNER, entry.pending.size))
    }
    ListRow(
        title = name,
        supporting = details.joinToString(" · "),
        leadingIcon = if (entry.report.isManual) Res.drawable.ic_edit else Res.drawable.ic_records,
        category = Category.RECORDS,
        onClick = onClick,
        modifier = if (isSelected) Modifier.semantics { selected = true } else Modifier,
    )
}

/** Dates and out-of-range only; applied together so the list does not jump while choosing. */
@Composable
private fun FilterSheet(current: LabResultFilter, onApply: (LabResultFilter) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(current) }
    VezunaSheet(vitaText(L10nKey.RECORDS_FILTER_TITLE), onDismiss) {
        Text(vitaText(L10nKey.RECORDS_FILTER_DATES), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        val anyDate = vitaText(L10nKey.LABS_FILTER_ANY_DATE)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
            FilterDateButton(vitaText(L10nKey.LABS_FILTER_FROM, draft.from?.let { formatUiDate(it) } ?: anyDate), draft.from) { draft = draft.copy(from = it) }
            FilterDateButton(vitaText(L10nKey.LABS_FILTER_TO, draft.to?.let { formatUiDate(it) } ?: anyDate), draft.to) { draft = draft.copy(to = it) }
        }
        FilterChip(
            selected = draft.outOfRangeOnly,
            onClick = { draft = draft.copy(outOfRangeOnly = !draft.outOfRangeOnly) },
            label = { Text(vitaText(L10nKey.LABS_FILTER_OUT_OF_RANGE), modifier = Modifier.padding(vertical = VezunaTokens.SpaceSmd)) },
        )
        PrimaryButton(vitaText(L10nKey.RECORDS_FILTER_APPLY), { onApply(draft) }, Modifier.fillMaxWidth())
        SecondaryButton(vitaText(L10nKey.RECORDS_FILTER_RESET), { onApply(draft.copy(from = null, to = null, outOfRangeOnly = false)) }, Modifier.fillMaxWidth())
    }
}
