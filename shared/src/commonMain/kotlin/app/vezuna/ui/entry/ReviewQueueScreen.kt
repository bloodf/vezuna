package app.vezuna.ui.entry

import app.vezuna.ui.components.clearOfNavigationBar
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vezuna.domain.LabReport
import app.vezuna.domain.OcrCandidate
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.vitaPlural
import app.vezuna.localization.vitaText
import app.vezuna.ui.AppScreen
import app.vezuna.ui.Category
import app.vezuna.ui.CollectionDateField
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.EmptyState
import app.vezuna.ui.components.FocusedTaskScaffold
import app.vezuna.ui.components.GroupedRows
import app.vezuna.ui.components.LabeledField
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.components.StatusKind
import app.vezuna.ui.components.StatusTag
import app.vezuna.ui.components.TertiaryButton
import app.vezuna.ui.components.VezunaSheet
import app.vezuna.ui.nav.RouteContext
import app.vezuna.ui.reportDisplayName
import app.vezuna.ui.tabular
import kotlinx.coroutines.launch
import kotlin.time.Instant
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_file_text
import vezuna.shared.generated.resources.ic_info
import vezuna.shared.generated.resources.ic_scan
import vezuna.shared.generated.resources.ic_undo
import vezuna.shared.generated.resources.ill_record_empty
import vezuna.shared.generated.resources.ill_success

/**
 * Review queue (039): every unconfirmed draft grouped by source, with its flags beside it. Review complete (046)
 * appears only when drafts were actually confirmed in this session. Rejected drafts can be restored.
 */
@Composable
internal fun ReviewQueueScreen(ctx: RouteContext) {
    val session = ctx.controller.importSession
    val confirmed by session.confirmedCount.collectAsState()
    val details by session.details.collectAsState()
    val chosenDates by session.chosenDates.collectAsState()
    var showDetails by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LabReport?>(null) }
    FocusedTaskScaffold(title = vitaText(L10nKey.ENTRY_REVIEW_TITLE), onBack = ctx::back) {
        val data = ctx.state.data ?: return@FocusedTaskScaffold
        val groups = data.reviewGroups()
        val pendingTotal = groups.sumOf { it.pending.size }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).clearOfNavigationBar().padding(horizontal = VezunaTokens.Gutter, vertical = VezunaTokens.SpaceMd),
            verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
        ) {
            when {
                pendingTotal > 0 -> PendingBanner(pendingTotal)
                confirmed > 0 -> ReviewComplete(confirmed, onRecords = { ctx.navigate(AppScreen.Labs) }, onImport = { ctx.controller.navigator.finishAndGo(AppScreen.ImportSource) })
                else -> EmptyState(
                    Res.drawable.ill_record_empty, vitaText(L10nKey.NOTHING_AWAITING_REVIEW), vitaText(L10nKey.REVIEW_TRACKING),
                    actionLabel = vitaText(L10nKey.ENTRY_ADD_RECORD), onAction = { ctx.controller.navigator.finishAndGo(AppScreen.ImportSource) },
                )
            }
            details?.let { found ->
                Column(Modifier.fillMaxWidth()) {
                    ListRow(
                        vitaText(L10nKey.ENTRY_DETAILS_TITLE),
                        onClick = { showDetails = true },
                        supporting = vitaPlural(L10nKey.ENTRY_DETAILS_SUMMARY, found.pages.size),
                        leadingIcon = Res.drawable.ic_scan,
                        category = Category.TRENDS,
                    )
                }
            }
            groups.filter { it.pending.isNotEmpty() }.forEach { group ->
                PendingGroup(ctx, group, chosenDates, onEditReport = { editing = it })
            }
            val rejected = groups.flatMap { it.rejected }
            if (rejected.isNotEmpty()) RejectedSection(ctx, rejected)
            // Another file or a typed result stays one tap away while drafts wait for review.
            if (pendingTotal > 0) SecondaryButton(vitaText(L10nKey.ENTRY_ADD_RECORD), { ctx.navigate(AppScreen.ImportSource) }, Modifier.fillMaxWidth())
        }
        if (showDetails) details?.let { ProcessingDetailsSheet(it) { showDetails = false } }
        editing?.let { report -> ReportDetailsSheet(report, onDismiss = { editing = null }) { name, date ->
            editing = null
            ctx.scope.launch { ctx.controller.updateReportDetails(report.reportId, name, date) }
        } }
    }
}

@Composable
private fun PendingBanner(count: Int) {
    Surface(shape = RoundedCornerShape(VezunaTokens.CardRadius), color = Category.CARE.fill, contentColor = Category.CARE.onFill) {
        Row(
            Modifier.fillMaxWidth().padding(VezunaTokens.SpaceMd).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
        ) {
            Icon(painterResource(Res.drawable.ic_file_text), contentDescription = null, modifier = Modifier.size(32.dp))
            Column(Modifier.weight(1f)) {
                Text(vitaPlural(L10nKey.ENTRY_VALUES_TO_CHECK, count), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(vitaText(L10nKey.ENTRY_NOTHING_CONFIRMED_UNTIL), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PendingGroup(ctx: RouteContext, group: ReviewGroup, chosenDates: Map<String, Instant>, onEditReport: (LabReport) -> Unit) {
    val data = ctx.state.data ?: return
    val name = group.report?.let { reportDisplayName(it, data, "") }?.ifBlank { null } ?: group.document?.displayName.orEmpty()
    val dates = group.pending.map { chosenDates[it.candidateId] ?: it.collectedAt.takeIf { _ -> it.collectedAtKnown } }
    val sharedDate = dates.firstOrNull()?.takeIf { first -> dates.all { it == first } }
    val pages = group.document?.let { 1 + it.pageVaultKeys.size } ?: 1
    val supporting = listOfNotNull(
        sharedDate?.let { vitaText(L10nKey.ENTRY_COLLECTION_DATE_VALUE, formatUiDate(it)) },
        group.report?.orderedAt?.let { vitaText(L10nKey.REPORT_ORDER_DATE, formatUiDate(it)) },
        if (pages > 1) vitaPlural(L10nKey.ENTRY_PAGES, pages) else null,
    ).joinToString("\n").ifBlank { null }
    GroupedRows {
        ListRow(name, supporting = supporting, leadingIcon = Res.drawable.ic_file_text, category = Category.RECORDS)
        group.pending.forEach { candidate ->
            CandidateRow(candidate, hasDate = chosenDates[candidate.candidateId] != null || candidate.collectedAtKnown) {
                ctx.navigate(AppScreen.CandidateReview(candidate.candidateId))
            }
        }
    }
    PrimaryButton(vitaText(L10nKey.ENTRY_REVIEW_VALUES), { ctx.navigate(AppScreen.CandidateReview(group.pending.first().candidateId)) }, Modifier.fillMaxWidth())
    group.report?.let { report -> TertiaryButton(vitaText(L10nKey.ENTRY_EDIT_REPORT_DETAILS), { onEditReport(report) }, Modifier.fillMaxWidth()) }
}

/** One focus stop: name, value as extracted and every flag, read together. */
@Composable
private fun CandidateRow(candidate: OcrCandidate, hasDate: Boolean, onClick: () -> Unit) {
    val flags = listOfNotNull(
        vitaText(L10nKey.ENTRY_CHECK_NUMBER).takeIf { candidate.needsNumberChoice },
        vitaText(L10nKey.ENTRY_DATE_NEEDED).takeIf { !hasDate },
    )
    ListRow(
        title = candidate.displayName,
        onClick = onClick,
        supporting = (listOf(candidate.valueText()) + flags).joinToString("\n"),
        trailing = { StatusTag(vitaText(L10nKey.STATUS_NEEDS_REVIEW), if (flags.isEmpty()) StatusKind.NEEDS_REVIEW else StatusKind.WARNING) },
    )
}

@Composable
private fun RejectedSection(ctx: RouteContext, rejected: List<OcrCandidate>) {
    var open by rememberSaveable { mutableStateOf(false) }
    TertiaryButton(vitaPlural(if (open) L10nKey.ENTRY_HIDE_REJECTED else L10nKey.ENTRY_SHOW_REJECTED, rejected.size), { open = !open }, Modifier.fillMaxWidth())
    if (!open) return
    GroupedRows {
        rejected.forEach { candidate ->
            ListRow(
                candidate.displayName,
                supporting = "${candidate.valueText()}\n${vitaText(L10nKey.ENTRY_REJECTED_NOTE)}",
                trailing = {
                    TertiaryButton(vitaText(L10nKey.RESTORE_CANDIDATE), { ctx.scope.launch { ctx.controller.restoreCandidate(candidate.candidateId) } })
                },
                leadingIcon = Res.drawable.ic_undo,
            )
        }
    }
}

@Composable
private fun ReviewComplete(count: Int, onRecords: () -> Unit, onImport: () -> Unit) {
    Surface(shape = RoundedCornerShape(VezunaTokens.CardRadius), color = Category.RECORDS.fill, contentColor = Category.RECORDS.onFill) {
        Column(
            Modifier.fillMaxWidth().padding(VezunaTokens.SpaceLg).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd),
        ) {
            Image(painterResource(Res.drawable.ill_success), contentDescription = null, modifier = Modifier.widthIn(max = 220.dp).fillMaxWidth().heightIn(max = 165.dp))
            Text(vitaText(L10nKey.ENTRY_REVIEW_COMPLETE), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Text(vitaPlural(L10nKey.ENTRY_RESULTS_CONFIRMED, count), style = MaterialTheme.typography.titleMedium.tabular(), textAlign = TextAlign.Center)
        }
    }
    PrimaryButton(vitaText(L10nKey.ENTRY_VIEW_RECORDS), onRecords, Modifier.fillMaxWidth())
    SecondaryButton(vitaText(L10nKey.ENTRY_IMPORT_ANOTHER), onImport, Modifier.fillMaxWidth())
}

/** Optional detail (149): how each page was read. Raw text is behind a toggle, never in the main path. */
@Composable
private fun ProcessingDetailsSheet(details: ExtractionDetails, onDismiss: () -> Unit) {
    var showText by remember { mutableStateOf(false) }
    VezunaSheet(vitaText(L10nKey.ENTRY_DETAILS_TITLE), onDismiss) {
        ListRow(details.fileName, supporting = vitaPlural(L10nKey.ENTRY_CANDIDATES_FOUND, details.candidateCount), leadingIcon = Res.drawable.ic_file_text)
        details.pages.forEach { page ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceXs)) {
                Text(
                    vitaText(L10nKey.ENTRY_PAGE_SOURCE, page.pageNumber, vitaText(if (page.embeddedText) L10nKey.ENTRY_PAGE_EMBEDDED else L10nKey.ENTRY_PAGE_OCR)),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (showText) {
                    Text(page.text.ifBlank { vitaText(L10nKey.ENTRY_PAGE_NO_TEXT) }, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        TertiaryButton(vitaText(if (showText) L10nKey.ENTRY_HIDE_TEXT else L10nKey.ENTRY_SHOW_TEXT), { showText = !showText }, Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
            Icon(painterResource(Res.drawable.ic_info), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(vitaText(L10nKey.ENTRY_ON_DEVICE_NOTE), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Laboratory name and order date of a report, read from the page as drafts; blank clears the name. */
@Composable
private fun ReportDetailsSheet(report: LabReport, onDismiss: () -> Unit, onSave: (String, Instant?) -> Unit) {
    var name by remember(report.reportId) { mutableStateOf(report.laboratoryName.orEmpty()) }
    var date by remember(report.reportId) { mutableStateOf(report.orderedAt) }
    VezunaSheet(vitaText(L10nKey.ENTRY_EDIT_REPORT_DETAILS), onDismiss) {
        LabeledField(vitaText(L10nKey.REPORT_LABORATORY_NAME), name, { name = it })
        CollectionDateField(date, { picked, _ -> date = picked }, label = vitaText(L10nKey.ENTRY_ORDER_DATE), help = vitaText(L10nKey.ENTRY_ORDER_DATE))
        if (date != null) TertiaryButton(vitaText(L10nKey.ENTRY_CLEAR_DATE), { date = null })
        PrimaryButton(vitaText(L10nKey.SAVE), { onSave(name, date) }, Modifier.fillMaxWidth())
        TertiaryButton(vitaText(L10nKey.CANCEL), onDismiss, Modifier.fillMaxWidth())
    }
}
