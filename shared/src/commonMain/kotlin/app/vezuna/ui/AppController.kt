package app.vezuna.ui

import app.vezuna.data.VezunaRepository
import app.vezuna.domain.ChatMessage
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
import app.vezuna.domain.OcrCandidate
import app.vezuna.domain.OcrRevision
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.Recurrence
import app.vezuna.domain.ReferenceRange
import app.vezuna.domain.SourceDocument
import app.vezuna.domain.UnitNormalizer
import app.vezuna.export.CsvDoctorExporter
import app.vezuna.export.DoctorExportContent
import app.vezuna.export.DoctorPdfDocument
import app.vezuna.export.ExportedPdf
import app.vezuna.export.NativePdfExporter
import app.vezuna.llm.InstalledModelManager
import app.vezuna.llm.LocalHealthChat
import app.vezuna.llm.ModelPack
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.localization.failMessage
import app.vezuna.localization.message
import app.vezuna.localization.parseDecimalInput
import app.vezuna.localization.pluralMessage
import app.vezuna.localization.toUserText
import app.vezuna.platform.currentInstant
import app.vezuna.platform.randomId
import app.vezuna.platform.sha256
import app.vezuna.platform.toHex
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

sealed interface AppScreen {
    data object Dashboard : AppScreen
    data object Labs : AppScreen
    data object ImportReview : AppScreen
    data class ResultDetail(val observationId: String) : AppScreen
    data object Trends : AppScreen
    data object Medications : AppScreen
    data object AiChat : AppScreen
    data object Models : AppScreen
    data object Settings : AppScreen
}

data class AppUiState(
    val loading: Boolean = true,
    val error: UserText? = null,
    val notice: UserText? = null,
    val profiles: List<Profile> = emptyList(),
    val data: ProfileData? = null,
    val dashboard: DashboardSnapshot? = null,
    val screen: AppScreen = AppScreen.Dashboard,
    val selectedObservationIds: Set<String> = emptySet(),
    val csvExport: String? = null,
    val pdfExport: ExportedPdf? = null,
)

object DashboardRefreshSignal {
    private val mutableChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes = mutableChanges.asSharedFlow()

    fun notifyRepositoryChanged() {
        mutableChanges.tryEmit(Unit)
    }
}

class AppController(
    private val repository: VezunaRepository,
    val modelManager: InstalledModelManager,
    private val chat: LocalHealthChat,
    private val pdfExporter: NativePdfExporter = NativePdfExporter(),
    private val clock: () -> kotlinx.datetime.Instant = { currentInstant() },
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    private val mutableState = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()

    suspend fun initialize() = operation {
        if (repository.profiles.value.isEmpty()) {
            repository.createProfile(Profile(randomId(), "Me", createdAt = currentInstant()))
        }
        mutableState.value = mutableState.value.copy(profiles = repository.profiles.value)
        val profileId = mutableState.value.data?.profile?.profileId ?: repository.profiles.value.first().profileId
        loadProfile(profileId)
        modelManager.refresh()
    }

    suspend fun refreshDashboard() {
        val profileId = mutableState.value.data?.profile?.profileId ?: return
        loadProfile(profileId)
    }

    fun navigate(screen: AppScreen) {
        mutableState.value = mutableState.value.copy(screen = screen, error = null, notice = null)
    }

    suspend fun switchProfile(profileId: String) = operation {
        if (repository.profiles.value.none { it.profileId == profileId }) failMessage(L10nKey.SELECT_PROFILE_FIRST)
        mutableState.value = mutableState.value.copy(data = null, dashboard = null, selectedObservationIds = emptySet(), csvExport = null, pdfExport = null)
        loadProfile(profileId)
    }

    suspend fun createProfile(displayName: String) = operation {
        if (displayName.isBlank()) failMessage(L10nKey.PROFILE_NAME_REQUIRED)
        val profile = Profile(randomId(), displayName.trim(), createdAt = currentInstant())
        repository.createProfile(profile)
        mutableState.value = mutableState.value.copy(profiles = repository.profiles.value)
        switchProfile(profile.profileId)
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
        if (typedName.trim() != profile.displayName) failMessage(L10nKey.PROFILE_NAME_MISMATCH)
        repository.deleteProfile(profileId)
        val remaining = repository.profiles.value
        val currentId = mutableState.value.data?.profile?.profileId
        if (currentId == null || currentId == profileId) {
            mutableState.value = mutableState.value.copy(data = null, dashboard = null, selectedObservationIds = emptySet(), csvExport = null, pdfExport = null)
            loadProfile(remaining.first().profileId)
        } else {
            loadProfile(currentId)
        }
    }

    private suspend fun reloadCurrentProfile() {
        val profileId = mutableState.value.data?.profile?.profileId ?: return
        loadProfile(profileId)
    }

    suspend fun importCandidate(
        fileName: String,
        mediaType: String,
        sourceBytes: ByteArray,
        testCode: String,
        displayName: String,
        rawValue: String,
        rawUnit: String,
        referenceLower: String,
        referenceUpper: String,
        collectedAt: kotlinx.datetime.Instant,
    ) = operation {
        val range = ReferenceRange(optionalDouble(referenceLower), optionalDouble(referenceUpper), unit = rawUnit.trim())
        val inserted = importExtracted(
            PickedSource(fileName, mediaType, sourceBytes),
            listOf(ExtractedCandidate(testCode, displayName, rawValue, rawUnit, range, collectedAt)),
        )
        mutableState.value = mutableState.value.copy(
            screen = AppScreen.ImportReview,
            notice = message(L10nKey.IMPORT_MANUAL_NOTICE, inserted),
        )
    }

    suspend fun importSource(source: PickedSource) = operation {
        if (source.candidates.isEmpty()) failMessage(L10nKey.NO_CLINICAL_CANDIDATES_EXTRACTED)
        val inserted = importExtracted(source, source.candidates)
        mutableState.value = mutableState.value.copy(
            screen = AppScreen.ImportReview,
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
        val sourceHash = sha256(source.bytes).toHex()
        val existingDocument = data.documents.firstOrNull { it.sha256 == sourceHash }
        val document = existingDocument ?: SourceDocument(
            data.profile.profileId,
            randomId(),
            source.fileName.trim(),
            source.mediaType,
            sourceHash,
            currentInstant(),
        )
        if (existingDocument == null) repository.addDocument(document)
        if (data.reports.none { it.sourceDocumentId == document.documentId }) {
            repository.addReport(LabReport(data.profile.profileId, randomId(), document.documentId, importedAt = currentInstant()))
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
                    ),
                )
                inserted++
            }
        }
        loadProfile(data.profile.profileId)
        return inserted
    }

    private fun candidateFingerprint(
        testCode: String,
        displayName: String,
        rawValue: String,
        rawUnit: String,
        range: ReferenceRange,
        collectedAt: kotlinx.datetime.Instant,
    ): String = listOf(
        testCode.trim().uppercase(),
        displayName.trim().lowercase(),
        rawValue.trim(),
        rawUnit.trim(),
        range.lower?.toString().orEmpty(),
        range.upper?.toString().orEmpty(),
        range.text.orEmpty(),
        range.unit.orEmpty(),
        collectedAt.toString(),
    ).joinToString("|")

    suspend fun confirmCandidate(
        candidateId: String,
        correctedValue: String,
        correctedUnit: String,
        referenceLower: String,
        referenceUpper: String,
    ) = operation {
        val data = requireData()
        val candidate = data.ocrCandidates.firstOrNull { it.candidateId == candidateId }
            ?: failMessage(L10nKey.CANDIDATE_UNAVAILABLE)
        if (candidate.status != ConfirmationStatus.UNREVIEWED) failMessage(L10nKey.CANDIDATE_ALREADY_REVIEWED)
        val value = parseDecimalInput(correctedValue) ?: failMessage(L10nKey.ENTER_NUMERIC_RESULT)
        if (correctedUnit.isBlank()) failMessage(L10nKey.NUMERIC_CANDIDATE_UNIT_REQUIRED)
        val normalized = UnitNormalizer.normalize(value, correctedUnit)
        val range = ReferenceRange(optionalDouble(referenceLower), optionalDouble(referenceUpper), unit = correctedUnit.trim())
        val revisions = candidate.revisions + OcrRevision(
            data.profile.profileId,
            randomId(),
            currentInstant(),
            "${candidate.rawValue} ${candidate.rawUnit}",
            "$correctedValue $correctedUnit",
        )
        val report = data.reports.first { it.sourceDocumentId == candidate.sourceDocumentId }
        val inserted = repository.addObservation(
            LabObservation(
                profileId = data.profile.profileId,
                observationId = randomId(),
                reportId = report.reportId,
                testCode = candidate.testCode,
                displayName = candidate.displayName,
                originalValue = value,
                originalUnit = correctedUnit.trim(),
                normalizedValue = normalized.value,
                normalizedUnit = normalized.unit,
                referenceRange = range,
                collectedAt = candidate.collectedAt,
                confirmation = ConfirmationStatus.CONFIRMED,
            ),
        )
        if (!inserted) failMessage(L10nKey.DUPLICATE_CLINICAL_RESULT)
        repository.updateCandidate(candidate.copy(status = ConfirmationStatus.CONFIRMED, revisions = revisions))
        loadProfile(data.profile.profileId)
        mutableState.value = mutableState.value.copy(notice = message(L10nKey.CONFIRMED_RESULT_SAVED))
    }

    suspend fun rejectCandidate(candidateId: String) = operation {
        val data = requireData()
        val candidate = data.ocrCandidates.firstOrNull { it.candidateId == candidateId }
            ?: failMessage(L10nKey.CANDIDATE_UNAVAILABLE)
        repository.updateCandidate(candidate.copy(status = ConfirmationStatus.REJECTED))
        loadProfile(data.profile.profileId)
    }

    suspend fun addMedication(name: String, strength: String, instructions: String, time: String) = operation {
        val data = requireData()
        if (name.isBlank()) failMessage(L10nKey.MEDICATION_NAME_REQUIRED)
        val canonicalTime = time.trim()
        if (!REMINDER_TIME.matches(canonicalTime)) failMessage(L10nKey.INVALID_REMINDER_TIME)
        val medicationId = randomId()
        val schedule = DoseSchedule(
            profileId = data.profile.profileId,
            scheduleId = randomId(),
            medicationId = medicationId,
            recurrence = Recurrence.DAILY,
            startDate = clock().toLocalDateTime(timeZone()).date,
            timesLocal = listOf(canonicalTime),
        )
        repository.addMedication(Medication(data.profile.profileId, medicationId, name.trim(), strength.trim().ifBlank { null }, instructions.trim().ifBlank { null }, listOf(schedule)))
        loadProfile(data.profile.profileId)
    }

    suspend fun takeDose(dose: DashboardDose) = recordTerminalDose(dose, DoseEventStatus.TAKEN)

    suspend fun skipDose(dose: DashboardDose) = recordTerminalDose(dose, DoseEventStatus.SKIPPED)

    suspend fun snoozeDose(dose: DashboardDose) = operation {
        val data = requireData()
        require(dose.profileId == data.profile.profileId)
        val now = currentInstant()
        val snoozedAt = kotlinx.datetime.Instant.fromEpochSeconds(now.epochSeconds + 600)
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

    suspend fun ask(question: String) = operation {
        val data = requireData()
        chat.ask(data, mutableState.value.selectedObservationIds, question)
        loadProfile(data.profile.profileId)
    }

    suspend fun exportCsv() = operation {
        mutableState.value = mutableState.value.copy(
            csvExport = CsvDoctorExporter().export(requireData()),
            notice = message(L10nKey.CSV_EXPORT_PREPARED),
        )
    }

    suspend fun exportPdf() = operation {
        val data = requireData()
        val document = DoctorExportContent.document(data)
        mutableState.value = mutableState.value.copy(
            pdfExport = pdfExporter.export(document),
            notice = message(L10nKey.PDF_EXPORT_PREPARED),
        )
    }

    suspend fun installModel(pack: ModelPack, bytes: ByteArray) {
        modelManager.install(pack, bytes, currentInstant().epochSeconds)
        modelManager.select(pack.modelId)
    }

    fun clearMessages() { mutableState.value = mutableState.value.copy(error = null, notice = null) }

    private suspend fun loadProfile(profileId: String) {
        val data = repository.readProfile(profileId)
        mutableState.value = mutableState.value.copy(
            profiles = repository.profiles.value,
            data = data,
            dashboard = DashboardSnapshotFactory.create(data, clock(), timeZone()),
            selectedObservationIds = mutableState.value.selectedObservationIds.intersect(
                data.observations.filter { it.confirmation == ConfirmationStatus.CONFIRMED }.map { it.observationId }.toSet(),
            ),
        )
    }

    private suspend fun operation(block: suspend () -> Unit) {
        mutableState.value = mutableState.value.copy(loading = true, error = null, notice = null)
        try {
            block()
            mutableState.value = mutableState.value.copy(loading = false)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            mutableState.value = mutableState.value.copy(loading = false, error = error.toUserText())
        }
    }

    private fun requireData(): ProfileData = mutableState.value.data ?: failMessage(L10nKey.SELECT_PROFILE_FIRST)

    private fun optionalDouble(raw: String): Double? = raw.trim().takeIf { it.isNotEmpty() }?.let {
        parseDecimalInput(it) ?: failMessage(L10nKey.REFERENCE_BOUNDS_NUMERIC)
    }
}

private val REMINDER_TIME = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")
