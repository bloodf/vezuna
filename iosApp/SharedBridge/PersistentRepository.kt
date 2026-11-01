package app.vezuna.ios

import app.vezuna.data.VezunaRepository
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
import app.vezuna.security.CipherEnvelope
import app.vezuna.security.SecureVault
import app.vezuna.security.VaultFailure
import app.vezuna.security.VaultUnreadableException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A write-through repository. Every successful mutation is encrypted before it becomes visible. */
class PersistentVezunaRepository private constructor(
    private val vault: SecureVault,
    private val json: Json,
) : VezunaRepository {
    private val mutex = Mutex()
    private val records = mutableMapOf<String, ProfileData>()
    private val mutableProfiles = MutableStateFlow<List<Profile>>(emptyList())
    private var selectedProfileId: String? = null
    var changeObserver: (() -> Unit)? = null
    override val profiles: StateFlow<List<Profile>> = mutableProfiles.asStateFlow()

    private suspend fun load() = mutex.withLock {
        try {
            loadLocked()
        } catch (error: VaultUnreadableException) {
            throw error
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            throw VaultUnreadableException(VaultFailure.CORRUPT, error)
        }
    }

    private suspend fun loadLocked() {
        selectedProfileId = vault.read(SELECTION_KEY)?.decodeToString()?.takeIf { it.isNotBlank() }
        val index = readEncrypted<List<Profile>>(INDEX_KEY, INDEX_ASSOCIATED_DATA, ListSerializer(Profile.serializer())).orEmpty()
        index.forEach { profile ->
            val data = readEncrypted(profileKey(profile.profileId), profileAssociatedData(profile.profileId), ProfileData.serializer())
                ?: error("Encrypted profile records are missing")
            require(data.profile == profile) { "Encrypted profile index does not match its records" }
            records[profile.profileId] = data
        }
        publishProfiles()
    }

    override suspend fun createProfile(profile: Profile) = mutex.withLock {
        require(!records.containsKey(profile.profileId)) { "Profile already exists" }
        val data = ProfileData(profile = profile)
        val newIndex = (records.values.map { it.profile } + profile).sortedBy { it.displayName }
        persist(data)
        writeEncrypted(INDEX_KEY, INDEX_ASSOCIATED_DATA, ListSerializer(Profile.serializer()), newIndex)
        records[profile.profileId] = data
        publishProfiles()
        changeObserver?.invoke()
        Unit
    }

    override suspend fun readProfile(profileId: String): ProfileData = mutex.withLock {
        val data = records.getValue(profileId)
        if (selectedProfileId != profileId) {
            vault.write(SELECTION_KEY, profileId.encodeToByteArray())
            selectedProfileId = profileId
            publishProfiles()
        }
        data
    }

    suspend fun peekProfile(profileId: String): ProfileData = mutex.withLock { records.getValue(profileId) }

    suspend fun addImport(document: SourceDocument, report: LabReport, candidates: List<OcrCandidate>) = mutex.withLock {
        val data = records.getValue(document.profileId)
        require(report.profileId == document.profileId && report.sourceDocumentId == document.documentId)
        require(candidates.isNotEmpty() && candidates.all {
            it.profileId == document.profileId && it.sourceDocumentId == document.documentId
        })
        require(data.documents.none { it.documentId == document.documentId })
        require(data.reports.none { it.reportId == report.reportId })
        require(candidates.map { it.candidateId }.distinct().size == candidates.size)
        commit(data.copy(
            documents = data.documents + document,
            reports = data.reports + report,
            ocrCandidates = data.ocrCandidates + candidates,
        ))
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
        require(data.documents.any { it.documentId == candidate.sourceDocumentId })
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
        commit(data.copy(observations = data.observations + observation))
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
        require(data.doseEvents.none { it.eventId == event.eventId })
        data.copy(doseEvents = data.doseEvents + event)
    }

    override suspend fun addChatMessage(message: ChatMessage) = mutate(message.profileId) { data ->
        data.copy(chat = data.chat + message)
    }

    private suspend fun mutate(profileId: String, transform: (ProfileData) -> ProfileData) = mutex.withLock {
        commit(transform(records.getValue(profileId)))
    }

    private suspend fun commit(data: ProfileData) {
        persist(data)
        records[data.profile.profileId] = data
        changeObserver?.invoke()
    }

    private suspend fun persist(data: ProfileData) {
        writeEncrypted(profileKey(data.profile.profileId), profileAssociatedData(data.profile.profileId), ProfileData.serializer(), data)
    }

    private suspend fun <T> writeEncrypted(
        key: String,
        associatedData: String,
        serializer: kotlinx.serialization.SerializationStrategy<T>,
        value: T,
    ) {
        val plain = json.encodeToString(serializer, value).encodeToByteArray()
        val envelope = vault.encrypt(plain, associatedData.encodeToByteArray())
        vault.write(key, json.encodeToString(CipherEnvelope.serializer(), envelope).encodeToByteArray())
    }

    private suspend fun <T> readEncrypted(
        key: String,
        associatedData: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T? {
        val stored = vault.read(key) ?: return null
        val envelope = json.decodeFromString(CipherEnvelope.serializer(), stored.decodeToString())
        val plain = vault.decrypt(envelope)
        // Associated data is authenticated by the vault envelope. Reject a mismatched payload before decoding.
        if (envelope.associatedData.decodeToString() != associatedData) error("Vault associated data mismatch")
        return json.decodeFromString(deserializer, plain.decodeToString())
    }

    private fun publishProfiles() {
        mutableProfiles.value = records.values.map { it.profile }.sortedWith(
            compareByDescending<Profile> { it.profileId == selectedProfileId }.thenBy { it.displayName },
        )
    }

    companion object {
        suspend fun open(vault: SecureVault, json: Json = Json { encodeDefaults = true }): PersistentVezunaRepository =
            PersistentVezunaRepository(vault, json).also { it.load() }

        private const val INDEX_KEY = "repository-profile-index-v1"
        private const val INDEX_ASSOCIATED_DATA = "Vezuna repository index v1"
        private const val SELECTION_KEY = "repository-selected-profile-v1"
        private fun profileKey(profileId: String) = "repository-profile-$profileId-v1"
        private fun profileAssociatedData(profileId: String) = "Vezuna repository profile v1:$profileId"
    }
}
