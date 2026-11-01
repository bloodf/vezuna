package app.vezuna.data

import app.vezuna.domain.ChatMessage
import app.vezuna.domain.DeviceVitals
import app.vezuna.domain.HealthLink
import app.vezuna.domain.VitalsBatch
import app.vezuna.domain.ClinicalDuplicateDetector
import app.vezuna.domain.DoseEvent
import app.vezuna.domain.DoseEvents
import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.LabObservation
import app.vezuna.domain.LabReport
import app.vezuna.domain.Medication
import app.vezuna.domain.OcrCandidate
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.ProfileVitals
import app.vezuna.domain.SourceDocument
import app.vezuna.domain.isInternallyConsistent
import app.vezuna.domain.withChatMessage
import app.vezuna.domain.withDocument
import app.vezuna.domain.withMedication
import app.vezuna.domain.withObservation
import app.vezuna.domain.withProfile
import app.vezuna.domain.withReport
import app.vezuna.domain.withSchedule
import app.vezuna.domain.withoutChatMessage
import app.vezuna.domain.withoutDocument
import app.vezuna.domain.withoutMedication
import app.vezuna.domain.withoutObservation
import app.vezuna.domain.withoutReport
import app.vezuna.domain.withoutSchedule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

interface VezunaRepository {
    val profiles: StateFlow<List<Profile>>
    suspend fun createProfile(profile: Profile)
    suspend fun readProfile(profileId: String): ProfileData
    suspend fun addDocument(document: SourceDocument)
    suspend fun addReport(report: LabReport)
    suspend fun addCandidate(candidate: OcrCandidate)
    suspend fun updateCandidate(candidate: OcrCandidate)
    suspend fun addObservation(observation: LabObservation): Boolean
    suspend fun addMedication(medication: Medication)
    suspend fun addDoseEvent(event: DoseEvent)
    suspend fun addChatMessage(message: ChatMessage)

    /** Deleting a profile removes all its records and stored source files. */
    suspend fun updateProfile(profile: Profile)
    suspend fun deleteProfile(profileId: String)
    suspend fun updateDocument(document: SourceDocument)
    /** Cascades to the document's reports, observations, candidates and stored source file. */
    suspend fun deleteDocument(profileId: String, documentId: String)
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
    /** Merges the batch into the linked profile only; returns how many of its entries were actually stored. */
    suspend fun ingestDeviceVitals(batch: VitalsBatch, now: Instant): Int
    /** Atomically replaces the stored vitals of [profileId] (restore); [ProfileVitals.lastSync] is kept as provided. */
    suspend fun replaceVitals(profileId: String, vitals: ProfileVitals)
}


/** Restores a backup including its device vitals; the vitals replace whatever the profile had. */
suspend fun VezunaRepository.restoreWithVitals(data: ProfileData) {
    restoreProfile(data.copy(vitals = ProfileVitals()))
    replaceVitals(data.profile.profileId, data.vitals)
}

/** Volatile implementation used by tests and previews. */
class InMemoryVezunaRepository : VezunaRepository {
    private val mutex = Mutex()
    private var records = mapOf<String, ProfileData>()
    private var healthLink: String? = null
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

    override suspend fun addReport(report: LabReport) = mutate(report.profileId) { data ->
        require(data.documents.any { it.documentId == report.sourceDocumentId }) { "Source document must exist in this profile" }
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

    override suspend fun addChatMessage(message: ChatMessage) = mutate(message.profileId) { data ->
        data.copy(chat = data.chat + message)
    }

    override suspend fun updateChatMessage(message: ChatMessage) = mutate(message.profileId) { it.withChatMessage(message) }

    override suspend fun deleteChatMessage(profileId: String, messageId: String) = mutate(profileId) { it.withoutChatMessage(messageId) }

    override suspend fun restoreProfile(data: ProfileData) = mutex.withLock {
        require(data.isInternallyConsistent()) { "Backup records are inconsistent" }
        publish(records + (data.profile.profileId to data))
    }

    override suspend fun linkedHealthProfileId(): String? = mutex.withLock { HealthLink.resolve(healthLink, records.values.map { it.profile }) }

    override suspend fun setLinkedHealthProfile(profileId: String) = mutex.withLock {
        require(records.containsKey(profileId)) { "Profile not found" }
        healthLink = profileId
    }

    override suspend fun replaceVitals(profileId: String, vitals: ProfileVitals) = mutate(profileId) { it.copy(vitals = vitals) }

    override suspend fun ingestDeviceVitals(batch: VitalsBatch, now: Instant): Int = mutex.withLock {
        val id = HealthLink.resolve(healthLink, records.values.map { it.profile }) ?: throw IllegalStateException("No profile to link")
        val data = records.getValue(id)
        healthLink = id
        val updated = DeviceVitals.ingest(data.vitals, batch, now, TimeZone.currentSystemDefault())
        publish(records + (id to data.copy(vitals = updated)))
        DeviceVitals.storedCount(updated, batch)
    }

    private suspend fun mutate(profileId: String, transform: (ProfileData) -> ProfileData) = mutex.withLock {
        val updated = transform(records.getValue(profileId))
        publish(records + (profileId to updated))
    }

    private fun publish(updated: Map<String, ProfileData>) {
        records = updated
        mutableProfiles.value = updated.values.map { it.profile }.sortedBy { it.displayName }
    }
}
