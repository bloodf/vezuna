package app.vezuna.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vezuna.domain.LabReport
import app.vezuna.domain.ProfileData
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.vitaText
import kotlinx.coroutines.launch

/** Every report of the profile, imported or manual, each deletable after a confirmation. */
@Composable
internal fun ReportList(data: ProfileData, controller: AppController) {
    if (data.reports.isEmpty()) return
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<LabReport?>(null) }
    val manualName = vitaText(L10nKey.MANUAL_ENTRY_REPORT)
    fun nameOf(report: LabReport): String =
        data.documents.firstOrNull { it.documentId == report.sourceDocumentId }?.displayName ?: manualName
    SectionHeading(vitaText(L10nKey.IMPORTED_REPORTS), vitaText(L10nKey.IMPORTED_REPORTS_SUBTITLE))
    data.reports.sortedByDescending { it.importedAt }.forEach { report ->
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(nameOf(report), style = MaterialTheme.typography.titleMedium)
                    Text(vitaText(if (report.isManual) L10nKey.REPORT_MANUAL_SUMMARY else L10nKey.REPORT_SUMMARY, formatUiDate(report.importedAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton({ pendingDelete = report }, Modifier.defaultMinSize(minHeight = 48.dp)) {
                    androidx.compose.material3.Icon(Icons.Rounded.Delete, null)
                    Text(vitaText(L10nKey.DELETE_REPORT))
                }
            }
        }
    }
    pendingDelete?.let { report ->
        ConfirmDialog(
            title = vitaText(L10nKey.DELETE_REPORT),
            message = vitaText(L10nKey.DELETE_REPORT_WARNING, nameOf(report)),
            confirmLabel = vitaText(L10nKey.DELETE),
            onConfirm = { pendingDelete = null; scope.launch { controller.deleteReport(report.reportId) } },
            onDismiss = { pendingDelete = null },
        )
    }
}
