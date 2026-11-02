package app.vezuna.ui

import app.vezuna.data.VezunaRepository
import app.vezuna.domain.ChatMessage
import app.vezuna.domain.ClinicalDuplicateDetector
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.DashboardDose
import app.vezuna.domain.DashboardSnapshot
import app.vezuna.domain.DashboardSnapshotFactory
import app.vezuna.domain.DoseEvent
import app.vezuna.domain.DoseEvents
import app.vezuna.domain.DoseEventStatus
import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.LabObservation
import app.vezuna.domain.LabReport
import app.vezuna.domain.Medication
import app.vezuna.domain.ObservationRevision
import app.vezuna.domain.OcrCandidate
import app.vezuna.domain.OcrRevision
import app.vezuna.domain.Profile
import app.vezuna.domain.DefaultProfileName
import app.vezuna.domain.ProfileAge
import app.vezuna.domain.ProfileData
import app.vezuna.domain.Recurrence
import app.vezuna.domain.ReferenceRange
import app.vezuna.domain.SourceDocument
import app.vezuna.domain.UnitNormalizer
import app.vezuna.export.CsvDoctorExporter
import app.vezuna.export.DoctorExportContent
import app.vezuna.export.DoctorPdfDocument
import app.vezuna.export.ExportedPdf
import app.vezuna.export.ExportOptions
import app.vezuna.export.ExportSectionText
import app.vezuna.export.NativePdfExporter
import app.vezuna.llm.InstalledModelManager
import app.vezuna.llm.LocalHealthChat
import app.vezuna.llm.ModelPack
import app.vezuna.localization.ContentLanguage
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.localization.failMessage
import app.vezuna.localization.formatLabValue
import app.vezuna.localization.localizeDecimalText
import app.vezuna.localization.message
import app.vezuna.localization.pluralMessage
import app.vezuna.localization.toUserText
import app.vezuna.platform.currentInstant
import app.vezuna.platform.randomId
import app.vezuna.platform.sha256
import app.vezuna.platform.toHex
import app.vezuna.data.restoreMembers
import app.vezuna.security.BackupMember
import app.vezuna.security.BackupService
import app.vezuna.security.backupFileId
import app.vezuna.ui.security.RestoreResult
import app.vezuna.ui.nav.Destination
import app.vezuna.ui.nav.Navigator
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class AppController(
    private val repository: VezunaRepository,
    val modelManager: InstalledModelManager,
    private val chat: LocalHealthChat,
    private val pdfExporter: NativePdfExporter = NativePdfExporter(),
    private val clock: () -> kotlin.time.Instant = { currentInstant() },
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    /** Null when the platform offers no backup file access (previews, tests). */
    val backup: BackupService? = null,
    private val sourceStore: SourceFileStore? = null,
) {
    private val mutableState = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    /** Back stacks for the five root destinations; the shell renders [Navigator.state]. */
    val navigator = Navigator()

    suspend fun initialize() = operation {
        // A profile only exists once the first run began, so an existing profile means onboarding is done (or not needed).
        val freshInstall = repository.profiles.value.isEmpty()
        if (freshInstall) {
            repository.createProfile(Profile(randomId(), DefaultProfileName.of(ContentLanguage.device()), createdAt = currentInstant()))
        }
        mutableState.value = mutableState.value.copy(profiles = repository.profiles.value, onboarding = freshInstall || mutableState.value.onboarding)
        val profileId = mutableState.value.data?.profile?.profileId ?: repository.profiles.value.first().profileId
        loadProfile(profileId)
        // A re-init mid-first-run keeps onboarding (and the typed name) unless real records or a second profile exist.
        val loaded = mutableState.value.data
        if (mutableState.value.onboarding && (repository.profiles.value.size > 1 || loaded == null || loaded.documents.isNotEmpty() || loaded.observations.isNotEmpty() || loaded.medications.isNotEmpty())) {
            mutableState.value = mutableState.value.copy(onboarding = false)
        }
        modelManager.refresh()
    }

    suspend fun refreshDashboard() {
        val profileId = mutableState.value.data?.profile?.profileId ?: return
        loadProfile(profileId)
    }

    fun announce(notice: UserText) {
        mutableState.value = mutableState.value.copy(notice = notice, error = null)
    }

    fun navigate(screen: AppScreen) {
        navigator.navigate(screen)
        clearMessages()
    }

    fun selectRoot(destination: Destination) {
        navigator.selectRoot(destination)
        clearMessages()
    }

    /** False when there is nothing to go back to inside the app. */
    fun back(): Boolean = navigator.back().also { if (it) clearMessages() }

    suspend fun switchProfile(profileId: String) = operation {
        if (repository.profiles.value.none { it.profileId == profileId }) failMessage(L10nKey.SELECT_PROFILE_FIRST)
        onProfileChanged()
        loadProfile(profileId)
    }

    /**
     * Clears everything bound to the previous profile before another one loads: its data, the chat record
     * selection, prepared exports and every pushed route (routes carry that profile's IDs). The shell also
     * keys saved UI state (filters, drafts, scroll) by profile, so those are dropped with it.
     * Lanes that add profile-bound state clear it here.
     */
    private fun onProfileChanged() {
        mutableState.value = mutableState.value.copy(data = null, dashboard = null, selectedObservationIds = emptySet(), csvExport = null, pdfExport = null)
        importSession.clear()
        mutableRestoreResult.value = null // L6
        navigator.resetToRoots()
    }

    suspend fun createProfile(displayName: String) = operation {
        if (displayName.isBlank()) failMessage(L10nKey.PROFILE_NAME_REQUIRED)
        val profile = Profile(randomId(), displayName.trim(), createdAt = currentInstant())
        repository.createProfile(profile)
        mutableState.value = mutableState.value.copy(profiles = repository.profiles.value)
        switchProfile(profile.profileId)
    }

    /** Sets or clears (null) the birth date. Rejects future dates and ages above [ProfileAge.MAX_YEARS]. */
    suspend fun setBirthDate(profileId: String, birthDate: LocalDate?) = operation {
        val profile = repository.profiles.value.firstOrNull { it.profileId == profileId } ?: failMessage(L10nKey.SELECT_PROFILE_FIRST)
        if (birthDate != null && !ProfileAge.isValid(birthDate, ProfileAge.today(clock(), timeZone()))) failMessage(L10nKey.BIRTH_DATE_INVALID)
        repository.updateProfile(profile.copy(birthDate = birthDate))
        mutableState.value = mutableState.value.copy(profiles = repository.profiles.value)
        reloadCurrentProfile()
    }

    /** Ends first-run onboarding, applying the chosen name and birth date when given. A blank name keeps the default. */
    suspend fun completeOnboarding(displayName: String, birthDate: LocalDate?) {
        val profile = repository.profiles.value.firstOrNull() ?: return skipOnboarding()
        clearMessages()
        if (displayName.isNotBlank() && displayName.trim() != profile.displayName) renameProfile(profile.profileId, displayName)
        if (birthDate != null) setBirthDate(profile.profileId, birthDate)
        if (mutableState.value.error == null) skipOnboarding()
    }

    fun skipOnboarding() {
        mutableState.value = mutableState.value.copy(onboarding = false)
    }

    suspend fun renameProfile(profileId: String, displayName: String) = operation {
        if (displayName.isBlank()) failMessage(L10nKey.PROFILE_NAME_REQUIRED)
        val profile = repository.profiles.value.firstOrNull { it.profileId == profileId } ?: failMessage(L10nKey.SELECT_PROFILE_FIRST)
        repository.updateProfile(profile.copy(displayName = displayName.trim()))
        reloadCurrentProfile()
    }

    /** The caller must type the exact profile name; the last remaining profile can never be deleted. */
    suspend fun deleteProfile(profileId: String, typedName: String) = operation {
        val profiles = repository.profiles.value
        val profile = profiles.firstOrNull { it.profileId == profileId } ?: failMessage(L10nKey.SELECT_PROFILE_FIRST)
        if (profiles.size <= 1) failMessage(L10nKey.LAST_PROFILE_CANNOT_BE_DELETED)
        if (typedName.trim() != profile.displayName.trim()) failMessage(L10nKey.PROFILE_NAME_MISMATCH)
        repository.deleteProfile(profileId)
        val remaining = repository.profiles.value
        val currentId = mutableState.value.data?.profile?.profileId
        if (currentId == null || currentId == profileId) {
            onProfileChanged()
            loadProfile(remaining.first().profileId)
        } else {
            loadProfile(currentId)
        }
    }

    suspend fun setLinkedHealthProfile(profileId: String) = operation {
        repository.setLinkedHealthProfile(profileId)
        reloadCurrentProfile()
    }

    private suspend fun reloadCurrentProfile() {
        val profileId = mutableState.value.data?.profile?.profileId ?: return
        loadProfile(profileId)
    }

    suspend fun importSource(source: PickedSource) = operation {
        if (source.candidates.isEmpty()) failMessage(L10nKey.NO_CLINICAL_CANDIDATES_EXTRACTED)
        val inserted = importExtracted(source, source.candidates)
        navigator.navigate(AppScreen.ImportReview)
        mutableState.value = mutableState.value.copy(
            notice = if (inserted == 0) message(L10nKey.NO_NEW_CANDIDATES)
            else pluralMessage(L10nKey.UNREVIEWED_CANDIDATES_ADDED, inserted),
        )
    }

    private suspend fun importExtracted(source: PickedSource, candidates: List<ExtractedCandidate>): Int {
        val data = requireData()
        if (source.fileName.isBlank() || source.bytes.isEmpty()) failMessage(L10nKey.READABLE_SOURCE_REQUIRED)
        if (candidates.isEmpty()) failMessage(L10nKey.REVIEW_CANDIDATE_REQUIRED)
        candidates.forEach { candidate ->
            if (candidate.testCode.isBlank() || candidate.displayName.isBlank() || candidate.rawValue.isBlank()) {
                failMessage(L10nKey.CANDIDATE_REQUIRED_FIELDS)
            }
            if (candidate.valueKind != ExtractedValueKind.QUALITATIVE && candidate.rawUnit.isBlank()) {
                failMessage(L10nKey.NUMERIC_CANDIDATE_UNIT_REQUIRED)
            }
        }
        val sourceHash = sourceIdentity(source)
        val existingDocument = data.documents.firstOrNull { it.sha256 == sourceHash }
        val document = existingDocument ?: newSourceDocument(data.profile.profileId, source, sourceHash)
        if (existingDocument == null) addDocumentKeepingFile(document) else reattachMissingSourceFile(existingDocument, source)
        if (data.reports.none { it.sourceDocumentId == document.documentId }) {
            repository.addReport(
                LabReport(
                    data.profile.profileId, randomId(), document.documentId,
                    laboratoryName = source.laboratoryName?.trim()?.takeIf { it.isNotEmpty() },
                    orderedAt = source.orderedAt,
                    importedAt = currentInstant(),
                ),
            )
        }
        val existingFingerprints = data.ocrCandidates.filter { it.sourceDocumentId == document.documentId }
            .mapTo(mutableSetOf()) { candidateFingerprint(it.testCode, it.displayName, it.rawValue, it.rawUnit, it.referenceRange, it.collectedAt) }
        var inserted = 0
        candidates.forEach { candidate ->
            val collectedAt = candidate.collectedAt ?: document.importedAt
            val fingerprint = candidateFingerprint(
                candidate.testCode,
                candidate.displayName,
                candidate.rawValue,
                candidate.rawUnit,
                candidate.referenceRange,
                collectedAt,
            )
            if (existingFingerprints.add(fingerprint)) {
                repository.addCandidate(
                    OcrCandidate(
                        profileId = data.profile.profileId,
                        candidateId = randomId(),
                        sourceDocumentId = document.documentId,
                        testCode = candidate.testCode.trim(),
                        displayName = candidate.displayName.trim(),
                        rawValue = candidate.rawValue.trim(),
                        rawUnit = candidate.rawUnit.trim(),
                        referenceRange = candidate.referenceRange,
                        collectedAt = collectedAt,
                        confidence = candidate.confidence,
                        status = ConfirmationStatus.UNREVIEWED,
                        qualitative = candidate.valueKind == ExtractedValueKind.QUALITATIVE,
                        rawToken = candidate.rawToken,
                        collectedAtKnown = candidate.collectedAt != null,
                    ),
                )
                inserted++
            }
        }
        loadProfile(data.profile.profileId)
        return inserted
    }

    private suspend fun newSourceDocument(profileId: String, source: PickedSource, sourceHash: String): SourceDocument {
        val documentId = randomId()
        val store = sourceStore
        val saved = mutableListOf<String>()
        try {
            store?.let { saved += it.save(profileId, documentId, source.bytes) }
            store?.let { s -> source.additionalPages.forEachIndexed { index, page -> saved += s.save(profileId, "$documentId-page-${index + 2}", page) } }
        } catch (error: Throwable) {
            withContext(NonCancellable) { saved.forEach { store?.discard(it) } }
            throw error
        }
        return SourceDocument(
            profileId, documentId, source.fileName.trim(), source.mediaType, sourceHash, currentInstant(),
            saved.firstOrNull(), saved.drop(1),
        )
    }

    /** A stored file must never outlive a document that was not recorded. */
    private suspend fun addDocumentKeepingFile(document: SourceDocument) {
        try {
            repository.addDocument(document)
        } catch (error: Throwable) {
            withContext(NonCancellable) { (listOfNotNull(document.vaultKey) + document.pageVaultKeys).forEach { sourceStore?.discard(it) } }
            throw error
        }
    }


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
    ) = operation {
        val data = requireData()
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
        loadProfile(data.profile.profileId)
        mutableState.value = mutableState.value.copy(notice = message(L10nKey.CONFIRMED_RESULT_SAVED))
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
    ) = operation {
        val data = requireData()
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
        loadProfile(data.profile.profileId)
        navigator.finishAndGo(AppScreen.Labs)
        mutableState.value = mutableState.value.copy(notice = message(L10nKey.MANUAL_RESULT_SAVED))
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
    ) = operation {
        val data = requireData()
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
            mutableState.value = mutableState.value.copy(notice = message(L10nKey.RESULT_UNCHANGED))
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
        loadProfile(data.profile.profileId)
        mutableState.value = mutableState.value.copy(notice = message(L10nKey.RESULT_UPDATED))
    }

    /** Removes the report with its source document, stored file, draft candidates and confirmed results (repository cascade). */
    suspend fun deleteReport(reportId: String) = operation {
        val data = requireData()
        if (data.reports.none { it.reportId == reportId }) failMessage(L10nKey.REPORT_NOT_FOUND)
        repository.deleteReport(data.profile.profileId, reportId)
        loadProfile(data.profile.profileId)
        mutableState.value = mutableState.value.copy(notice = message(L10nKey.REPORT_DELETED))
    }

    suspend fun rejectCandidate(candidateId: String) = operation {
        val data = requireData()
        val candidate = data.ocrCandidates.firstOrNull { it.candidateId == candidateId }
            ?: failMessage(L10nKey.CANDIDATE_UNAVAILABLE)
        repository.updateCandidate(candidate.copy(status = ConfirmationStatus.REJECTED))
        loadProfile(data.profile.profileId)
    }

    /** The reviewer's laboratory name and order date for a report; blank clears the name, null clears the date. */
    suspend fun updateReportDetails(reportId: String, laboratoryName: String, orderedAt: kotlin.time.Instant?) = operation {
        val data = requireData()
        val report = data.reports.firstOrNull { it.reportId == reportId } ?: failMessage(L10nKey.REPORT_NOT_FOUND)
        repository.updateReport(report.copy(laboratoryName = laboratoryName.trim().takeIf { it.isNotEmpty() }, orderedAt = orderedAt))
        loadProfile(data.profile.profileId)
    }

    /** Returns a rejected candidate to review. Only a rejected candidate can be restored; a confirmed one never. */
    suspend fun restoreCandidate(candidateId: String) = operation {
        val data = requireData()
        val candidate = data.ocrCandidates.firstOrNull { it.candidateId == candidateId }
            ?: failMessage(L10nKey.CANDIDATE_UNAVAILABLE)
        if (candidate.status != ConfirmationStatus.REJECTED) failMessage(L10nKey.CANDIDATE_ALREADY_REVIEWED)
        repository.updateCandidate(candidate.copy(status = ConfirmationStatus.UNREVIEWED))
        loadProfile(data.profile.profileId)
    }

    suspend fun addMedication(name: String, strength: String, instructions: String, time: String) =
        addMedication(name, strength, instructions, listOf(ScheduleDraft(times = listOf(time), startDate = clock().toLocalDateTime(timeZone()).date)))

    suspend fun addMedication(name: String, strength: String, instructions: String, drafts: List<ScheduleDraft>) = operation {
        val data = requireData()
        if (name.isBlank()) failMessage(L10nKey.MEDICATION_NAME_REQUIRED)
        val medicationId = randomId()
        val schedules = buildSchedules(data.profile.profileId, medicationId, drafts)
        repository.addMedication(Medication(data.profile.profileId, medicationId, name.trim(), strength.trim().ifBlank { null }, instructions.trim().ifBlank { null }, schedules))
        loadProfile(data.profile.profileId)
    }

    private fun buildSchedules(profileId: String, medicationId: String, drafts: List<ScheduleDraft>, previous: List<DoseSchedule> = emptyList()): List<DoseSchedule> {
        if (drafts.isEmpty()) failMessage(L10nKey.SCHEDULE_TIMES_REQUIRED)
        drafts.firstNotNullOfOrNull { it.errors().firstOrNull() }?.let { failMessage(it) }
        val now = clock()
        return drafts.map { draft ->
            val schedule = draft.toSchedule(profileId, medicationId, ::randomId)
            val old = previous.firstOrNull { it.scheduleId == schedule.scheduleId }
            // Doses count from the moment the schedule was created or its times/recurrence last changed.
            val unchanged = old != null && old.copy(trackedSince = null) == schedule
            schedule.copy(trackedSince = if (unchanged) old?.trackedSince else now)
        }
    }

    suspend fun updateMedication(medicationId: String, name: String, strength: String, instructions: String, drafts: List<ScheduleDraft>) = operation {
        val data = requireData()
        val medication = findMedication(data, medicationId)
        if (name.isBlank()) failMessage(L10nKey.MEDICATION_NAME_REQUIRED)
        val schedules = buildSchedules(data.profile.profileId, medicationId, drafts, medication.schedules)
        repository.updateMedication(
            medication.copy(
                displayName = name.trim(),
                strength = strength.trim().ifBlank { null },
                instructions = instructions.trim().ifBlank { null },
                schedules = schedules,
            ),
        )
        loadProfile(data.profile.profileId)
    }

    suspend fun updateMedication(medicationId: String, name: String, strength: String, instructions: String, time: String) = operation {
        val data = requireData()
        val medication = findMedication(data, medicationId)
        if (name.isBlank()) failMessage(L10nKey.MEDICATION_NAME_REQUIRED)
        val canonicalTime = time.trim()
        if (!REMINDER_TIME.matches(canonicalTime)) failMessage(L10nKey.INVALID_REMINDER_TIME)
        // The editor exposes one reminder time; it replaces the first time of the first schedule.
        val schedules = medication.schedules.mapIndexed { index, schedule ->
            if (index == 0 && schedule.timesLocal.firstOrNull() != canonicalTime) {
                schedule.copy(timesLocal = listOf(canonicalTime) + schedule.timesLocal.drop(1), trackedSince = clock())
            } else {
                schedule
            }
        }
        repository.updateMedication(
            medication.copy(
                displayName = name.trim(),
                strength = strength.trim().ifBlank { null },
                instructions = instructions.trim().ifBlank { null },
                schedules = schedules,
            ),
        )
        loadProfile(data.profile.profileId)
    }

    /** Pausing keeps history; resuming also clears an end date that has already passed (a stopped medication). */
    suspend fun setMedicationActive(medicationId: String, active: Boolean) = operation {
        val data = requireData()
        val medication = findMedication(data, medicationId)
        val today = clock().toLocalDateTime(timeZone()).date
        val schedules = if (active) {
            // Paused or stopped days are not due: doses count again only from the moment of resuming.
            medication.schedules.map { (if (it.endDate != null && it.endDate <= today) it.copy(endDate = null) else it).copy(trackedSince = clock()) }
        } else {
            medication.schedules
        }
        repository.updateMedication(medication.copy(active = active, schedules = schedules))
        loadProfile(data.profile.profileId)
    }

    /** Stopping ends the schedules today (never before they start) and deactivates reminders immediately. */
    suspend fun stopMedication(medicationId: String) = operation {
        val data = requireData()
        val medication = findMedication(data, medicationId)
        val today = clock().toLocalDateTime(timeZone()).date
        val schedules = medication.schedules.map { it.copy(endDate = maxOf(today, it.startDate)) }
        repository.updateMedication(medication.copy(active = false, schedules = schedules))
        loadProfile(data.profile.profileId)
    }

    suspend fun deleteMedication(medicationId: String) = operation {
        val data = requireData()
        findMedication(data, medicationId)
        repository.deleteMedication(data.profile.profileId, medicationId)
        loadProfile(data.profile.profileId)
    }

    private fun findMedication(data: ProfileData, medicationId: String): Medication =
        data.medications.firstOrNull { it.medicationId == medicationId } ?: failMessage(L10nKey.MEDICATION_NOT_FOUND)

    suspend fun takeDose(dose: DashboardDose) = recordTerminalDose(dose, DoseEventStatus.TAKEN)

    suspend fun skipDose(dose: DashboardDose) = recordTerminalDose(dose, DoseEventStatus.SKIPPED)

    suspend fun snoozeDose(dose: DashboardDose) = operation {
        val data = requireData()
        require(dose.profileId == data.profile.profileId)
        val now = currentInstant()
        val snoozedAt = kotlin.time.Instant.fromEpochSeconds(now.epochSeconds + 600)
        val event = DoseEvent(
            profileId = data.profile.profileId,
            eventId = DoseEvents.snoozeIdFor(dose.scheduleId, snoozedAt),
            medicationId = dose.medicationId,
            scheduleId = dose.scheduleId,
            scheduledFor = snoozedAt,
            status = DoseEventStatus.SCHEDULED,
            recordedAt = now,
            replacesScheduledFor = dose.scheduledFor,
        )
        if (data.doseEvents.none { it.eventId == event.eventId }) repository.addDoseEvent(event)
        loadProfile(data.profile.profileId)
    }

    private suspend fun recordTerminalDose(dose: DashboardDose, status: DoseEventStatus) = operation {
        val data = requireData()
        require(dose.profileId == data.profile.profileId)
        repository.addDoseEvent(
            DoseEvent(
                profileId = data.profile.profileId,
                eventId = DoseEvents.idFor(dose.scheduleId, dose.scheduledFor),
                medicationId = dose.medicationId,
                scheduleId = dose.scheduleId,
                scheduledFor = dose.scheduledFor,
                status = status,
                recordedAt = currentInstant(),
            ),
        )
        loadProfile(data.profile.profileId)
    }

    fun toggleObservation(observationId: String) {
        val data = mutableState.value.data ?: return
        require(data.observations.any { it.observationId == observationId && it.confirmation == ConfirmationStatus.CONFIRMED })
        val selected = mutableState.value.selectedObservationIds
        mutableState.value = mutableState.value.copy(
            selectedObservationIds = if (observationId in selected) selected - observationId else selected + observationId,
        )
    }

    /** Returns true when the answer was stored; on failure (or cancellation of the calling job) the caller keeps the user's question. */
    suspend fun ask(question: String): Boolean {
        var persisted = false
        try {
            operation {
                val data = requireData()
                val before = data.chat.size
                mutableState.value = mutableState.value.copy(streamingAnswer = "")
                try {
                    chat.ask(data, mutableState.value.selectedObservationIds, question) { partial ->
                        mutableState.value = mutableState.value.copy(streamingAnswer = partial)
                    }
                } finally {
                    mutableState.value = mutableState.value.copy(streamingAnswer = null)
                    // Stop may arrive after the exchange was saved: reload so the screen shows what was stored.
                    withContext(NonCancellable) {
                        loadProfile(data.profile.profileId)
                        persisted = (mutableState.value.data?.chat?.size ?: before) > before
                    }
                }
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // The answer is already stored, so the caller should clear the input as for a normal success.
            if (persisted) return true
            throw cancellation
        }
        return mutableState.value.error == null
    }

    /** [language] is the per-export override; null follows the app/device language. */
    suspend fun exportCsv(language: ContentLanguage? = null, options: ExportOptions = ExportOptions()) = operation {
        mutableState.value = mutableState.value.copy(
            csvExport = CsvDoctorExporter().export(requireData(), language ?: ContentLanguage.device(), options),
            notice = message(L10nKey.CSV_EXPORT_PREPARED),
        )
    }

    suspend fun exportPdf(language: ContentLanguage? = null, options: ExportOptions = ExportOptions()) = operation {
        val data = requireData()
        val chosen = language ?: ContentLanguage.device()
        val document = DoctorExportContent.document(data, ExportSectionText.titleOf(options.family, chosen), chosen, options, timeZone())
        mutableState.value = mutableState.value.copy(
            pdfExport = pdfExporter.export(document),
            notice = message(L10nKey.PDF_EXPORT_PREPARED),
        )
    }

    suspend fun installModel(pack: ModelPack, sourcePath: String) {
        modelManager.install(pack, sourcePath, currentInstant().epochSeconds)
        modelManager.select(pack.modelId)
    }

    // region L8 assistant
    /** Permanently deletes every chat message of the current profile through the repository. Records are untouched. */
    suspend fun clearChatHistory() = operation {
        val data = requireData()
        try {
            data.chat.forEach { repository.deleteChatMessage(data.profile.profileId, it.messageId) }
        } finally {
            withContext(NonCancellable) { loadProfile(data.profile.profileId) }
        }
        mutableState.value = mutableState.value.copy(notice = message(L10nKey.CHAT_HISTORY_CLEARED))
    }
    // endregion L8 assistant

    fun clearMessages() { mutableState.value = mutableState.value.copy(error = null, notice = null) }

    private suspend fun loadProfile(profileId: String) {
        val data = repository.readProfile(profileId)
        mutableState.value = mutableState.value.copy(
            profiles = repository.profiles.value,
            data = data,
            healthLinkedProfileId = repository.linkedHealthProfileId(),
            dashboard = DashboardSnapshotFactory.create(data, clock(), timeZone()),
            selectedObservationIds = mutableState.value.selectedObservationIds.intersect(
                data.observations.filter { it.confirmation == ConfirmationStatus.CONFIRMED }.map { it.observationId }.toSet(),
            ),
        )
    }

    /** Backup restore. A profile that already exists is either replaced or the backup is stored under fresh IDs. */
    suspend fun restoreBackup(data: ProfileData, mode: RestoreMode) = restoreHousehold(listOf(BackupMember(data)), mode)

    /** Restores every profile of a backup, with the original files it carries. */
    suspend fun restoreHousehold(members: List<BackupMember>, mode: RestoreMode) = operation {
        val outcome = repository.restoreMembers(members, mode, sourceStore, ::randomId)
        mutableState.value = mutableState.value.copy(profiles = repository.profiles.value)
        switchProfile(outcome.profileIds.first())
        DashboardRefreshSignal.notifyRepositoryChanged()
        mutableState.value = mutableState.value.copy(
            notice = message(if (outcome.allVitalsRestored) L10nKey.BACKUP_RESTORED else L10nKey.BACKUP_RESTORED_WITHOUT_VITALS),
        )
    }

    /**
     * Every profile with its vitals and original source files, for a household backup. A missing or unreadable file is
     * left out; loading stops with BACKUP_TOO_LARGE as soon as the originals cannot fit in a backup.
     */
    suspend fun householdForBackup(): List<BackupMember> {
        var encoded = 0L
        return repository.profiles.value.map { profile ->
            val data = repository.peekProfile(profile.profileId)
            val files = mutableMapOf<String, ByteArray>()
            data.documents.forEach { document ->
                (listOfNotNull(document.vaultKey) + document.pageVaultKeys).forEachIndexed { index, key ->
                    val fileId = backupFileId(document.documentId, index + 1)
                    val bytes = try {
                        sourceStore?.load(document.profileId, fileId, key)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (unreadable: Exception) {
                        null
                    } ?: return@forEachIndexed
                    encoded += (bytes.size + 2L) / 3 * 4
                    if (encoded > BackupService.MAX_BACKUP_WRITE_BYTES) failMessage(L10nKey.BACKUP_TOO_LARGE)
                    files[fileId] = bytes
                }
            }
            BackupMember(data, files)
        }
    }

    private suspend fun operation(block: suspend () -> Unit) {
        mutableState.value = mutableState.value.copy(loading = true, error = null, notice = null)
        try {
            block()
            mutableState.value = mutableState.value.copy(loading = false)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            mutableState.value = mutableState.value.copy(loading = false)
            throw cancellation
        } catch (error: Throwable) {
            mutableState.value = mutableState.value.copy(loading = false, error = error.toUserText())
        }
    }

    // ---- L6 security lane ----
    private val mutableRestoreResult = MutableStateFlow<RestoreResult?>(null)

    /** Outcome of the last restore started from Backups, shown there until dismissed (the restore resets navigation). */
    val restoreResult: StateFlow<RestoreResult?> = mutableRestoreResult.asStateFlow()

    fun showRestoreResult(result: RestoreResult?) {
        mutableRestoreResult.value = result
    }
    // ---- end L6 ----

    private fun requireData(): ProfileData = mutableState.value.data ?: failMessage(L10nKey.SELECT_PROFILE_FIRST)

    // region L3 import and entry
    /** Import journey state (chosen source, extraction phase, draft dates); cleared on profile change. */
    val importSession = app.vezuna.ui.entry.ImportSession()

    /**
     * Opens the platform picker for [kind] and imports what it extracts. A cancelled picker returns silently;
     * a failure or a source without results stays on the source screen with its own explanation.
     */
    suspend fun importFrom(kind: app.vezuna.ui.entry.SourceKind, picker: suspend () -> PickedSource?) {
        val session = importSession
        session.start(kind)
        val profileId = mutableState.value.data?.profile?.profileId
        val source = try {
            picker()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            session.idle()
            throw cancellation
        } catch (failure: app.vezuna.ui.entry.ImportFailure) {
            return session.fail(failure.reason, failure.fileName)
        } catch (error: Throwable) {
            return session.fail(app.vezuna.ui.entry.ImportFailureReason.UNREADABLE, null)
        } ?: return session.idle()
        // A source picked for one profile never lands in another: switching while the picker was open drops it.
        if (profileId == null || mutableState.value.data?.profile?.profileId != profileId) {
            session.idle()
            return announce(message(L10nKey.ENTRY_PROFILE_CHANGED))
        }
        if (source.candidates.isEmpty()) return session.noResults(source.fileName)
        val onSourceScreen = navigator.current == AppScreen.ImportSource || navigator.current == AppScreen.ImportExtract
        importSource(source)
        // The source step is finished: back from the review returns to where the import began, not to the picker.
        if (onSourceScreen && mutableState.value.error == null && navigator.current == AppScreen.ImportReview) {
            navigator.back()
            navigator.finishAndGo(AppScreen.ImportReview)
        }
        session.idle()
    }

    /** True when the last operation failed because the result is already recorded (the draft stays editable). */
    val lastErrorWasDuplicate: Boolean
        get() = (mutableState.value.error as? UserText.Key)?.key == L10nKey.DUPLICATE_CLINICAL_RESULT
    // endregion L3 import and entry
    // region L2 records
    /** Decrypts every stored page of the document's original file. Never throws; a failure is a [SourceFileRead]. */
    suspend fun readSourceFile(documentId: String): SourceFileRead {
        val document = mutableState.value.data?.documents?.firstOrNull { it.documentId == documentId } ?: return SourceFileRead.Missing
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

    /** Importing the same file again stores it for a document that lost its file (portable restore keeps no files). */
    private suspend fun reattachMissingSourceFile(document: SourceDocument, source: PickedSource) {
        val store = sourceStore
        if (document.vaultKey != null || store == null) return
        val saved = mutableListOf<String>()
        try {
            saved += store.save(document.profileId, document.documentId, source.bytes)
            source.additionalPages.forEachIndexed { index, page -> saved += store.save(document.profileId, backupFileId(document.documentId, index + 2), page) }
            repository.updateDocument(document.copy(vaultKey = saved.first(), pageVaultKeys = saved.drop(1)))
        } catch (error: Throwable) {
            withContext(NonCancellable) { saved.forEach { store.discard(it) } }
            throw error
        }
    }
    // endregion L2 records

    // region L9 exports
    /** Drops prepared files before a new preview is built, so the preview never shows the previous selection's bytes. */
    fun clearExports() {
        mutableState.value = mutableState.value.copy(csvExport = null, pdfExport = null)
    }
    // endregion L9 exports
}

/** Result of opening a report's original file: the decrypted pages, no stored file, or a file that failed to decrypt. */
sealed interface SourceFileRead {
    data class Available(val mediaType: String, val pages: List<ByteArray>) : SourceFileRead
    data object Missing : SourceFileRead
    data object Unreadable : SourceFileRead
}

private val REMINDER_TIME = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")
