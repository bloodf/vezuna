package app.vezuna.data

import app.vezuna.domain.ChatMessage
import app.vezuna.domain.ClinicalDuplicateDetector
import app.vezuna.domain.DoseEvent
import app.vezuna.domain.DoseEvents
import app.vezuna.domain.LabObservation
import app.vezuna.domain.LabReport
import app.vezuna.domain.Medication
import app.vezuna.domain.OcrCandidate
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.SourceDocument
import app.vezuna.security.CipherEnvelope
import app.vezuna.security.SecureVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Write-through, profile-isolated persistence. The plaintext snapshot never touches disk. */
class PersistentVezunaRepository(
    private val vault: SecureVault,
    private val json: Json = Json { encodeDefaults = true },
) : VezunaRepository {
    @Serializable
    private data class Snapshot(val records: Map<String, ProfileData> = emptyMap())

    private val mutex = Mutex()
    private var snapshot = Snapshot()
    private var initialized = false
    private val mutableProfiles = MutableStateFlow<List<Profile>>(emptyList())
    private val mutableRevision = MutableStateFlow(0L)

    override val profiles: StateFlow<List<Profile>> = mutableProfiles.asStateFlow()
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    override suspend fun createProfile(profile: Profile) = mutate {
        require(!snapshot.records.containsKey(profile.profileId)) { "Profile already exists" }
        require(profile.displayName.isNotBlank()) { "Profile name is required" }
        snapshot.copy(records = snapshot.records + (profile.profileId to ProfileData(profile)))
    }

    override suspend fun readProfile(profileId: String): ProfileData = mutex.withLock {
        ensureInitialized()
        snapshot.records.getValue(profileId)
    }

    override suspend fun addDocument(document: SourceDocument) = mutate(document.profileId) { data ->
        require(data.documents.none { it.documentId == document.documentId })
        data.copy(documents = data.documents + document)
    }

    override suspend fun addReport(report: LabReport) = mutate(report.profileId) { data ->
        require(data.documents.any { it.documentId == report.sourceDocumentId }) { "Source document must exist in this profile" }
        require(data.reports.none { it.reportId == report.reportId })
        data.copy(reports = data.reports + report)
    }

    override suspend fun addCandidate(candidate: OcrCandidate) = mutate(candidate.profileId) { data ->
        require(data.documents.any { it.documentId == candidate.sourceDocumentId }) { "Source document must exist in this profile" }
        require(data.ocrCandidates.none { it.candidateId == candidate.candidateId })
        data.copy(ocrCandidates = data.ocrCandidates + candidate)
    }

    override suspend fun updateCandidate(candidate: OcrCandidate) = mutate(candidate.profileId) { data ->
        val index = data.ocrCandidates.indexOfFirst { it.candidateId == candidate.candidateId }
        require(index >= 0)
        data.copy(ocrCandidates = data.ocrCandidates.toMutableList().apply { this[index] = candidate })
    }

    override suspend fun addObservation(observation: LabObservation): Boolean = mutex.withLock {
        ensureInitialized()
        val data = snapshot.records.getValue(observation.profileId)
        require(data.reports.any { it.reportId == observation.reportId }) { "Report must exist in this profile" }
        if (data.observations.any { ClinicalDuplicateDetector.areDuplicates(it, observation) }) return@withLock false
        require(data.observations.none { it.observationId == observation.observationId })
        commit(snapshot.with(data.copy(observations = data.observations + observation)))
        true
    }

    override suspend fun addMedication(medication: Medication) = mutate(medication.profileId) { data ->
        require(medication.schedules.all { it.profileId == medication.profileId && it.medicationId == medication.medicationId })
        require(data.medications.none { it.medicationId == medication.medicationId })
        data.copy(medications = data.medications + medication)
    }

    override suspend fun addDoseEvent(event: DoseEvent) = mutate(event.profileId) { data ->
        require(data.medications.any { medication ->
            medication.medicationId == event.medicationId && medication.schedules.any { it.scheduleId == event.scheduleId }
        })
        data.copy(doseEvents = DoseEvents.merge(data.doseEvents, event))
    }

    override suspend fun addChatMessage(message: ChatMessage) = mutate(message.profileId) { data ->
        require(data.chat.none { it.messageId == message.messageId })
        data.copy(chat = data.chat + message)
    }

    private suspend fun mutate(transform: () -> Snapshot) = mutex.withLock {
        ensureInitialized()
        commit(transform())
    }

    private suspend fun mutate(profileId: String, transform: (ProfileData) -> ProfileData) = mutex.withLock {
        ensureInitialized()
        val current = snapshot.records.getValue(profileId)
        commit(snapshot.with(transform(current)))
    }

    private fun Snapshot.with(data: ProfileData): Snapshot =
        copy(records = records + (data.profile.profileId to data))

    private fun ensureInitialized() {
        if (!initialized) error("Repository must be initialized before use")
    }

    private suspend fun commit(updated: Snapshot) {
        persist(updated)
        snapshot = updated
        mutableProfiles.value = updated.records.values.map { it.profile }.sortedBy { it.displayName }
        mutableRevision.value += 1
    }

    private suspend fun persist(updated: Snapshot) {
        val plaintext = json.encodeToString(Snapshot.serializer(), updated).encodeToByteArray()
        val envelope = vault.encrypt(plaintext, ASSOCIATED_DATA)
        vault.write(STORAGE_KEY, json.encodeToString(CipherEnvelope.serializer(), envelope).encodeToByteArray())
    }

    private suspend fun restore() {
        val encoded = vault.read(STORAGE_KEY)
        snapshot = if (encoded == null) {
            Snapshot()
        } else {
            val envelope = json.decodeFromString(CipherEnvelope.serializer(), encoded.decodeToString())
            val plaintext = vault.decrypt(envelope)
            json.decodeFromString(Snapshot.serializer(), plaintext.decodeToString()).also { restored ->
                restored.records.forEach { (profileId, data) ->
                    require(profileId == data.profile.profileId) { "Corrupt profile scope in repository" }
                    require(data.documents.all { it.profileId == profileId } &&
                        data.reports.all { it.profileId == profileId } &&
                        data.observations.all { it.profileId == profileId } &&
                        data.ocrCandidates.all { it.profileId == profileId } &&
                        data.medications.all { it.profileId == profileId } &&
                        data.doseEvents.all { it.profileId == profileId } &&
                        data.chat.all { it.profileId == profileId }) { "Corrupt cross-profile record in repository" }
                }
            }
        }
        mutableProfiles.value = snapshot.records.values.map { it.profile }.sortedBy { it.displayName }
        initialized = true
    }

    /** Called once by the Application before any controller can observe profiles. */
    suspend fun load() = mutex.withLock {
        if (!initialized) restore()
    }

    private companion object {
        const val STORAGE_KEY = "repository-v1"
        val ASSOCIATED_DATA = "Vezuna repository v1".encodeToByteArray()
    }
}
