package app.vezuna.ui.entry

import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabObservation
import app.vezuna.domain.LabReport
import app.vezuna.domain.OcrCandidate
import app.vezuna.domain.ProfileData
import app.vezuna.domain.SourceDocument
import app.vezuna.domain.UnitNormalizer
import app.vezuna.localization.localizeDecimalText
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Drafts of one imported source: what still needs review and what was set aside. */
data class ReviewGroup(
    val document: SourceDocument?,
    val report: LabReport?,
    val pending: List<OcrCandidate>,
    val rejected: List<OcrCandidate>,
)

/** Groups unconfirmed drafts by source, newest import first. Confirmed results never appear here. */
fun ProfileData.reviewGroups(): List<ReviewGroup> =
    ocrCandidates
        .filter { it.status == ConfirmationStatus.UNREVIEWED || it.status == ConfirmationStatus.REJECTED }
        .groupBy { it.sourceDocumentId }
        .map { (documentId, candidates) ->
            ReviewGroup(
                document = documents.firstOrNull { it.documentId == documentId },
                report = reports.firstOrNull { it.sourceDocumentId == documentId },
                pending = candidates.filter { it.status == ConfirmationStatus.UNREVIEWED },
                rejected = candidates.filter { it.status == ConfirmationStatus.REJECTED },
            )
        }
        .sortedByDescending { it.document?.importedAt }

/** The draft to review after [candidateId]: the next one from the same source, else any other, else none. */
fun ProfileData.nextPending(candidateId: String): OcrCandidate? {
    val pending = ocrCandidates.filter { it.status == ConfirmationStatus.UNREVIEWED && it.candidateId != candidateId }
    val documentId = ocrCandidates.firstOrNull { it.candidateId == candidateId }?.sourceDocumentId
    return pending.firstOrNull { it.sourceDocumentId == documentId } ?: pending.firstOrNull()
}

/** Confirmed results of the same test on the same calendar day: where a duplicate draft would collide. */
fun ProfileData.sameDayResults(testCode: String, date: Instant): List<LabObservation> {
    val code = UnitNormalizer.canonicalTestCode(testCode)
    val day = date.toLocalDateTime(TimeZone.UTC).date
    return observations.filter {
        it.confirmation == ConfirmationStatus.CONFIRMED &&
            UnitNormalizer.canonicalTestCode(it.testCode) == code &&
            it.collectedAt.toLocalDateTime(TimeZone.UTC).date == day
    }
}

/** The extracted value as read: text as written, a number in the reader's decimal style with its original unit. */
fun OcrCandidate.valueText(): String =
    if (looksQualitative) rawValue else "${localizeDecimalText(rawValue)} $rawUnit".trim()

/** True when the printed number has two readings and the person must choose one (image 042). */
val OcrCandidate.needsNumberChoice: Boolean get() = rawToken != null && !looksQualitative
