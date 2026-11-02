package app.vezuna.ui.records

import app.vezuna.ui.components.clearOfArt
import app.vezuna.ui.components.returnFocusAfter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.OcrCandidate
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.vitaPlural
import app.vezuna.localization.vitaText
import app.vezuna.ui.AppScreen
import app.vezuna.ui.Category
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.CategoryPanel
import app.vezuna.ui.components.ConfirmDialog
import app.vezuna.ui.components.EmptyState
import app.vezuna.ui.components.FocusedTaskScaffold
import app.vezuna.ui.components.GroupedRows
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.StatusKind
import app.vezuna.ui.components.StatusTag
import app.vezuna.ui.nav.RouteContext
import app.vezuna.ui.reportDisplayName
import kotlinx.coroutines.launch
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_download
import vezuna.shared.generated.resources.ic_edit
import vezuna.shared.generated.resources.ic_file_pdf
import vezuna.shared.generated.resources.ic_trash
import vezuna.shared.generated.resources.ill_file_error
import vezuna.shared.generated.resources.ill_lab_tube

/** Report overview (image 019) as a pushed screen; deleting the report returns to the list. */
@Composable
internal fun ReportDetailScreen(ctx: RouteContext, reportId: String) = FocusedTaskScaffold(title = null, onBack = ctx::back) {
    ReportDetailContent(ctx, reportId, onDeleted = ctx::back)
}

/**
 * The report's confirmed results and its drafts, kept apart, then its actions: the original file, export,
 * lab details and deletion (image 022). Also the trailing pane of the tablet layout (image 142).
 */
@Composable
internal fun ReportDetailContent(ctx: RouteContext, reportId: String, onDeleted: () -> Unit) {
    val data = ctx.state.data ?: return
    val entry = RecordsListing.entry(data, reportId) ?: return EmptyState(
        Res.drawable.ill_file_error, vitaText(L10nKey.REPORT_DETAIL_UNAVAILABLE), vitaText(L10nKey.REPORT_DETAIL_UNAVAILABLE_BODY),
        Modifier.padding(VezunaTokens.Gutter), actionLabel = vitaText(L10nKey.RESULT_DETAIL_BACK), onAction = ctx::back,
    )
    val scope = rememberCoroutineScope()
    var confirmDelete by remember(reportId) { mutableStateOf(false) }
    var editDetails by remember(reportId) { mutableStateOf(false) }
    val name = reportDisplayName(entry.report, data, vitaText(L10nKey.MANUAL_ENTRY_REPORT))

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = VezunaTokens.Gutter, vertical = VezunaTokens.SpaceMd),
        verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
    ) {
        CategoryPanel(Category.RECORDS, illustration = Res.drawable.ill_lab_tube) {
            Text(name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.clearOfArt().semantics { heading() })
            Text(formatUiDate(entry.date), style = MaterialTheme.typography.titleMedium)
            Text(data.profile.displayName, style = MaterialTheme.typography.bodyLarge)
            val origin = entry.document?.let { vitaText(L10nKey.SOURCE_DOCUMENT_NAME, it.displayName) }
                ?: vitaText(L10nKey.REPORT_MANUAL_SUMMARY, formatUiDate(entry.report.importedAt))
            Text(origin, style = MaterialTheme.typography.bodyMedium)
        }

        GroupedRows(title = vitaPlural(L10nKey.REPORT_DETAIL_CONFIRMED, entry.confirmed.size)) {
            if (entry.confirmed.isEmpty()) ListRow(vitaText(L10nKey.RECORDS_NO_CONFIRMED_YET))
            entry.confirmed.forEach { observation ->
                ResultListRow(observation, data) { ctx.navigate(AppScreen.ResultDetail(observation.observationId)) }
            }
        }

        val drafts = entry.pending + entry.rejected
        if (drafts.isNotEmpty()) {
            GroupedRows(title = vitaPlural(L10nKey.REPORT_DETAIL_DRAFTS, drafts.size)) {
                drafts.forEach { DraftRow(it) { ctx.navigate(AppScreen.CandidateReview(it.candidateId)) } }
            }
        }

        if (entry.report.sourceDocumentId != null) {
            PrimaryButton(
                vitaText(L10nKey.REPORT_DETAIL_VIEW_ORIGINAL), { ctx.navigate(AppScreen.SourceViewer(entry.report.sourceDocumentId)) },
                Modifier.fillMaxWidth(), icon = Res.drawable.ic_file_pdf,
            )
        }
        GroupedRows {
            ListRow(vitaText(L10nKey.REPORT_DETAIL_CREATE_EXPORT), leadingIcon = Res.drawable.ic_download, onClick = { ctx.navigate(AppScreen.ExportHub) })
            ListRow(vitaText(L10nKey.EDIT_REPORT_DETAILS), leadingIcon = Res.drawable.ic_edit, onClick = { editDetails = true }, modifier = Modifier.returnFocusAfter(editDetails))
            ListRow(vitaText(L10nKey.DELETE_REPORT), leadingIcon = Res.drawable.ic_trash, onClick = { confirmDelete = true }, modifier = Modifier.returnFocusAfter(confirmDelete))
        }
    }

    if (confirmDelete) {
        val impact = RecordsListing.deletionImpact(data, reportId)
        ConfirmDialog(
            title = vitaText(L10nKey.REPORT_DELETE_TITLE, name),
            body = vitaText(L10nKey.DELETE_REPORT_WARNING, name),
            confirmLabel = vitaText(L10nKey.DELETE_REPORT),
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    ctx.controller.deleteReport(reportId)
                    if (ctx.controller.state.value.data?.reports?.none { it.reportId == reportId } == true) onDeleted()
                }
            },
            onDismiss = { confirmDelete = false },
            extra = {
                Text(vitaPlural(L10nKey.REPORT_DELETE_RESULTS, impact.confirmedResults), style = MaterialTheme.typography.bodyMedium)
                if (impact.drafts > 0) Text(vitaPlural(L10nKey.REPORT_DELETE_DRAFTS, impact.drafts), style = MaterialTheme.typography.bodyMedium)
                if (impact.removesFile) Text(vitaText(L10nKey.REPORT_DELETE_FILE), style = MaterialTheme.typography.bodyMedium)
            },
        )
    }
    if (editDetails) {
        ReportDetailsDialog(
            entry.report,
            onSave = { lab, date -> editDetails = false; scope.launch { ctx.controller.updateReportDetails(reportId, lab, date) } },
            onDismiss = { editDetails = false },
        )
    }
}

/** An extracted value that is not a confirmed result: its raw text and its review state, never a range verdict. */
@Composable
private fun DraftRow(candidate: OcrCandidate, onClick: () -> Unit) {
    val rejected = candidate.status == ConfirmationStatus.REJECTED
    ListRow(
        title = candidate.displayName,
        supporting = "${candidate.rawValue} ${candidate.rawUnit}".trim(),
        onClick = onClick,
        trailing = {
            StatusTag(
                vitaText(if (rejected) L10nKey.REPORT_DETAIL_REJECTED else L10nKey.STATUS_NEEDS_REVIEW),
                if (rejected) StatusKind.INFO else StatusKind.NEEDS_REVIEW,
            )
        },
    )
}
