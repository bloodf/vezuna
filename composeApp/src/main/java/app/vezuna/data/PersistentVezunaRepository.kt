package app.vezuna.data

import app.vezuna.domain.ChatMessage
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
import app.vezuna.domain.SourceDocument
import app.vezuna.domain.isInternallyConsistent
import app.vezuna.domain.vaultKeys
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
import app.vezuna.security.CipherEnvelope
import app.vezuna.security.SecureVault
import app.vezuna.security.VaultFailure
import app.vezuna.security.VaultUnreadableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.logging.Level
import java.util.logging.Logger

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
    private var corruptCurrentPending = false
    private val mutableProfiles = MutableStateFlow<List<Profile>>(emptyList())
    private val mutableRevision = MutableStateFlow(0L)

    private val mutableOpenedFromPrevious = MutableStateFlow(false)

    /** True when the last load had to use the previous generation, so recent changes may be missing. */
    val openedFromPreviousGeneration: StateFlow<Boolean> = mutableOpenedFromPrevious.asStateFlow()

    fun acknowledgePreviousGeneration() { mutableOpenedFromPrevious.value = false }

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

    override suspend fun updateProfile(profile: Profile) = mutate(profile.profileId) { it.withProfile(profile) }

    override suspend fun deleteProfile(profileId: String) = mutex.withLock {
        ensureInitialized()
        val removed = snapshot.records[profileId] ?: throw IllegalArgumentException("Profile not found")
        commit(snapshot.copy(records = snapshot.records - profileId))
        removed.vaultKeys().forEach { vault.delete(it) }
    }

    override suspend fun updateDocument(document: SourceDocument) = mutate(document.profileId) { it.withDocument(document) }

    override suspend fun deleteDocument(profileId: String, documentId: String) = mutate(profileId) { it.withoutDocument(documentId) }

    override suspend fun updateReport(report: LabReport) = mutate(report.profileId) { it.withReport(report) }

    override suspend fun deleteReport(profileId: String, reportId: String) = mutate(profileId) { it.withoutReport(reportId) }

    override suspend fun updateObservation(observation: LabObservation) = mutate(observation.profileId) { it.withObservation(observation) }

    override suspend fun deleteObservation(profileId: String, observationId: String) = mutate(profileId) { it.withoutObservation(observationId) }

    override suspend fun updateMedication(medication: Medication) = mutate(medication.profileId) { it.withMedication(medication) }

    override suspend fun deleteMedication(profileId: String, medicationId: String) = mutate(profileId) { it.withoutMedication(medicationId) }

    override suspend fun updateSchedule(schedule: DoseSchedule) = mutate(schedule.profileId) { it.withSchedule(schedule) }

    override suspend fun deleteSchedule(profileId: String, scheduleId: String) = mutate(profileId) { it.withoutSchedule(scheduleId) }

    override suspend fun updateChatMessage(message: ChatMessage) = mutate(message.profileId) { it.withChatMessage(message) }

    override suspend fun deleteChatMessage(profileId: String, messageId: String) = mutate(profileId) { it.withoutChatMessage(messageId) }

    override suspend fun restoreProfile(data: ProfileData) = mutex.withLock {
        ensureInitialized()
        require(data.isInternallyConsistent()) { "Backup records are inconsistent" }
        val previous = snapshot.records[data.profile.profileId]
        commit(snapshot.with(data))
        // Files orphaned by the replacement go only after the restored snapshot is durable.
        ((previous?.vaultKeys() ?: emptySet()) - data.vaultKeys()).forEach { vault.delete(it) }
    }

    private suspend fun mutate(transform: () -> Snapshot) = mutex.withLock {
        ensureInitialized()
        commit(transform())
    }

    private suspend fun mutate(profileId: String, transform: (ProfileData) -> ProfileData) = mutex.withLock {
        ensureInitialized()
        val current = snapshot.records.getValue(profileId)
        val updated = transform(current)
        commit(snapshot.with(updated))
        // Source files orphaned by a cascading delete are removed only after the new snapshot is durable.
        (current.vaultKeys() - updated.vaultKeys()).forEach { vault.delete(it) }
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
        if (corruptCurrentPending) {
            // If this throws the write fails, but the corrupt file is never rotated over the good previous copy.
            vault.quarantineCurrent(STORAGE_KEY)
            corruptCurrentPending = false
        }
        val plaintext = json.encodeToString(Snapshot.serializer(), updated).encodeToByteArray()
        val envelope = vault.encrypt(plaintext, ASSOCIATED_DATA)
        vault.write(STORAGE_KEY, json.encodeToString(CipherEnvelope.serializer(), envelope).encodeToByteArray())
    }

    private suspend fun restore() {
        snapshot = decodeSnapshot(vault.read(STORAGE_KEY))
        mutableProfiles.value = snapshot.records.values.map { it.profile }.sortedBy { it.displayName }
        initialized = true
    }

    /** Current generation first; only a corrupt one falls back to the previous generation. */
    private suspend fun decodeSnapshot(current: ByteArray?): Snapshot = try {
        decode(current)
    } catch (error: CancellationException) {
        throw error
    } catch (error: VaultUnreadableException) {
        if (error.failure != VaultFailure.CORRUPT) throw error
        decodePrevious(error)
    } catch (error: Exception) {
        decodePrevious(VaultUnreadableException(VaultFailure.CORRUPT, error))
    }

    private suspend fun decodePrevious(failure: VaultUnreadableException): Snapshot {
        val previous = vault.readPrevious(STORAGE_KEY) ?: throw failure
        val restored = try {
            decode(previous)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw failure
        }
        // Keep the damaged file as evidence and out of the rotation that would overwrite the good previous copy.
        try {
            vault.quarantineCurrent(STORAGE_KEY)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Still open with the good previous data; the first write retries before anything can be rotated.
            logger.log(Level.WARNING, "Could not quarantine corrupt vault file", error)
            corruptCurrentPending = true
        }
        mutableOpenedFromPrevious.value = true
        return restored
    }

    private suspend fun decode(encoded: ByteArray?): Snapshot {
        if (encoded == null) return Snapshot()
        val envelope = json.decodeFromString(CipherEnvelope.serializer(), encoded.decodeToString())
        val plaintext = vault.decrypt(envelope)
        return json.decodeFromString(Snapshot.serializer(), plaintext.decodeToString()).also { restored ->
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
        }.let { restored ->
            // Older builds could store several terminal events for one dose; keep one.
            restored.copy(records = restored.records.mapValues { (_, data) -> data.copy(doseEvents = DoseEvents.collapse(data.doseEvents)) })
        }
    }

    /** Called once by the Application before any controller can observe profiles. */
    suspend fun load() = mutex.withLock {
        if (!initialized) restore()
    }

    /**
     * Explicit user reset: keeps the unreadable files in a quarantine folder, drops a dead key, and starts empty.
     * Never deletes the evidence; refuses when the vault cannot preserve it.
     */
    suspend fun resetAfterFailure() = mutex.withLock {
        check(vault.quarantineAll()) { "Vault cannot preserve unreadable files" }
        vault.discardUnusableKey()
        initialized = false
        restore()
    }

    private companion object {
        val logger: Logger = Logger.getLogger("PersistentVezunaRepository")
        const val STORAGE_KEY = "repository-v1"
        val ASSOCIATED_DATA = "Vezuna repository v1".encodeToByteArray()
    }
}
