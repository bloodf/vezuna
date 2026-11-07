package app.vezuna.data

import app.vezuna.health.*
import app.vezuna.domain.AggregateWindow

import app.vezuna.domain.ChatMessage
import app.vezuna.domain.DeviceVitals
import app.vezuna.domain.HealthLink
import app.vezuna.domain.VitalsBatch
import app.vezuna.domain.ClinicalDuplicateDetector
import app.vezuna.domain.DoseEvent
import app.vezuna.domain.DoseEvents
import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.Appointment
import app.vezuna.domain.HealthRecord
import app.vezuna.domain.LabObservation
import app.vezuna.domain.LabReport
import app.vezuna.domain.Medication
import app.vezuna.domain.OcrCandidate
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.ProfileVitals
import app.vezuna.domain.SourceDocument
import app.vezuna.domain.VaultDocument
import app.vezuna.domain.addingVaultDocument
import app.vezuna.domain.isInternallyConsistent
import app.vezuna.domain.withVaultDocument
import app.vezuna.domain.withoutVaultDocument
import app.vezuna.domain.withChatMessage
import app.vezuna.domain.withDocument
import app.vezuna.domain.withAppointment
import app.vezuna.domain.withHealthRecord
import app.vezuna.domain.withMedication
import app.vezuna.domain.withNewAppointment
import app.vezuna.domain.withNewHealthRecord
import app.vezuna.domain.withObservation
import app.vezuna.domain.withProfile
import app.vezuna.domain.withReport
import app.vezuna.domain.withSchedule
import app.vezuna.domain.withoutChatMessage
import app.vezuna.domain.withoutDocument
import app.vezuna.domain.withoutAppointment
import app.vezuna.domain.withoutHealthRecord
import app.vezuna.domain.withoutMedication
import app.vezuna.domain.withoutObservation
import app.vezuna.domain.withoutReport
import app.vezuna.domain.withoutSchedule
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/** Legacy nonpaging write and same-lock context. This is not a transactional cursor acknowledgement. */
data class VitalsSyncCommit(val storedCount: Int, val context: VitalsSyncContext)

/** Captured under the repository lock before a platform read; never recapture at commit time. */
data class VitalsSyncContext(val profileId: String, val generation: Long, val lastSync: Instant?, val zone: TimeZone)

interface VezunaRepository {
    val profiles: StateFlow<List<Profile>>
    suspend fun createProfile(profile: Profile)
    suspend fun readProfile(profileId: String): ProfileData

    /** Like [readProfile] but never changes which profile the repository treats as selected. */
    suspend fun peekProfile(profileId: String): ProfileData = readProfile(profileId)
    suspend fun addDocument(document: SourceDocument)
    suspend fun addReport(report: LabReport)
    suspend fun addCandidate(candidate: OcrCandidate)
    suspend fun updateCandidate(candidate: OcrCandidate)
    suspend fun addObservation(observation: LabObservation): Boolean
    suspend fun addMedication(medication: Medication)
    suspend fun addDoseEvent(event: DoseEvent)
    suspend fun addDoseEvents(profileId: String, events: List<DoseEvent>)
    suspend fun addChatMessage(message: ChatMessage)

    /** Deleting a profile removes all its records and stored source files. */
    suspend fun updateProfile(profile: Profile)
    suspend fun deleteProfile(profileId: String)
    suspend fun updateDocument(document: SourceDocument)
    /** Cascades to the document's reports, observations, candidates and stored source file. */
    suspend fun deleteDocument(profileId: String, documentId: String)
    // region document vault
    suspend fun addVaultDocument(document: VaultDocument)
    suspend fun updateVaultDocument(document: VaultDocument)
    /** The record goes first; its stored file is removed only once that is durable. */
    suspend fun deleteVaultDocument(profileId: String, documentId: String)
    // endregion document vault
    suspend fun updateReport(report: LabReport)
    /** Cascades to the report's observations and, when unshared, its candidates, document and stored source file. */
    suspend fun deleteReport(profileId: String, reportId: String)
    suspend fun updateObservation(observation: LabObservation)
    suspend fun deleteObservation(profileId: String, observationId: String)
    suspend fun updateMedication(medication: Medication)
    /** Cascades to the medication's schedules and dose events. */
    suspend fun deleteMedication(profileId: String, medicationId: String)
    suspend fun updateSchedule(schedule: DoseSchedule)
    suspend fun deleteSchedule(profileId: String, scheduleId: String)
    suspend fun updateChatMessage(message: ChatMessage)
    suspend fun deleteChatMessage(profileId: String, messageId: String)
    suspend fun addHealthRecord(record: HealthRecord)
    suspend fun updateHealthRecord(record: HealthRecord)
    suspend fun deleteHealthRecord(profileId: String, recordId: String)
    suspend fun addAppointment(appointment: Appointment)
    suspend fun updateAppointment(appointment: Appointment)
    suspend fun deleteAppointment(profileId: String, appointmentId: String)

    /**
     * Creates the profile or atomically replaces every record of the existing one with [data] (no stale records
     * survive). Source files of the replaced profile that [data] no longer references are removed.
     * Vitals are stored separately by persistent implementations: use [restoreWithVitals] to restore both.
     */
    suspend fun restoreProfile(data: ProfileData)
    // --- Device vitals (Health Connect / HealthKit) ---
    /** The one profile that receives device vitals; defaults to the first created profile. Null when there are none. */
    suspend fun linkedHealthProfileId(): String?
    suspend fun setLinkedHealthProfile(profileId: String)
    /** Pins only the already resolved target; never replaces another profile's link. Returns the same-lock sync context. */
    suspend fun linkOnboardingHealthProfile(profileId: String, zone: TimeZone = TimeZone.currentSystemDefault()): VitalsSyncContext?
    /** The stored link only, without the oldest-profile default. Falls back to the resolved link (the safe, "keep" answer). */
    suspend fun explicitHealthLinkId(): String? = linkedHealthProfileId()
    /** Atomically captures the resolved link, its generation and the stored sync window. */
    suspend fun captureVitalsSync(zone: TimeZone = TimeZone.currentSystemDefault()): VitalsSyncContext
    /** Rejects stale contexts before any write; returns how many entries were actually stored. */
    suspend fun ingestDeviceVitals(context: VitalsSyncContext, batch: VitalsBatch, now: Instant): Int =
        ingestDeviceVitalsAndCapture(context, batch, now).storedCount
    /** Returns the exact successor under the commit lock, never a freshly recaptured target. */
    suspend fun ingestDeviceVitalsAndCapture(context: VitalsSyncContext, batch: VitalsBatch, now: Instant): VitalsSyncCommit
    /** Runs source cleanup only while the original/successor context remains current. Action must not reenter this repository. */
    suspend fun withValidatedVitalsSync(context: VitalsSyncContext, action: suspend () -> Unit)
    /** Resolves storage/bookkeeping only; success never grants a paging successor or native authorization. */
    suspend fun resolveVitalsWrite(operationId: String): Boolean = false
    suspend fun readVitalsState(context: VitalsSyncContext): VitalsReadState
    suspend fun commitWeightPage(state: VitalsReadState, page: WeightPage): VitalsWriteOutcome
    suspend fun commitAggregateWindow(state: VitalsReadState, window: AggregateWindow): VitalsWriteOutcome
    suspend fun restoreProfileAndVitals(data: ProfileData): Boolean {
        restoreProfile(data.copy(vitals = ProfileVitals()))
        kotlin.coroutines.coroutineContext[RestoreRecordWrite]?.mayBeStored = true
        return try { replaceVitals(data.profile.profileId, data.vitals.copy(lastSync = null)); true }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }
    /** Replaces clinical values and clears aggregate lastSync and all device-local sync/provenance state. */
    suspend fun replaceVitals(profileId: String, vitals: ProfileVitals)
}


/** Tracks whether attachment cleanup is still safe, including writes whose outcome is unknown. */
internal class RestoreRecordWrite : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<RestoreRecordWrite>
    var mayBeStored = false
}

/**
 * Restores a backup including its device vitals; the vitals replace whatever the profile had. `lastSync` is cleared so
 * the next device sync reads its full first window. Returns false when the profile was restored but the vitals could not
 * be written (the profile and its records are kept).
 */
suspend fun VezunaRepository.restoreWithVitals(data: ProfileData): Boolean = restoreProfileAndVitals(data)

/** Volatile implementation used by tests and previews. */
class InMemoryVezunaRepository : VezunaRepository {
    private val mutex = Mutex()
    private var records = mapOf<String, ProfileData>()
    private var healthLink: String? = null
    private var vitalsSyncGeneration = 0L
    private val storedVitals = mutableMapOf<String, StoredVitals>()
    private val mutableProfiles = MutableStateFlow<List<Profile>>(emptyList())
    override val profiles = mutableProfiles.asStateFlow()

    override suspend fun createProfile(profile: Profile) = mutex.withLock {
        require(!records.containsKey(profile.profileId)) { "Profile already exists" }
        publish(records + (profile.profileId to ProfileData(profile)))
    }

    override suspend fun readProfile(profileId: String): ProfileData = mutex.withLock { records.getValue(profileId) }

    override suspend fun updateProfile(profile: Profile) = mutate(profile.profileId) { it.withProfile(profile) }

    override suspend fun deleteProfile(profileId: String) = mutex.withLock {
        require(records.containsKey(profileId)) { "Profile not found" }
        if (HealthLink.resolve(healthLink, records.values.map { it.profile }) == profileId) vitalsSyncGeneration += 1
        storedVitals.remove(profileId)
        val remaining = records - profileId
        if (healthLink == profileId) healthLink = HealthLink.resolve(null, remaining.values.map { it.profile })
        publish(remaining)
    }

    override suspend fun addDocument(document: SourceDocument) = mutate(document.profileId) { data ->
        require(data.documents.none { it.documentId == document.documentId })
        data.copy(documents = data.documents + document)
    }

    override suspend fun updateDocument(document: SourceDocument) = mutate(document.profileId) { it.withDocument(document) }

    override suspend fun deleteDocument(profileId: String, documentId: String) = mutate(profileId) { it.withoutDocument(documentId) }

    // region document vault
    override suspend fun addVaultDocument(document: VaultDocument) = mutate(document.source.profileId) { it.addingVaultDocument(document) }

    override suspend fun updateVaultDocument(document: VaultDocument) = mutate(document.source.profileId) { it.withVaultDocument(document) }

    override suspend fun deleteVaultDocument(profileId: String, documentId: String) = mutate(profileId) { it.withoutVaultDocument(documentId) }
    // endregion document vault

    override suspend fun addReport(report: LabReport) = mutate(report.profileId) { data ->
        require(report.sourceDocumentId == null || data.documents.any { it.documentId == report.sourceDocumentId }) { "Source document must exist in this profile" }
        require(data.reports.none { it.reportId == report.reportId })
        data.copy(reports = data.reports + report)
    }

    override suspend fun updateReport(report: LabReport) = mutate(report.profileId) { it.withReport(report) }

    override suspend fun deleteReport(profileId: String, reportId: String) = mutate(profileId) { it.withoutReport(reportId) }

    override suspend fun addCandidate(candidate: OcrCandidate) = mutate(candidate.profileId) { data ->
        require(data.documents.any { it.documentId == candidate.sourceDocumentId }) { "Source document must exist in this profile" }
        require(data.ocrCandidates.none { it.candidateId == candidate.candidateId })
        data.copy(ocrCandidates = data.ocrCandidates + candidate)
    }

    override suspend fun updateCandidate(candidate: OcrCandidate) = mutate(candidate.profileId) { data ->
        require(data.ocrCandidates.any { it.candidateId == candidate.candidateId })
        data.copy(ocrCandidates = data.ocrCandidates.map { if (it.candidateId == candidate.candidateId) candidate else it })
    }

    override suspend fun addObservation(observation: LabObservation): Boolean = mutex.withLock {
        val data = records.getValue(observation.profileId)
        require(data.reports.any { it.reportId == observation.reportId }) { "Report must exist in this profile" }
        if (data.observations.any { ClinicalDuplicateDetector.areDuplicates(it, observation) }) return@withLock false
        require(data.observations.none { it.observationId == observation.observationId })
        publish(records + (data.profile.profileId to data.copy(observations = data.observations + observation)))
        true
    }

    override suspend fun updateObservation(observation: LabObservation) = mutate(observation.profileId) { it.withObservation(observation) }

    override suspend fun deleteObservation(profileId: String, observationId: String) = mutate(profileId) { it.withoutObservation(observationId) }

    override suspend fun addMedication(medication: Medication) = mutate(medication.profileId) { data ->
        require(medication.schedules.all { it.profileId == medication.profileId && it.medicationId == medication.medicationId })
        require(data.medications.none { it.medicationId == medication.medicationId })
        data.copy(medications = data.medications + medication)
    }

    override suspend fun updateMedication(medication: Medication) = mutate(medication.profileId) { it.withMedication(medication) }

    override suspend fun deleteMedication(profileId: String, medicationId: String) = mutate(profileId) { it.withoutMedication(medicationId) }

    override suspend fun updateSchedule(schedule: DoseSchedule) = mutate(schedule.profileId) { it.withSchedule(schedule) }

    override suspend fun deleteSchedule(profileId: String, scheduleId: String) = mutate(profileId) { it.withoutSchedule(scheduleId) }

    override suspend fun addDoseEvent(event: DoseEvent) = mutate(event.profileId) { data ->
        require(data.medications.any { medication -> medication.medicationId == event.medicationId && medication.schedules.any { it.scheduleId == event.scheduleId } })
        data.copy(doseEvents = DoseEvents.merge(data.doseEvents, event))
    }

    /** One write for many events (e.g. MISSED bookkeeping); events whose schedule no longer exists are skipped. */
    override suspend fun addDoseEvents(profileId: String, events: List<DoseEvent>) {
        if (events.isEmpty()) return
        mutate(profileId) { data ->
            data.copy(
                doseEvents = events.filter { event ->
                    data.medications.any { m -> m.medicationId == event.medicationId && m.schedules.any { it.scheduleId == event.scheduleId } }
                }.fold(data.doseEvents) { acc, event -> DoseEvents.merge(acc, event) },
            )
        }
    }

    override suspend fun addChatMessage(message: ChatMessage) = mutate(message.profileId) { data ->
        data.copy(chat = data.chat + message)
    }

    override suspend fun updateChatMessage(message: ChatMessage) = mutate(message.profileId) { it.withChatMessage(message) }

    override suspend fun deleteChatMessage(profileId: String, messageId: String) = mutate(profileId) { it.withoutChatMessage(messageId) }

    override suspend fun addHealthRecord(record: HealthRecord) = mutate(record.profileId) { it.withNewHealthRecord(record) }

    override suspend fun updateHealthRecord(record: HealthRecord) = mutate(record.profileId) { it.withHealthRecord(record) }

    override suspend fun deleteHealthRecord(profileId: String, recordId: String) = mutate(profileId) { it.withoutHealthRecord(recordId) }

    override suspend fun addAppointment(appointment: Appointment) = mutate(appointment.profileId) { it.withNewAppointment(appointment) }

    override suspend fun updateAppointment(appointment: Appointment) = mutate(appointment.profileId) { it.withAppointment(appointment) }

    override suspend fun deleteAppointment(profileId: String, appointmentId: String) = mutate(profileId) { it.withoutAppointment(appointmentId) }

    override suspend fun restoreProfile(data: ProfileData) = mutex.withLock {
        require(data.isInternallyConsistent()) { "Backup records are inconsistent" }
        if (HealthLink.resolve(healthLink, records.values.map { it.profile }) == data.profile.profileId) vitalsSyncGeneration += 1
        storedVitals[data.profile.profileId] = StoredVitals(clinical = data.vitals.copy(lastSync = null))
        publish(records + (data.profile.profileId to data.copy(vitals = data.vitals.copy(lastSync = null))))
    }

    override suspend fun linkedHealthProfileId(): String? = mutex.withLock { HealthLink.resolve(healthLink, records.values.map { it.profile }) }

    override suspend fun explicitHealthLinkId(): String? = mutex.withLock { healthLink }

    override suspend fun setLinkedHealthProfile(profileId: String) = mutex.withLock {
        require(records.containsKey(profileId)) { "Profile not found" }
        if (HealthLink.resolve(healthLink, records.values.map { it.profile }) != profileId) vitalsSyncGeneration += 1
        healthLink = profileId
    }

    override suspend fun linkOnboardingHealthProfile(profileId: String, zone: TimeZone): VitalsSyncContext? = mutex.withLock {
        if (HealthLink.resolve(healthLink, records.values.map { it.profile }) != profileId) return@withLock null
        healthLink = profileId
        VitalsSyncContext(profileId, vitalsSyncGeneration, records.getValue(profileId).vitals.lastSync, zone)
    }

    override suspend fun replaceVitals(profileId: String, vitals: ProfileVitals) = mutex.withLock {
        val data = records.getValue(profileId)
        if (HealthLink.resolve(healthLink, records.values.map { it.profile }) == profileId) vitalsSyncGeneration += 1
        storedVitals[profileId] = StoredVitals(clinical = vitals.copy(lastSync = null))
        publish(records + (profileId to data.copy(vitals = vitals.copy(lastSync = null))))
    }

    override suspend fun captureVitalsSync(zone: TimeZone): VitalsSyncContext = mutex.withLock {
        val id = HealthLink.resolve(healthLink, records.values.map { it.profile }) ?: throw IllegalStateException("No profile to link")
        VitalsSyncContext(id, vitalsSyncGeneration, records.getValue(id).vitals.lastSync, zone)
    }

    override suspend fun ingestDeviceVitalsAndCapture(context: VitalsSyncContext, batch: VitalsBatch, now: Instant): VitalsSyncCommit = mutex.withLock {
        currentCoroutineContext().ensureActive()
        val id = HealthLink.resolve(healthLink, records.values.map { it.profile })
        check(id == context.profileId && vitalsSyncGeneration == context.generation) { "Device vitals sync is stale" }
        checkNotNull(id)
        val data = records.getValue(id)
        healthLink = id
        val updated = conservativeVitals(data.vitals, batch, now, context.zone)
        val oldStored = storedVitals[id] ?: StoredVitals()
        storedVitals[id] = oldStored.copy(clinical = updated, revision = oldStored.revision + 1)
        publish(records + (id to data.copy(vitals = updated)))
        vitalsSyncGeneration += 1
        VitalsSyncCommit(DeviceVitals.storedCount(updated, batch), VitalsSyncContext(id, vitalsSyncGeneration, updated.lastSync, context.zone))
    }

    override suspend fun readVitalsState(context: VitalsSyncContext): VitalsReadState = mutex.withLock {
        validateVitals(context)
        VitalsReadState(context, storedVitals[context.profileId]?.revision ?: 0, storedVitals[context.profileId]?.local ?: WeightLocalState())
    }

    private suspend fun validateVitals(context: VitalsSyncContext) {
        check(HealthLink.resolve(healthLink, records.values.map { it.profile }) == context.profileId && vitalsSyncGeneration == context.generation) { "Device vitals sync is stale" }
        currentCoroutineContext().ensureActive()
    }

    override suspend fun commitWeightPage(state: VitalsReadState, page: WeightPage): VitalsWriteOutcome = commitVitals(state) { old ->
        val result = WeightChanges.reduce(old.clinical, old.local, page)
        old.copy(clinical = result.vitals, local = result.local) to result.storedCount
    }

    override suspend fun commitAggregateWindow(state: VitalsReadState, window: AggregateWindow): VitalsWriteOutcome {
        require(window.zone == state.context.zone) { "Aggregate zone changed" }
        return commitVitals(state) { old ->
            val clinical = DeviceVitals.ingestAggregates(old.clinical, window)
            old.copy(clinical = clinical) to (window.steps.distinctBy { it.date }.count { item -> clinical.steps.any { it.date == item.date } } + window.heartRate.distinctBy { it.start }.count { item -> clinical.heartRateHourly.any { it.start == item.start } }) }
    }

    private suspend fun commitVitals(state: VitalsReadState, fold: (StoredVitals) -> Pair<StoredVitals, Int>): VitalsWriteOutcome = mutex.withLock {
        try { validateVitals(state.context) } catch (_: IllegalStateException) { return@withLock VitalsWriteOutcome.Rejected("stale") }
        val id = state.context.profileId
        val old = (storedVitals[id] ?: StoredVitals()).copy(clinical = records.getValue(id).vitals)
        vitalsSyncGeneration += 1 // Every attempt consumes this generation, including rejected folds.
        if (state.revision != old.revision) return@withLock VitalsWriteOutcome.Rejected("revision")
        val folded = try { fold(old) }
            catch (_: IllegalArgumentException) { return@withLock VitalsWriteOutcome.Rejected("invalid page") }
        val next = folded.first.copy(revision = old.revision + 1, commitId = kotlin.uuid.Uuid.random().toString())
        storedVitals[id] = next
        healthLink = id
        publish(records + (id to records.getValue(id).copy(vitals = next.clinical)))
        VitalsWriteOutcome.Committed(next.commitId, next.revision, folded.second,
            VitalsReadState(VitalsSyncContext(id, vitalsSyncGeneration, next.clinical.lastSync, state.context.zone), next.revision, next.local))
    }

    override suspend fun restoreProfileAndVitals(data: ProfileData): Boolean = mutex.withLock {
        require(data.isInternallyConsistent())
        if (HealthLink.resolve(healthLink, records.values.map { it.profile }) == data.profile.profileId) vitalsSyncGeneration += 1
        val clinical = data.vitals.copy(lastSync = null)
        storedVitals[data.profile.profileId] = StoredVitals(clinical = clinical)
        publish(records + (data.profile.profileId to data.copy(vitals = clinical)))
        kotlin.coroutines.coroutineContext[RestoreRecordWrite]?.mayBeStored = true
        true
    }

    override suspend fun withValidatedVitalsSync(context: VitalsSyncContext, action: suspend () -> Unit) = mutex.withLock {
        val id = HealthLink.resolve(healthLink, records.values.map { it.profile })
        check(id == context.profileId && vitalsSyncGeneration == context.generation) { "Device vitals sync is stale" }
        currentCoroutineContext().ensureActive()
        action()
    }

    private suspend fun mutate(profileId: String, transform: (ProfileData) -> ProfileData) = mutex.withLock {
        val updated = transform(records.getValue(profileId))
        publish(records + (profileId to updated))
    }

    private fun publish(updated: Map<String, ProfileData>) {
        if (HealthLink.resolve(healthLink, records.values.map { it.profile }) != HealthLink.resolve(healthLink, updated.values.map { it.profile })) {
            vitalsSyncGeneration += 1
        }
        records = updated
        mutableProfiles.value = updated.values.map { it.profile }.sortedBy { it.displayName }
    }
}

/** Compatibility entry for native/legacy payloads: omission never removes weight, and local progress is untouched. */
internal fun conservativeVitals(stored: ProfileVitals, batch: VitalsBatch, now: Instant, zone: TimeZone): ProfileVitals {
    val aggregate = if (batch.markSynced) DeviceVitals.ingestAggregates(stored,
        AggregateWindow(batch.replaceFrom, batch.steps, batch.heartRate, now, zone)) else stored.copy(
            // Legacy merge data is not a completed provider query and supplies no absence/replacement authority.
            steps = (stored.steps + batch.steps.filter { app.vezuna.domain.StepsDay.ofOrNull(it.date, it.count) != null }).associateBy { it.date }.values.toList(),
            heartRateHourly = (stored.heartRateHourly + batch.heartRate.filter { app.vezuna.domain.HeartRateBucket.ofOrNull(it.start, it.min, it.avg, it.max) != null }).associateBy { it.start }.values.toList(),
        )
    val known = (stored.weights.map { it.recordId } + stored.samples.map { it.recordId }).toSet()
    val additions = batch.weights.filter { it.recordId !in known && app.vezuna.domain.WeightReading.ofOrNull(it.recordId, it.time, it.kg) != null }.distinctBy { it.recordId }
    return aggregate.copy(weights = stored.weights + additions)
}
