package app.vezuna.ios

import app.vezuna.data.VezunaRepository
import app.vezuna.domain.ChatMessage
import app.vezuna.domain.ClinicalDuplicateDetector
import app.vezuna.domain.DeviceVitals
import app.vezuna.domain.HealthLink
import app.vezuna.domain.VitalsBatch
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
import app.vezuna.health.VitalsStore
import app.vezuna.security.CipherEnvelope
import app.vezuna.security.SecureVault
import app.vezuna.security.VaultFailure
import app.vezuna.security.VaultUnreadableException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A write-through repository. Every successful mutation is encrypted before it becomes visible. */
class PersistentVezunaRepository private constructor(
    private val vault: SecureVault,
    private val json: Json,
) : VezunaRepository {
    private val mutex = Mutex()
    private val records = mutableMapOf<String, ProfileData>()
    private val vitalsStore = VitalsStore(vault, json)
    // Vitals live in their own vault files; `records` always carry empty vitals so profile writes never rewrite them.
    private val vitals = mutableMapOf<String, ProfileVitals>()
    private val mutableProfiles = MutableStateFlow<List<Profile>>(emptyList())
    private var selectedProfileId: String? = null
    private var healthLinkId: String? = null
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
        healthLinkId = vault.read(HEALTH_LINK_KEY)?.decodeToString()?.takeIf { it.isNotBlank() }
        val index = readEncrypted<List<Profile>>(INDEX_KEY, INDEX_ASSOCIATED_DATA, ListSerializer(Profile.serializer())).orEmpty()
        var indexStale = false
        index.forEach { profile ->
            val data = readEncrypted(profileKey(profile.profileId), profileAssociatedData(profile.profileId), ProfileData.serializer())
                ?: error("Encrypted profile records are missing")
            // The record is authoritative: updateProfile writes it before the index, so a crash can leave the index behind.
            if (data.profile != profile) indexStale = true
            val stored = vitalsStore.load(profile.profileId)
            // Vitals saved inside the profile file by an earlier build move to their own file.
            val loaded = stored ?: data.vitals.migrated().takeUnless { it.isEmpty }?.also { vitalsStore.save(profile.profileId, it) }
            if (loaded != null) vitals[profile.profileId] = loaded
            records[profile.profileId] = data.copy(doseEvents = DoseEvents.collapse(data.doseEvents), vitals = ProfileVitals())
        }
        if (indexStale) {
            writeEncrypted(
                INDEX_KEY, INDEX_ASSOCIATED_DATA, ListSerializer(Profile.serializer()),
                records.values.map { it.profile }.sortedBy { it.displayName },
            )
        }
        vitalsStore.sweep(records.keys)
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
        val data = records.getValue(profileId).withVitals()
        if (selectedProfileId != profileId) {
            vault.write(SELECTION_KEY, profileId.encodeToByteArray())
            selectedProfileId = profileId
            publishProfiles()
        }
        data
    }

    suspend fun peekProfile(profileId: String): ProfileData = mutex.withLock { records.getValue(profileId).withVitals() }

    override suspend fun addDocument(document: SourceDocument) = mutate(document.profileId) { data ->
        require(data.documents.none { it.documentId == document.documentId })
        data.copy(documents = data.documents + document)
    }

    override suspend fun addReport(report: LabReport) = mutate(report.profileId) { data ->
        require(report.sourceDocumentId == null || data.documents.any { it.documentId == report.sourceDocumentId }) { "Source document must exist in this profile" }
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
        data.copy(doseEvents = DoseEvents.merge(data.doseEvents, event))
    }

    override suspend fun addChatMessage(message: ChatMessage) = mutate(message.profileId) { data ->
        data.copy(chat = data.chat + message)
    }

    override suspend fun updateProfile(profile: Profile) = mutex.withLock {
        val updated = records.getValue(profile.profileId).withProfile(profile)
        val newIndex = (records.values.map { it.profile }.filter { it.profileId != profile.profileId } + profile).sortedBy { it.displayName }
        persist(updated)
        writeEncrypted(INDEX_KEY, INDEX_ASSOCIATED_DATA, ListSerializer(Profile.serializer()), newIndex)
        records[profile.profileId] = updated
        publishProfiles()
        changeObserver?.invoke()
        Unit
    }

    override suspend fun deleteProfile(profileId: String) = mutex.withLock {
        val removed = records[profileId] ?: throw IllegalArgumentException("Profile not found")
        val newIndex = records.values.map { it.profile }.filter { it.profileId != profileId }.sortedBy { it.displayName }
        // Index first: a crash afterwards leaves only unreferenced encrypted files, never a dangling index entry.
        writeEncrypted(INDEX_KEY, INDEX_ASSOCIATED_DATA, ListSerializer(Profile.serializer()), newIndex)
        records.remove(profileId)
        vitals.remove(profileId)
        if (healthLinkId == profileId) {
            val next = HealthLink.resolve(null, records.values.map { it.profile })
            if (next == null) vault.delete(HEALTH_LINK_KEY) else vault.write(HEALTH_LINK_KEY, next.encodeToByteArray())
            healthLinkId = next
        }
        if (selectedProfileId == profileId) {
            vault.delete(SELECTION_KEY)
            selectedProfileId = null
        }
        vault.delete(profileKey(profileId))
        vitalsStore.delete(profileId)
        removed.vaultKeys().forEach { vault.delete(it) }
        publishProfiles()
        changeObserver?.invoke()
        Unit
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
        require(data.isInternallyConsistent()) { "Backup records are inconsistent" }
        val stored = data.copy(vitals = ProfileVitals())
        val previous = records[data.profile.profileId]
        val newIndex = (records.values.map { it.profile }.filter { it.profileId != data.profile.profileId } + data.profile).sortedBy { it.displayName }
        persist(stored)
        writeEncrypted(INDEX_KEY, INDEX_ASSOCIATED_DATA, ListSerializer(Profile.serializer()), newIndex)
        records[data.profile.profileId] = stored
        publishProfiles()
        ((previous?.vaultKeys() ?: emptySet()) - data.vaultKeys()).forEach { vault.delete(it) }
        changeObserver?.invoke()
        Unit
    }

    private suspend fun mutate(profileId: String, transform: (ProfileData) -> ProfileData) = mutex.withLock {
        val current = records.getValue(profileId)
        val updated = transform(current)
        commit(updated)
        // Source files orphaned by a cascading delete are removed only after the new records are durable.
        (current.vaultKeys() - updated.vaultKeys()).forEach { vault.delete(it) }
    }

    override suspend fun linkedHealthProfileId(): String? = mutex.withLock {
        HealthLink.resolve(healthLinkId, records.values.map { it.profile })
    }

    override suspend fun setLinkedHealthProfile(profileId: String) = mutex.withLock {
        require(records.containsKey(profileId)) { "Profile not found" }
        persistHealthLink(profileId)
    }

    override suspend fun replaceVitals(profileId: String, vitals: ProfileVitals) = mutex.withLock {
        require(records.containsKey(profileId)) { "Profile not found" }
        vitalsStore.save(profileId, vitals)
        this.vitals[profileId] = vitals
    }

    override suspend fun ingestDeviceVitals(batch: VitalsBatch, now: Instant): Int = mutex.withLock {
        val id = HealthLink.resolve(healthLinkId, records.values.map { it.profile }) ?: throw IllegalStateException("No profile to link")
        val updated = DeviceVitals.ingest(vitals[id] ?: ProfileVitals(), batch, now, TimeZone.currentSystemDefault())
        // Only the vitals file is rewritten, never the profile file; the link is pinned once the data is durable.
        vitalsStore.save(id, updated)
        vitals[id] = updated
        if (healthLinkId != id) persistHealthLink(id)
        DeviceVitals.storedCount(updated, batch)
    }

    private fun ProfileData.withVitals() = copy(vitals = this@PersistentVezunaRepository.vitals[profile.profileId] ?: ProfileVitals())

    private suspend fun persistHealthLink(profileId: String) {
        vault.write(HEALTH_LINK_KEY, profileId.encodeToByteArray())
        healthLinkId = profileId
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
        private const val HEALTH_LINK_KEY = "repository-health-link-v1"
        private fun profileKey(profileId: String) = "repository-profile-$profileId-v1"
        private fun profileAssociatedData(profileId: String) = "Vezuna repository profile v1:$profileId"
    }
}
