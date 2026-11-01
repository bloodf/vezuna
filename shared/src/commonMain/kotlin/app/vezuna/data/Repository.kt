package app.vezuna.data

import app.vezuna.domain.ChatMessage
import app.vezuna.domain.ClinicalDuplicateDetector
import app.vezuna.domain.DoseEvent
import app.vezuna.domain.LabObservation
import app.vezuna.domain.LabReport
import app.vezuna.domain.Medication
import app.vezuna.domain.OcrCandidate
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.SourceDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
}

class InMemoryVezunaRepository : VezunaRepository {
    private val mutex = Mutex()
    private val records = mutableMapOf<String, MutableProfileRecords>()
    private val mutableProfiles = MutableStateFlow<List<Profile>>(emptyList())
    override val profiles = mutableProfiles.asStateFlow()

    override suspend fun createProfile(profile: Profile) = mutex.withLock {
        require(!records.containsKey(profile.profileId)) { "Profile already exists" }
        records[profile.profileId] = MutableProfileRecords(profile)
        mutableProfiles.value = records.values.map { it.profile }.sortedBy { it.displayName }
    }

    override suspend fun readProfile(profileId: String): ProfileData = mutex.withLock {
        records.getValue(profileId).snapshot()
    }

    override suspend fun addDocument(document: SourceDocument) = mutate(document.profileId) {
        require(documents.none { it.documentId == document.documentId })
        documents += document
    }

    override suspend fun addReport(report: LabReport) = mutate(report.profileId) {
        require(documents.any { it.documentId == report.sourceDocumentId }) { "Source document must exist in this profile" }
        require(reports.none { it.reportId == report.reportId })
        reports += report
    }

    override suspend fun addCandidate(candidate: OcrCandidate) = mutate(candidate.profileId) {
        require(documents.any { it.documentId == candidate.sourceDocumentId }) { "Source document must exist in this profile" }
        require(ocrCandidates.none { it.candidateId == candidate.candidateId })
        ocrCandidates += candidate
    }

    override suspend fun updateCandidate(candidate: OcrCandidate) = mutate(candidate.profileId) {
        val index = ocrCandidates.indexOfFirst { it.candidateId == candidate.candidateId }
        require(index >= 0)
        ocrCandidates[index] = candidate
    }

    override suspend fun addObservation(observation: LabObservation): Boolean = mutex.withLock {
        val current = records.getValue(observation.profileId)
        require(current.reports.any { it.reportId == observation.reportId }) { "Report must exist in this profile" }
        if (current.observations.any { ClinicalDuplicateDetector.areDuplicates(it, observation) }) return@withLock false
        require(current.observations.none { it.observationId == observation.observationId })
        current.observations += observation
        true
    }

    override suspend fun addMedication(medication: Medication) = mutate(medication.profileId) {
        require(medication.schedules.all { it.profileId == medication.profileId && it.medicationId == medication.medicationId })
        require(medications.none { it.medicationId == medication.medicationId })
        medications += medication
    }

    override suspend fun addDoseEvent(event: DoseEvent) = mutate(event.profileId) {
        require(medications.any { medication -> medication.medicationId == event.medicationId && medication.schedules.any { it.scheduleId == event.scheduleId } })
        require(doseEvents.none { it.eventId == event.eventId })
        doseEvents += event
    }

    override suspend fun addChatMessage(message: ChatMessage) = mutate(message.profileId) {
        chat += message
    }

    private suspend inline fun mutate(profileId: String, crossinline block: MutableProfileRecords.() -> Unit) =
        mutex.withLock { records.getValue(profileId).block() }

    private class MutableProfileRecords(val profile: Profile) {
        val documents = mutableListOf<SourceDocument>()
        val reports = mutableListOf<LabReport>()
        val observations = mutableListOf<LabObservation>()
        val ocrCandidates = mutableListOf<OcrCandidate>()
        val medications = mutableListOf<Medication>()
        val doseEvents = mutableListOf<DoseEvent>()
        val chat = mutableListOf<ChatMessage>()

        fun snapshot() = ProfileData(
            profile = profile,
            documents = documents.toList(),
            reports = reports.toList(),
            observations = observations.toList(),
            ocrCandidates = ocrCandidates.toList(),
            medications = medications.toList(),
            doseEvents = doseEvents.toList(),
            chat = chat.toList(),
        )
    }
}
