package app.vezuna.ui.today

import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabReport
import app.vezuna.domain.ProfileData
import app.vezuna.ui.reportDate

/** Reports of the active profile with at least one confirmed result, newest first. Unreviewed imports never count. */
internal fun recentConfirmedReports(data: ProfileData, limit: Int = 2): List<LabReport> {
    val profileId = data.profile.profileId
    val confirmed = data.observations
        .filter { it.profileId == profileId && it.confirmation == ConfirmationStatus.CONFIRMED }
        .mapTo(HashSet()) { it.reportId }
    return data.reports
        .filter { it.profileId == profileId && it.reportId in confirmed }
        .sortedByDescending { reportDate(it) }
        .take(limit)
}

/** The import's file name when every value waiting for review comes from one import, else null. */
internal fun reviewSourceName(data: ProfileData): String? {
    val profileId = data.profile.profileId
    val documentId = data.ocrCandidates
        .filter { it.profileId == profileId && it.status == ConfirmationStatus.UNREVIEWED }
        .map { it.sourceDocumentId }
        .distinct()
        .singleOrNull() ?: return null
    return data.documents.firstOrNull { it.profileId == profileId && it.documentId == documentId }?.displayName
}

/** First use: nothing recorded yet, so Today offers the first tasks instead of empty sections or zero counts. */
internal fun isTodayEmpty(data: ProfileData): Boolean =
    data.medications.isEmpty() && data.reports.isEmpty() && data.ocrCandidates.none { it.status == ConfirmationStatus.UNREVIEWED }
