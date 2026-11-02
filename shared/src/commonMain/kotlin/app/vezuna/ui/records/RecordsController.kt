package app.vezuna.ui.records

import app.vezuna.domain.ClinicalDuplicateDetector
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabObservation
import app.vezuna.domain.LabReport
import app.vezuna.domain.ObservationRevision
import app.vezuna.domain.OcrRevision
import app.vezuna.domain.ReferenceRange
import app.vezuna.localization.L10nKey
import app.vezuna.localization.failMessage
import app.vezuna.localization.formatLabValue
import app.vezuna.localization.localizeDecimalText
import app.vezuna.localization.message
import app.vezuna.platform.currentInstant
import app.vezuna.platform.randomId
import app.vezuna.security.backupFileId
import app.vezuna.ui.AppScreen
import app.vezuna.ui.ControllerCore
import app.vezuna.ui.SourceFileRead
import app.vezuna.ui.nav.Navigator
import app.vezuna.ui.resolveResultValue
import app.vezuna.ui.reviewedRange
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Review, manual entry, editing and deletion of lab results and reports, plus reading original files. */
internal class RecordsController(private val core: ControllerCore, private val navigator: Navigator) {
    private val repository get() = core.repository
    private val sourceStore get() = core.sourceStore

    suspend fun confirmCandidate(
        candidateId: String,
        correctedValue: String,
        correctedUnit: String,
        referenceLower: String,
        referenceUpper: String,
        collectedAt: kotlin.time.Instant? = null,
        testCode: String? = null,
        displayName: String? = null,
        asText: Boolean? = null,
        /** The reviewer's reference text; null keeps the candidate's own text. Blank clears it. */
        referenceText: String? = null,
    ) = core.operation {
        val data = core.requireData()
        val candidate = data.ocrCandidates.firstOrNull { it.candidateId == candidateId }
            ?: failMessage(L10nKey.CANDIDATE_UNAVAILABLE)
        if (candidate.status != ConfirmationStatus.UNREVIEWED) failMessage(L10nKey.CANDIDATE_ALREADY_REVIEWED)
        val date = collectedAt ?: candidate.collectedAt.takeIf { candidate.collectedAtKnown }
            ?: failMessage(L10nKey.COLLECTION_DATE_REQUIRED)
        val code = (testCode ?: candidate.testCode).trim()
        val name = (displayName ?: candidate.displayName).trim()
        if (code.isBlank() || name.isBlank()) failMessage(L10nKey.CANDIDATE_REQUIRED_FIELDS)
        val result = resolveResultValue(asText ?: candidate.looksQualitative, correctedValue, correctedUnit, listOf(candidate.rawValue.trim(), localizeDecimalText(candidate.rawValue)))
        val qualitativeValue = result.qualitativeValue
        val value = result.value
        val unit = result.unit
        val normalized = result.normalized
        val range = reviewedRange(candidate.referenceRange, referenceText, referenceLower, referenceUpper, unit, qualitativeValue != null)
        val revisions = candidate.revisions + OcrRevision(
            data.profile.profileId,
            randomId(),
            currentInstant(),
            "${candidate.rawValue} ${candidate.rawUnit}".trim(),
            "$correctedValue $unit".trim(),
        )
        val report = data.reports.first { it.sourceDocumentId == candidate.sourceDocumentId }
        val inserted = repository.addObservation(
            LabObservation(
                profileId = data.profile.profileId,
                observationId = randomId(),
                reportId = report.reportId,
                testCode = code,
                displayName = name,
                originalValue = value,
                originalUnit = unit,
                normalizedValue = normalized.value,
                normalizedUnit = normalized.unit,
                referenceRange = range,
                collectedAt = date,
                confirmation = ConfirmationStatus.CONFIRMED,
                qualitativeValue = qualitativeValue,
                valueDecimals = result.decimals,
            ),
        )
        if (!inserted) failMessage(L10nKey.DUPLICATE_CLINICAL_RESULT)
        repository.updateCandidate(candidate.copy(testCode = code, displayName = name, collectedAt = date, collectedAtKnown = true, status = ConfirmationStatus.CONFIRMED, revisions = revisions))
        core.loadProfile(data.profile.profileId)
        core.mutableState.value = core.mutableState.value.copy(notice = message(L10nKey.CONFIRMED_RESULT_SAVED))
    }

    /**
     * A result typed in from a paper report: no source file, a new "manual" report, and a confirmed result
     * (the reviewer is the author). The collection date is required.
     */
    suspend fun addManualResult(
        testCode: String,
        displayName: String,
        rawValue: String,
        rawUnit: String,
        referenceLower: String,
        referenceUpper: String,
        referenceText: String?,
        collectedAt: kotlin.time.Instant?,
        asText: Boolean = false,
    ) = core.operation {
        val data = core.requireData()
        val date = collectedAt ?: failMessage(L10nKey.COLLECTION_DATE_REQUIRED)
        val code = testCode.trim()
        val name = displayName.trim()
        if (code.isBlank() || name.isBlank()) failMessage(L10nKey.CANDIDATE_REQUIRED_FIELDS)
        val result = resolveResultValue(asText, rawValue, rawUnit)
        val range = reviewedRange(ReferenceRange(), referenceText, referenceLower, referenceUpper, result.unit, result.qualitativeValue != null)
        val report = LabReport(data.profile.profileId, randomId(), importedAt = currentInstant())
        val observation = LabObservation(
            profileId = data.profile.profileId,
            observationId = randomId(),
            reportId = report.reportId,
            testCode = code,
            displayName = name,
            originalValue = result.value,
            originalUnit = result.unit,
            normalizedValue = result.normalized.value,
            normalizedUnit = result.normalized.unit,
            referenceRange = range,
            collectedAt = date,
            confirmation = ConfirmationStatus.CONFIRMED,
            qualitativeValue = result.qualitativeValue,
            valueDecimals = result.decimals,
        )
        if (data.observations.any { ClinicalDuplicateDetector.areDuplicates(it, observation) }) failMessage(L10nKey.DUPLICATE_CLINICAL_RESULT)
        repository.addReport(report)
        try {
            if (!repository.addObservation(observation)) failMessage(L10nKey.DUPLICATE_CLINICAL_RESULT)
        } catch (error: Throwable) {
            // No empty manual report may outlive a failed save, even when the save was cancelled.
            withContext(NonCancellable) { repository.deleteReport(data.profile.profileId, report.reportId) }
            throw error
        }
        core.loadProfile(data.profile.profileId)
        navigator.finishAndGo(AppScreen.Labs)
        core.mutableState.value = core.mutableState.value.copy(notice = message(L10nKey.MANUAL_RESULT_SAVED))
    }


    /**
     * Edits a confirmed result (value or text, unit, reference range, date). The previous state is appended to the
     * result's revisions, and duplicate detection runs again against every other confirmed result.
     */
    suspend fun editObservation(
        observationId: String,
        correctedValue: String,
        correctedUnit: String,
        referenceLower: String,
        referenceUpper: String,
        referenceText: String?,
        collectedAt: kotlin.time.Instant?,
    ) = core.operation {
        val data = core.requireData()
        val current = data.observations.firstOrNull { it.observationId == observationId && it.confirmation == ConfirmationStatus.CONFIRMED }
            ?: failMessage(L10nKey.RESULT_UNAVAILABLE)
        val date = collectedAt ?: failMessage(L10nKey.COLLECTION_DATE_REQUIRED)
        val result = resolveResultValue(current.isQualitative, correctedValue, correctedUnit, listOf(formatLabValue(current.originalValue, current.valueDecimals)))
        val range = reviewedRange(current.referenceRange, referenceText, referenceLower, referenceUpper, result.unit, current.isQualitative)
        val edited = current.copy(
            originalValue = result.value,
            originalUnit = result.unit,
            normalizedValue = result.normalized.value,
            normalizedUnit = result.normalized.unit,
            referenceRange = range,
            collectedAt = date,
            qualitativeValue = result.qualitativeValue,
            // Older results carry no precision: re-saving the same value must not count as an edit.
            valueDecimals = if (result.value == current.originalValue && current.valueDecimals == null) null else result.decimals,
        )
        if (edited == current) {
            core.mutableState.value = core.mutableState.value.copy(notice = message(L10nKey.RESULT_UNCHANGED))
            return@operation
        }
        if (data.observations.any { it.observationId != current.observationId && ClinicalDuplicateDetector.areDuplicates(it, edited) }) {
            failMessage(L10nKey.DUPLICATE_CLINICAL_RESULT)
        }
        val revision = ObservationRevision(
            revisionId = randomId(),
            revisedAt = currentInstant(),
            previousValue = current.originalValue,
            previousUnit = current.originalUnit,
            previousValueDecimals = current.valueDecimals,
            previousReferenceRange = current.referenceRange,
            previousCollectedAt = current.collectedAt,
            previousQualitativeValue = current.qualitativeValue,
        )
        repository.updateObservation(edited.copy(revisions = current.revisions + revision))
        core.loadProfile(data.profile.profileId)
        core.mutableState.value = core.mutableState.value.copy(notice = message(L10nKey.RESULT_UPDATED))
    }

    /** Removes the report with its source document, stored file, draft candidates and confirmed results (repository cascade). */
    suspend fun deleteReport(reportId: String) = core.operation {
        val data = core.requireData()
        if (data.reports.none { it.reportId == reportId }) failMessage(L10nKey.REPORT_NOT_FOUND)
        repository.deleteReport(data.profile.profileId, reportId)
        core.loadProfile(data.profile.profileId)
        core.mutableState.value = core.mutableState.value.copy(notice = message(L10nKey.REPORT_DELETED))
    }

    suspend fun rejectCandidate(candidateId: String) = core.operation {
        val data = core.requireData()
        val candidate = data.ocrCandidates.firstOrNull { it.candidateId == candidateId }
            ?: failMessage(L10nKey.CANDIDATE_UNAVAILABLE)
        repository.updateCandidate(candidate.copy(status = ConfirmationStatus.REJECTED))
        core.loadProfile(data.profile.profileId)
    }

    /** The reviewer's laboratory name and order date for a report; blank clears the name, null clears the date. */
    suspend fun updateReportDetails(reportId: String, laboratoryName: String, orderedAt: kotlin.time.Instant?) = core.operation {
        val data = core.requireData()
        val report = data.reports.firstOrNull { it.reportId == reportId } ?: failMessage(L10nKey.REPORT_NOT_FOUND)
        repository.updateReport(report.copy(laboratoryName = laboratoryName.trim().takeIf { it.isNotEmpty() }, orderedAt = orderedAt))
        core.loadProfile(data.profile.profileId)
    }

    /** Returns a rejected candidate to review. Only a rejected candidate can be restored; a confirmed one never. */
    suspend fun restoreCandidate(candidateId: String) = core.operation {
        val data = core.requireData()
        val candidate = data.ocrCandidates.firstOrNull { it.candidateId == candidateId }
            ?: failMessage(L10nKey.CANDIDATE_UNAVAILABLE)
        if (candidate.status != ConfirmationStatus.REJECTED) failMessage(L10nKey.CANDIDATE_ALREADY_REVIEWED)
        repository.updateCandidate(candidate.copy(status = ConfirmationStatus.UNREVIEWED))
        core.loadProfile(data.profile.profileId)
    }
    fun toggleObservation(observationId: String) {
        val data = core.mutableState.value.data ?: return
        require(data.observations.any { it.observationId == observationId && it.confirmation == ConfirmationStatus.CONFIRMED })
        val selected = core.mutableState.value.selectedObservationIds
        core.mutableState.value = core.mutableState.value.copy(
            selectedObservationIds = if (observationId in selected) selected - observationId else selected + observationId,
        )
    }

    /** Decrypts every stored page of the document's original file. Never throws; a failure is a [SourceFileRead]. */
    suspend fun readSourceFile(documentId: String): SourceFileRead {
        val document = core.mutableState.value.data?.documents?.firstOrNull { it.documentId == documentId } ?: return SourceFileRead.Missing
        val keys = listOfNotNull(document.vaultKey) + document.pageVaultKeys
        val store = sourceStore
        if (keys.isEmpty() || store == null) return SourceFileRead.Missing
        return try {
            SourceFileRead.Available(document.mediaType, keys.mapIndexed { index, key -> store.load(document.profileId, backupFileId(documentId, index + 1), key) ?: return SourceFileRead.Missing })
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            SourceFileRead.Unreadable
        }
    }
}
