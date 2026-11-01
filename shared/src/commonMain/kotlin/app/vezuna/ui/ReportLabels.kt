package app.vezuna.ui

import app.vezuna.domain.LabReport
import app.vezuna.domain.ProfileData
import kotlinx.datetime.Instant

/** What a reader calls a report: the laboratory when known, else the source file name, else "manual entry". */
internal fun reportDisplayName(report: LabReport?, data: ProfileData, manualName: String): String {
    report ?: return manualName
    report.laboratoryName?.takeIf { it.isNotBlank() }?.let { return it }
    return data.documents.firstOrNull { it.documentId == report.sourceDocumentId }?.displayName ?: manualName
}

/** The order date when the report states one, else the day it was added. */
internal fun reportDate(report: LabReport): Instant = report.orderedAt ?: report.importedAt
