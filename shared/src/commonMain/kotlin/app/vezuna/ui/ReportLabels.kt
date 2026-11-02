package app.vezuna.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.vezuna.domain.LabObservation
import app.vezuna.domain.LabReport
import app.vezuna.domain.ProfileData
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.vitaText
import kotlinx.datetime.Instant

/** What a reader calls a report: the laboratory when known, else the source file name, else "manual entry". */
internal fun reportDisplayName(report: LabReport?, data: ProfileData, manualName: String): String {
    report ?: return manualName
    report.laboratoryName?.takeIf { it.isNotBlank() }?.let { return it }
    return data.documents.firstOrNull { it.documentId == report.sourceDocumentId }?.displayName ?: manualName
}

/** The order date when the report states one, else the day it was added. */
internal fun reportDate(report: LabReport): Instant = report.orderedAt ?: report.importedAt

/** Where a result came from: laboratory (or file) and date, the document name, or "typed by hand". */
@Composable
internal fun SourceReportInfo(observation: LabObservation, data: ProfileData) {
    val report = data.reports.firstOrNull { it.reportId == observation.reportId } ?: return
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.bodySmall
    val date = formatUiDate(reportDate(report))
    if (report.isManual) {
        Text(vitaText(L10nKey.REPORT_MANUAL_SUMMARY, date), style = style, color = color)
        return
    }
    val name = reportDisplayName(report, data, "")
    Text(vitaText(L10nKey.SOURCE_REPORT, "$name ($date)"), style = style, color = color)
    data.documents.firstOrNull { it.documentId == report.sourceDocumentId }?.let {
        Text(vitaText(L10nKey.SOURCE_DOCUMENT_NAME, it.displayName), style = style, color = color)
    }
}
