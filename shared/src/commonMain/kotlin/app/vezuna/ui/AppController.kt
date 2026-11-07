package app.vezuna.ui

import app.vezuna.data.VezunaRepository
import app.vezuna.domain.DashboardDose
import app.vezuna.domain.ProfileData
import app.vezuna.export.ExportOptions
import app.vezuna.export.NativePdfExporter
import app.vezuna.llm.InstalledModelManager
import app.vezuna.llm.LocalHealthChat
import app.vezuna.llm.ModelPack
import app.vezuna.localization.ContentLanguage
import app.vezuna.localization.UserText
import app.vezuna.platform.currentInstant
import app.vezuna.security.BackupMember
import app.vezuna.security.BackupService
import app.vezuna.ui.assistant.AssistantController
import app.vezuna.ui.care.CareController
import app.vezuna.ui.entry.ImportController
import app.vezuna.ui.entry.SourceKind
import app.vezuna.ui.export.ExportController
import app.vezuna.ui.more.ProfileController
import app.vezuna.ui.nav.Destination
import app.vezuna.ui.nav.Navigator
import app.vezuna.ui.records.DocumentVaultController
import app.vezuna.ui.records.RecordsController
import app.vezuna.ui.records.storedFileOpens
import app.vezuna.ui.security.BackupController
import app.vezuna.ui.security.LeaveOutProposal
import app.vezuna.ui.security.RestoreResult
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * Composition root and public facade. The work lives in per-feature controllers that share one [ControllerCore]
 * (the single [AppUiState] flow); this class only wires them and forwards the calls screens and bridges use.
 */
class AppController(
    repository: VezunaRepository,
    val modelManager: InstalledModelManager,
    chat: LocalHealthChat,
    pdfExporter: NativePdfExporter = NativePdfExporter(),
    clock: () -> kotlin.time.Instant = { currentInstant() },
    timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    /** Null when the platform offers no backup file access (previews, tests). */
    val backup: BackupService? = null,
    sourceStore: SourceFileStore? = null,
    warn: (String, Throwable) -> Unit = { _, _ -> },
    /** Whether a file for the document vault can be opened on this device: media type and pages. */
    documentFileOpens: suspend (String, List<ByteArray>) -> Boolean = ::storedFileOpens,
) {
    private val core = ControllerCore(repository, clock, timeZone, sourceStore)
    val state: StateFlow<AppUiState> = core.mutableState.asStateFlow()

    /** Back stacks for the five root destinations; the shell renders [Navigator.state]. */
    val navigator = Navigator()

    private val profiles = ProfileController(core, modelManager, { onProfileChanged() }, warn)
    private val imports = ImportController(core, navigator)
    private val records = RecordsController(core, navigator)
    private val healthRecords = app.vezuna.ui.records.HealthRecordsController(core)
    private val appointments = app.vezuna.ui.records.AppointmentsController(core)
    private val care = CareController(core)
    private val assistant = AssistantController(core, repository, chat)
    private val exports = ExportController(core, pdfExporter)
    private val backups = BackupController(core, profiles::switchProfile, profiles::discardUntouchedDefaultAfterRestore)
    private val documentVault = DocumentVaultController(core, navigator, imports.importSession, backups::householdFitsBackup, documentFileOpens)

    /** Import journey state (chosen source, extraction phase, draft dates); cleared on profile change. */
    val importSession get() = imports.importSession

    /** True when the last operation failed because the result is already recorded (the draft stays editable). */
    val lastErrorWasDuplicate: Boolean get() = imports.lastErrorWasDuplicate

    /** Outcome of the last restore started from Backups, shown there until dismissed (the restore resets navigation). */
    val restoreResult: StateFlow<RestoreResult?> get() = backups.restoreResult

    /**
     * Clears everything bound to the previous profile before another one loads: its data, the chat record
     * selection, prepared exports and every pushed route (routes carry that profile's IDs). The shell also
     * keys saved UI state (filters, drafts, scroll) by profile, so those are dropped with it.
     * Lanes that add profile-bound state clear it here.
     */
    private fun onProfileChanged() {
        core.mutableState.value = core.mutableState.value.copy(data = null, dashboard = null, selectedObservationIds = emptySet(), csvExport = null, pdfExport = null, streamingAnswer = null)
        assistant.cancelAsk()
        imports.importSession.clear()
        documentVault.onProfileChanged()
        backups.onProfileChanged()
        navigator.resetToRoots()
    }

    // navigation and messages
    fun announce(notice: UserText) = core.announce(notice)

    fun navigate(screen: AppScreen) {
        navigator.navigate(screen)
        profiles.onRouteChanged(navigator.current)
        clearMessages()
    }

    fun selectRoot(destination: Destination) {
        navigator.selectRoot(destination)
        profiles.onRouteChanged(navigator.current)
        clearMessages()
    }

    /** False when there is nothing to go back to inside the app. */
    fun back(): Boolean = navigator.back().also {
        if (it) {
            profiles.onRouteChanged(navigator.current)
            clearMessages()
        }
    }

    fun clearMessages() = core.clearMessages()

    suspend fun refreshDashboard() = core.reloadCurrentProfile()

    // profiles
    suspend fun initialize() = profiles.initialize()
    suspend fun switchProfile(profileId: String) = profiles.switchProfile(profileId)
    suspend fun createProfile(displayName: String, relationship: String = "") = profiles.createProfile(displayName, relationship)

    /** Sets or clears (null) the birth date. Rejects future dates and ages above [app.vezuna.domain.ProfileAge.MAX_YEARS]. */
    suspend fun setBirthDate(profileId: String, birthDate: LocalDate?) = profiles.setBirthDate(profileId, birthDate)
    suspend fun setRelationship(profileId: String, relationship: String) = profiles.setRelationship(profileId, relationship)

    /** Saves the first profile; finish ends onboarding. A blank name keeps the default. */
    suspend fun completeOnboarding(displayName: String, birthDate: LocalDate?, finish: Boolean = true, relationship: String = "") = profiles.completeOnboarding(displayName, birthDate, finish, relationship)
    fun skipOnboarding() = profiles.skipOnboarding()

    /** Welcome screen's Restore: like [skipOnboarding], and marks the next successful restore as the first-time one. */
    fun startFirstRunRestore() = profiles.startFirstRunRestore()
    suspend fun renameProfile(profileId: String, displayName: String) = profiles.renameProfile(profileId, displayName)

    /** The caller must type the exact profile name; the last remaining profile can never be deleted. */
    suspend fun deleteProfile(profileId: String, typedName: String) = profiles.deleteProfile(profileId, typedName)
    internal suspend fun linkOnboardingHealthProfile(profileId: String) = profiles.linkOnboardingHealthProfile(profileId)
    suspend fun setLinkedHealthProfile(profileId: String) = profiles.setLinkedHealthProfile(profileId)

    // import
    suspend fun importSource(source: PickedSource) = imports.importSource(source)

    /**
     * Opens the platform picker for [kind] and imports what it extracts. A cancelled picker returns silently;
     * a failure or a source without results stays on the source screen with its own explanation.
     */
    suspend fun importFrom(kind: SourceKind, picker: suspend () -> PickedSource?) = imports.importFrom(kind, picker)

    // records
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
    ) = records.confirmCandidate(candidateId, correctedValue, correctedUnit, referenceLower, referenceUpper, collectedAt, testCode, displayName, asText, referenceText)

    /** A result typed in from a paper report: no source file, a new "manual" report, and a confirmed result. The collection date is required. */
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
    ) = records.addManualResult(testCode, displayName, rawValue, rawUnit, referenceLower, referenceUpper, referenceText, collectedAt, asText)

    /** Edits a confirmed result; the previous state is appended to its revisions and duplicates are re-checked. */
    suspend fun editObservation(
        observationId: String,
        correctedValue: String,
        correctedUnit: String,
        referenceLower: String,
        referenceUpper: String,
        referenceText: String?,
        collectedAt: kotlin.time.Instant?,
    ) = records.editObservation(observationId, correctedValue, correctedUnit, referenceLower, referenceUpper, referenceText, collectedAt)

    /** Removes the report with its source document, stored file, draft candidates and confirmed results (repository cascade). */
    suspend fun deleteReport(reportId: String) = records.deleteReport(reportId)
    internal suspend fun saveHealthRecord(recordId: String?, draft: app.vezuna.ui.records.HealthRecordDraft) = healthRecords.save(recordId, draft)
    suspend fun deleteHealthRecord(recordId: String) = healthRecords.delete(recordId)
    internal suspend fun saveAppointment(appointmentId: String?, draft: app.vezuna.ui.records.AppointmentDraft) = appointments.save(appointmentId, draft)
    suspend fun deleteAppointment(appointmentId: String) = appointments.delete(appointmentId)
    suspend fun rejectCandidate(candidateId: String) = records.rejectCandidate(candidateId)

    /** The reviewer's laboratory name and order date for a report; blank clears the name, null clears the date. */
    suspend fun updateReportDetails(reportId: String, laboratoryName: String, orderedAt: kotlin.time.Instant?) = records.updateReportDetails(reportId, laboratoryName, orderedAt)

    /** Returns a rejected candidate to review. Only a rejected candidate can be restored; a confirmed one never. */
    suspend fun restoreCandidate(candidateId: String) = records.restoreCandidate(candidateId)
    fun toggleObservation(observationId: String) = records.toggleObservation(observationId)

    /** Decrypts every stored page of the document's original file. Never throws; a failure is a [SourceFileRead]. */
    suspend fun readSourceFile(documentId: String): SourceFileRead = records.readSourceFile(documentId)

    // region document vault
    /** The tag the Documents list is filtered by; cleared on profile change. */
    val documentTagFilter: StateFlow<String?> get() = documentVault.tagFilter
    fun filterDocumentsByTag(tag: String?) = documentVault.filterByTag(tag)
    suspend fun addDocumentFrom(kind: app.vezuna.ui.entry.SourceKind, picker: suspend () -> PickedSource?) = documentVault.addFrom(kind, picker)
    suspend fun updateDocument(documentId: String, name: String, tags: List<String>) = documentVault.update(documentId, name, tags)
    suspend fun deleteDocument(documentId: String) = documentVault.delete(documentId)
    // endregion document vault

    // medications and doses
    suspend fun addMedication(name: String, strength: String, instructions: String, time: String) = care.addMedication(name, strength, instructions, time)
    suspend fun addMedication(name: String, strength: String, instructions: String, drafts: List<ScheduleDraft>, supplyUnits: String = "", lowThreshold: String = "") =
        care.addMedication(name, strength, instructions, drafts, supplyUnits, lowThreshold)

    suspend fun updateMedication(medicationId: String, name: String, strength: String, instructions: String, drafts: List<ScheduleDraft>, supplyUnits: String? = null, lowThreshold: String = "", unitsShown: String? = null) =
        care.updateMedication(medicationId, name, strength, instructions, drafts, supplyUnits, lowThreshold, unitsShown)

    suspend fun updateMedication(medicationId: String, name: String, strength: String, instructions: String, time: String) = care.updateMedication(medicationId, name, strength, instructions, time)

    /** Adds [added] units to what is left now and restarts the count; only a medication that tracks supply can be refilled. */
    suspend fun recordRefill(medicationId: String, added: String) = care.recordRefill(medicationId, added)

    /** Pausing keeps history; resuming also clears an end date that has already passed (a stopped medication). */
    suspend fun setMedicationActive(medicationId: String, active: Boolean) = care.setMedicationActive(medicationId, active)

    /** Stopping ends the schedules today (never before they start) and deactivates reminders immediately. */
    suspend fun stopMedication(medicationId: String) = care.stopMedication(medicationId)
    suspend fun deleteMedication(medicationId: String) = care.deleteMedication(medicationId)
    suspend fun takeDose(dose: DashboardDose) = care.takeDose(dose)
    suspend fun skipDose(dose: DashboardDose) = care.skipDose(dose)
    suspend fun snoozeDose(dose: DashboardDose) = care.snoozeDose(dose)

    // assistant
    /** Returns true when the answer was stored; on failure (or cancellation of the calling job) the caller keeps the user's question. */
    suspend fun ask(question: String): Boolean = assistant.ask(question)

    /** Permanently deletes every chat message of the current profile through the repository. Records are untouched. */
    suspend fun clearChatHistory() = assistant.clearChatHistory()

    // exports
    /** [language] is the per-export override; null follows the app/device language. */
    suspend fun exportCsv(language: ContentLanguage? = null, options: ExportOptions = ExportOptions()) = exports.exportCsv(language, options)
    suspend fun exportPdf(language: ContentLanguage? = null, options: ExportOptions = ExportOptions()) = exports.exportPdf(language, options)

    /** Drops prepared files before a new preview is built, so the preview never shows the previous selection's bytes. */
    fun clearExports() = exports.clearExports()

    // models
    suspend fun installModel(pack: ModelPack, sourcePath: String) {
        modelManager.install(pack, sourcePath, currentInstant().epochSeconds)
        modelManager.select(pack.modelId)
    }

    // backup
    fun showRestoreResult(result: RestoreResult?) = backups.showRestoreResult(result)

    /** Backup restore. A profile that already exists is either replaced or the backup is stored under fresh IDs. */
    suspend fun restoreBackup(data: ProfileData, mode: RestoreMode) = backups.restoreBackup(data, mode)

    /** Restores every profile of a backup, with the original files it carries. */
    suspend fun restoreHousehold(members: List<BackupMember>, mode: RestoreMode) = backups.restoreHousehold(members, mode)

    /** Every profile with its vitals and original source files, for a household backup. */
    suspend fun householdForBackup(omit: Set<Pair<String, String>> = emptySet(), leaveOutChat: Boolean = false): List<BackupMember> = backups.householdForBackup(omit, leaveOutChat)

    suspend fun prepareHouseholdBackup(agreed: LeaveOutProposal?, leaveOutChat: Boolean): app.vezuna.ui.security.PreparedHouseholdBackup = backups.prepareHouseholdBackup(agreed, leaveOutChat)

    /** Vault documents to leave out (largest first) so a household backup fits; nothing is left out until the person agrees. */
    suspend fun proposeLeaveOut(leaveOutChat: Boolean = false): LeaveOutProposal = backups.proposeLeaveOut(leaveOutChat)
}

/** Result of opening a report's original file: the decrypted pages, no stored file, or a file that failed to decrypt. */
sealed interface SourceFileRead {
    data class Available(val mediaType: String, val pages: List<ByteArray>) : SourceFileRead
    data object Missing : SourceFileRead
    data object Unreadable : SourceFileRead
}
