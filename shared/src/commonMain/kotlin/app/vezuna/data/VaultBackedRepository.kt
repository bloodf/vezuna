package app.vezuna.data

import app.vezuna.domain.ChatMessage
import app.vezuna.domain.ClinicalDuplicateDetector
import app.vezuna.domain.DeviceVitals
import app.vezuna.domain.DoseEvent
import app.vezuna.domain.DoseEvents
import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.HealthLink
import app.vezuna.domain.LabObservation
import app.vezuna.domain.LabReport
import app.vezuna.domain.Medication
import app.vezuna.domain.OcrCandidate
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.ProfileVitals
import app.vezuna.domain.SourceDocument
import app.vezuna.domain.VitalsBatch
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
import app.vezuna.security.CipherEnvelopeCodec
import app.vezuna.security.SecureVault
import app.vezuna.security.VaultFailure
import app.vezuna.security.VaultUnreadableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The one write-through repository for Android and iOS. Every successful mutation is encrypted before it becomes visible.
 *
 * Layout in the vault: one encrypted file per profile plus an encrypted profile index that is written last, so a commit
 * rewrites only the profile it touched. Vitals live in their own files ([VitalsStore]). Plaintext never touches disk.
 *
 * Call [load] once before use. [persistSelection] keeps the last opened profile first in [profiles] (iOS); otherwise
 * profiles are sorted by name.
 */
class VaultBackedRepository(
    private val vault: SecureVault,
    private val json: Json = Json { encodeDefaults = true },
    private val persistSelection: Boolean = false,
    private val warn: (String, Throwable?) -> Unit = { _, _ -> },
) : VezunaRepository {
    /** The single-blob format older Android builds wrote; read once, migrated to per-profile files, then deleted. */
    @Serializable
    private data class LegacySnapshot(
        val records: Map<String, ProfileData> = emptyMap(),
        val healthLinkedProfileId: String? = null,
    )

    private val mutex = Mutex()
    private val records = mutableMapOf<String, ProfileData>()
    private val vitalsStore = VitalsStore(vault, json)
    // Vitals live in their own vault files; `records` always carry empty vitals so profile writes never rewrite them.
    private val vitals = mutableMapOf<String, ProfileVitals>()
    // Keys whose corrupt current file was replaced by the previous generation; quarantined before the next write of that key.
    private val pendingQuarantine = mutableSetOf<String>()
    // Index entries whose record is missing; carried forward in the index so their vitals and files are never swept.
    private val skipped = mutableMapOf<String, Profile>()
    // Ids of profiles the user deleted. Null when the tombstone file is unreadable: then nothing is recovered from orphans.
    private var deletedProfileIds: Set<String>? = emptySet()
    private var initialized = false
    private var selectedProfileId: String? = null
    private var healthLinkId: String? = null
    private val mutableProfiles = MutableStateFlow<List<Profile>>(emptyList())
    private val mutableRevision = MutableStateFlow(0L)
    private val mutableOpenedFromPrevious = MutableStateFlow(false)

    var changeObserver: (() -> Unit)? = null

    /** Profiles listed in the index whose record could not be found; hidden from [profiles] but never forgotten. */
    val unavailableProfiles: List<Profile> get() = skipped.values.toList()

    /** True when the last load had to use a previous generation, so recent changes may be missing. */
    val openedFromPreviousGeneration: StateFlow<Boolean> = mutableOpenedFromPrevious.asStateFlow()

    fun acknowledgePreviousGeneration() { mutableOpenedFromPrevious.value = false }

    override val profiles: StateFlow<List<Profile>> = mutableProfiles.asStateFlow()

    /** Increments after every change to profile records. */
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    /** Called once by the app before any controller can observe profiles. */
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

    override suspend fun createProfile(profile: Profile) = mutex.withLock {
        ensureInitialized()
        require(!records.containsKey(profile.profileId)) { "Profile already exists" }
        require(profile.displayName.isNotBlank()) { "Profile name is required" }
        val data = ProfileData(profile = profile)
        skipped.remove(profile.profileId)
        forgetDeleted(profile.profileId)
        persist(data)
        writeIndex(indexWith(data.profile))
        records[profile.profileId] = data
        changed()
    }

    override suspend fun readProfile(profileId: String): ProfileData = mutex.withLock {
        ensureInitialized()
        val data = records.getValue(profileId).withVitals()
        if (persistSelection && selectedProfileId != profileId) {
            vault.write(SELECTION_KEY, profileId.encodeToByteArray())
            selectedProfileId = profileId
            publishProfiles()
        }
        data
    }

    override suspend fun peekProfile(profileId: String): ProfileData = mutex.withLock {
        ensureInitialized()
        records.getValue(profileId).withVitals()
    }

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
        require(data.documents.any { it.documentId == candidate.sourceDocumentId }) { "Source document must exist in this profile" }
        require(data.ocrCandidates.none { it.candidateId == candidate.candidateId })
        data.copy(ocrCandidates = data.ocrCandidates + candidate)
    }

    override suspend fun updateCandidate(candidate: OcrCandidate) = mutate(candidate.profileId) { data ->
        require(data.ocrCandidates.any { it.candidateId == candidate.candidateId })
        data.copy(ocrCandidates = data.ocrCandidates.map { if (it.candidateId == candidate.candidateId) candidate else it })
    }

    override suspend fun addObservation(observation: LabObservation): Boolean = mutex.withLock {
        ensureInitialized()
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
        require(data.chat.none { it.messageId == message.messageId })
        data.copy(chat = data.chat + message)
    }

    override suspend fun updateProfile(profile: Profile) = mutex.withLock {
        ensureInitialized()
        val updated = records.getValue(profile.profileId).withProfile(profile)
        persist(updated)
        writeIndex(indexWith(profile))
        records[profile.profileId] = updated
        changed()
    }

    override suspend fun deleteProfile(profileId: String) = mutex.withLock {
        ensureInitialized()
        // A profile whose record is missing (see unavailableProfiles) can still be deleted, with its vitals.
        val removed = records[profileId]
        val unavailable = skipped[profileId]
        if (removed == null && unavailable == null) throw IllegalArgumentException("Profile not found")
        // Recorded before anything is removed: a record file that survives a failed delete is then never recovered as an orphan.
        recordDeleted(profileId)
        // Index first: a crash afterwards leaves only unreferenced encrypted files, never a dangling index entry.
        skipped.remove(profileId)
        try {
            writeIndex(records.values.map { it.profile }.filter { it.profileId != profileId })
        } catch (error: Throwable) {
            unavailable?.let { skipped[profileId] = it }
            throw error
        }
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
        removed?.vaultKeys()?.forEach { vault.delete(it) }
        // Last, so a failure above leaves the vitals in place and the next load's sweep retries.
        vitalsStore.delete(profileId)
        changed()
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
        // Vitals live in their own file; callers write them through replaceVitals (see restoreWithVitals).
        val stored = data.copy(vitals = ProfileVitals())
        val previous = records[data.profile.profileId]
        forgetDeleted(data.profile.profileId)
        persist(stored)
        // The record is durable from here on. Nothing below may throw for a profile that already existed: its index
        // entry is repaired on the next load, and callers must not roll back files the durable record points at.
        try {
            skipped.remove(data.profile.profileId)
            writeIndex(indexWith(data.profile))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (previous == null) throw error
            warn("Could not update the profile index after a restore", error)
        }
        records[data.profile.profileId] = stored
        // Files orphaned by the replacement go only after the restored record is durable; best effort.
        ((previous?.vaultKeys() ?: emptySet()) - data.vaultKeys()).forEach { key ->
            try {
                vault.delete(key)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                warn("Could not delete an orphaned original", error)
            }
        }
        changed()
    }

    override suspend fun linkedHealthProfileId(): String? = mutex.withLock {
        ensureInitialized()
        HealthLink.resolve(healthLinkId, records.values.map { it.profile })
    }

    override suspend fun setLinkedHealthProfile(profileId: String) = mutex.withLock {
        ensureInitialized()
        require(records.containsKey(profileId)) { "Profile not found" }
        persistHealthLink(profileId)
    }

    override suspend fun replaceVitals(profileId: String, vitals: ProfileVitals) = mutex.withLock {
        ensureInitialized()
        require(records.containsKey(profileId)) { "Profile not found" }
        vitalsStore.save(profileId, vitals)
        this.vitals[profileId] = vitals
    }

    override suspend fun ingestDeviceVitals(batch: VitalsBatch, now: Instant): Int = mutex.withLock {
        ensureInitialized()
        val id = HealthLink.resolve(healthLinkId, records.values.map { it.profile }) ?: throw IllegalStateException("No profile to link")
        val updated = DeviceVitals.ingest(vitals[id] ?: ProfileVitals(), batch, now, TimeZone.currentSystemDefault())
        // Only the vitals file is rewritten, never the profile file; the link is pinned once the data is durable.
        vitalsStore.save(id, updated)
        vitals[id] = updated
        if (healthLinkId != id) persistHealthLink(id)
        DeviceVitals.storedCount(updated, batch)
    }

    private fun ProfileData.withVitals() = copy(vitals = this@VaultBackedRepository.vitals[profile.profileId] ?: ProfileVitals())

    private fun ensureInitialized() {
        if (!initialized) error("Repository must be initialized before use")
    }

    private suspend fun mutate(profileId: String, transform: (ProfileData) -> ProfileData) = mutex.withLock {
        ensureInitialized()
        val current = records.getValue(profileId)
        val updated = transform(current)
        commit(updated)
        // Source files orphaned by a cascading delete are removed only after the new records are durable.
        (current.vaultKeys() - updated.vaultKeys()).forEach { vault.delete(it) }
    }

    private suspend fun commit(data: ProfileData) {
        persist(data)
        records[data.profile.profileId] = data
        changed()
    }

    private fun changed() {
        publishProfiles()
        mutableRevision.value += 1
        try {
            changeObserver?.invoke()
        } catch (error: Exception) {
            warn("Change observer failed", error)
        }
    }

    private suspend fun persistHealthLink(profileId: String) {
        vault.write(HEALTH_LINK_KEY, profileId.encodeToByteArray())
        healthLinkId = profileId
    }

    private fun publishProfiles() {
        val all = records.values.map { it.profile }
        mutableProfiles.value = if (persistSelection) {
            all.sortedWith(compareByDescending<Profile> { it.profileId == selectedProfileId }.thenBy { it.displayName })
        } else {
            all.sortedBy { it.displayName }
        }
    }

    private fun indexWith(profile: Profile): List<Profile> =
        (records.values.map { it.profile }.filter { it.profileId != profile.profileId } + profile).sortedBy { it.displayName }

    // ---- storage ----

    private suspend fun persist(data: ProfileData) {
        val document = StorageSchema.stamp(json.encodeToJsonElement(ProfileData.serializer(), data) as JsonObject)
        writeDocument(profileKey(data.profile.profileId), profileAssociatedData(data.profile.profileId), document)
    }

    private suspend fun writeIndex(profiles: List<Profile>) {
        val all = profiles + skipped.values.filter { s -> profiles.none { it.profileId == s.profileId } }
        val document = StorageSchema.stamp(JsonObject(mapOf(INDEX_PROFILES to json.encodeToJsonElement(ListSerializer(Profile.serializer()), all))))
        writeDocument(INDEX_KEY, INDEX_ASSOCIATED_DATA, document)
    }

    private suspend fun writeDocument(key: String, associatedData: String, document: JsonElement) {
        if (key in pendingQuarantine) {
            // If this throws the write fails, but the corrupt file is never rotated over the good previous copy.
            vault.quarantineCurrent(key)
            pendingQuarantine -= key
        }
        val plain = json.encodeToString(JsonElement.serializer(), document).encodeToByteArray()
        val envelope = vault.encrypt(plain, associatedData.encodeToByteArray())
        vault.write(key, CipherEnvelopeCodec.encode(envelope))
    }

    private suspend fun restore() {
        records.clear()
        vitals.clear()
        pendingQuarantine.clear()
        skipped.clear()
        mutableOpenedFromPrevious.value = false
        deletedProfileIds = readDeleted()
        selectedProfileId = vault.read(SELECTION_KEY)?.decodeToString()?.takeIf { it.isNotBlank() }
        healthLinkId = vault.read(HEALTH_LINK_KEY)?.decodeToString()?.takeIf { it.isNotBlank() }

        var index = readDocument(INDEX_KEY, INDEX_ASSOCIATED_DATA, ::parseIndex)
        var legacyPresent = false
        if (index == null) {
            val legacy = readDocument(LEGACY_KEY, LEGACY_ASSOCIATED_DATA, ::parseLegacy)
            if (legacy != null) {
                legacyPresent = true
                index = migrateLegacy(legacy)
            }
        } else {
            // Index exists, so it is authoritative; a leftover single blob would only keep deleted data alive.
            legacyPresent = vault.read(LEGACY_KEY) != null
        }

        var indexStale = false
        // Unlisted records that could not be read: left on disk, and their vitals must survive the sweep below.
        val unreadableUnlisted = mutableSetOf<String>()
        val listed = index.orEmpty().associateBy { it.profileId }
        // A previous-generation index is one write behind: records it does not list are recovered, unless the user deleted them.
        val extra = if (mutableOpenedFromPrevious.value) orphanIds(listed.keys) else emptyList()
        (listed.keys + extra).forEach { id ->
            val data = if (id in listed) {
                readDocument(profileKey(id), profileAssociatedData(id)) { parseProfile(id, it) }
            } else {
                readOrphan(id)
            }
            if (data == null) {
                if (id in listed && deletedProfileIds?.contains(id) == true) {
                    // Deleted by the user: the index entry is stale, not an unavailable profile.
                    indexStale = true
                    return@forEach
                }
                if (id !in listed) unreadableUnlisted += id
                // A crash or partial delete left an index entry without its record; the other profiles must still open.
                warn("Skipping profile $id: its record is missing", null)
                listed[id]?.let { skipped[id] = it }
                return@forEach
            }
            // The record is authoritative: updateProfile writes it before the index, so a crash can leave the index behind.
            if (data.profile != listed[id]) indexStale = true
            val stored = vitalsStore.load(id) { warn("Could not read stored vitals (${it::class.simpleName})", null) }
            // Vitals saved inside the profile record by an earlier build move to their own file.
            val loaded = stored ?: data.vitals.migrated().takeUnless { it.isEmpty }?.also { vitalsStore.save(id, it) }
            if (loaded != null) vitals[id] = loaded
            records[id] = data.copy(doseEvents = DoseEvents.collapse(data.doseEvents), vitals = ProfileVitals())
        }
        if (indexStale) writeIndex(records.values.map { it.profile }.sortedBy { it.displayName })
        // Vitals of skipped profiles stay until the user deletes the profile or its record reappears.
        vitalsStore.sweep(records.keys + skipped.keys + unreadableUnlisted)
        if (legacyPresent) {
            try {
                vault.delete(LEGACY_KEY)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                warn("Could not delete the migrated legacy repository file", error)
            }
        }
        publishProfiles()
        initialized = true
    }

    /**
     * Profile ids whose record file exists but which the loaded index does not list. Only reached for a previous-generation
     * index. Ids with vitals count too, and an id the user deleted never does. When the tombstones cannot be read nothing
     * new is recovered from record files; the vitals path keeps its earlier behavior.
     */
    private suspend fun orphanIds(listed: Set<String>): List<String> {
        val deleted = deletedProfileIds
        val withVitals = vitalsStore.ids().filter { it !in listed && it !in deleted.orEmpty() && vault.read(profileKey(it)) != null }
        if (deleted == null) return withVitals
        val stored = vault.listKeys().orEmpty().mapNotNull(::profileIdOf).filter { it !in listed && it !in deleted }
        return (withVitals + stored).distinct()
    }

    /** An unlisted record is optional: one that cannot be read is left untouched on disk and the vault still opens. */
    private suspend fun readOrphan(id: String): ProfileData? = try {
        readDocument(profileKey(id), profileAssociatedData(id)) { parseProfile(id, it) }
    } catch (error: CancellationException) {
        throw error
    } catch (error: VaultUnreadableException) {
        if (error.failure == VaultFailure.SCHEMA_NEWER || error.failure == VaultFailure.KEY_INVALIDATED) throw error
        warn("Skipping unlisted profile record $id: it could not be read", error)
        null
    }

    private suspend fun readDeleted(): Set<String>? = try {
        readDocument(DELETED_KEY, DELETED_ASSOCIATED_DATA, ::parseDeleted)?.toSet() ?: emptySet()
    } catch (error: CancellationException) {
        throw error
    } catch (error: VaultUnreadableException) {
        if (error.failure == VaultFailure.SCHEMA_NEWER) throw error
        warn("Could not read the deleted-profile record; unlisted profiles will not be recovered", error)
        null
    }

    private fun parseDeleted(element: JsonElement): List<String> =
        json.decodeFromJsonElement(ListSerializer(String.serializer()), StorageSchema.migrate(element as JsonObject).getValue(INDEX_DELETED))

    private suspend fun writeDeleted(ids: Set<String>) {
        val document = StorageSchema.stamp(JsonObject(mapOf(INDEX_DELETED to json.encodeToJsonElement(ListSerializer(String.serializer()), ids.sorted()))))
        writeDocument(DELETED_KEY, DELETED_ASSOCIATED_DATA, document)
        deletedProfileIds = ids
    }

    private suspend fun recordDeleted(profileId: String) {
        val known = deletedProfileIds
        // Unreadable tombstones are replaced (the unreadable file is quarantined first), starting again from this id.
        if (known == null) pendingQuarantine += DELETED_KEY
        writeDeleted(known.orEmpty() + profileId)
    }

    /** A profile id being created or restored again is no longer deleted. */
    private suspend fun forgetDeleted(profileId: String) {
        val known = deletedProfileIds ?: return
        if (profileId in known) writeDeleted(known - profileId)
    }

    private fun profileIdOf(key: String): String? =
        key.takeIf { it != INDEX_KEY && it.startsWith(PROFILE_PREFIX) && it.endsWith(PROFILE_SUFFIX) }
            ?.removePrefix(PROFILE_PREFIX)?.removeSuffix(PROFILE_SUFFIX)?.takeIf { it.isNotBlank() }

    /** Writes every profile, the health link, and the index last; only then is the new layout authoritative. */
    private suspend fun migrateLegacy(legacy: LegacySnapshot): List<Profile> {
        legacy.records.values.forEach { persist(it) }
        val profiles = legacy.records.values.map { it.profile }.sortedBy { it.displayName }
        legacy.healthLinkedProfileId?.takeIf { legacy.records.containsKey(it) }?.let { vault.write(HEALTH_LINK_KEY, it.encodeToByteArray()) }
        writeIndex(profiles)
        healthLinkId = vault.read(HEALTH_LINK_KEY)?.decodeToString()?.takeIf { it.isNotBlank() }
        return profiles
    }

    /**
     * Current generation first; only a corrupt one falls back to the previous generation.
     * Null when nothing is stored. A newer schema is never read from the previous generation.
     */
    private suspend fun <T : Any> readDocument(key: String, associatedData: String, parse: (JsonElement) -> T): T? {
        val current = vault.read(key) ?: return null
        return try {
            decode(current, associatedData, parse)
        } catch (error: CancellationException) {
            throw error
        } catch (error: StorageSchemaTooNewException) {
            throw VaultUnreadableException(VaultFailure.SCHEMA_NEWER, error)
        } catch (error: VaultUnreadableException) {
            if (error.failure != VaultFailure.CORRUPT) throw error
            readPrevious(key, associatedData, parse, error)
        } catch (error: Exception) {
            readPrevious(key, associatedData, parse, VaultUnreadableException(VaultFailure.CORRUPT, error))
        }
    }

    private suspend fun <T : Any> readPrevious(
        key: String,
        associatedData: String,
        parse: (JsonElement) -> T,
        failure: VaultUnreadableException,
    ): T {
        val previous = vault.readPrevious(key) ?: throw failure
        val restored = try {
            decode(previous, associatedData, parse)
        } catch (error: CancellationException) {
            throw error
        } catch (error: StorageSchemaTooNewException) {
            throw VaultUnreadableException(VaultFailure.SCHEMA_NEWER, error)
        } catch (error: Exception) {
            throw failure
        }
        // Keep the damaged file as evidence and out of the rotation that would overwrite the good previous copy.
        try {
            vault.quarantineCurrent(key)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Still open with the good previous data; the first write of this key retries before anything is rotated.
            warn("Could not quarantine corrupt vault file", error)
            pendingQuarantine += key
        }
        mutableOpenedFromPrevious.value = true
        return restored
    }

    private suspend fun <T> decode(stored: ByteArray, associatedData: String, parse: (JsonElement) -> T): T {
        val envelope = CipherEnvelopeCodec.decode(stored)
        val plain = vault.decrypt(envelope, associatedData.encodeToByteArray())
        return parse(json.parseToJsonElement(plain.decodeToString()))
    }

    private fun parseIndex(element: JsonElement): List<Profile> {
        // Schema 1 (iOS) stored a bare array of profiles.
        val array = if (element is JsonArray) element else {
            val document = StorageSchema.migrate(element as JsonObject)
            document.getValue(INDEX_PROFILES)
        }
        return json.decodeFromJsonElement(ListSerializer(Profile.serializer()), array)
    }

    private fun parseProfile(profileId: String, element: JsonElement): ProfileData {
        val document = StorageSchema.unstamped(StorageSchema.migrate(element as JsonObject))
        val data = json.decodeFromJsonElement(ProfileData.serializer(), document)
        require(data.profile.profileId == profileId && data.hasOnlyProfileRecords(profileId)) { "Corrupt profile scope in repository" }
        return data
    }

    private fun parseLegacy(element: JsonElement): LegacySnapshot {
        val snapshot = json.decodeFromJsonElement(LegacySnapshot.serializer(), element)
        snapshot.records.forEach { (profileId, data) -> require(data.profile.profileId == profileId && data.hasOnlyProfileRecords(profileId)) { "Corrupt profile scope in repository" } }
        // Older builds could store several terminal events for one dose; keep one.
        return snapshot.copy(records = snapshot.records.mapValues { (_, data) -> data.copy(doseEvents = DoseEvents.collapse(data.doseEvents)) })
    }

    private fun ProfileData.hasOnlyProfileRecords(profileId: String) =
        documents.all { it.profileId == profileId } &&
            reports.all { it.profileId == profileId } &&
            observations.all { it.profileId == profileId } &&
            ocrCandidates.all { it.profileId == profileId } &&
            medications.all { it.profileId == profileId } &&
            doseEvents.all { it.profileId == profileId } &&
            chat.all { it.profileId == profileId }

    companion object {
        /** iOS-style entry point. */
        suspend fun open(vault: SecureVault, json: Json = Json { encodeDefaults = true }, persistSelection: Boolean = true): VaultBackedRepository =
            VaultBackedRepository(vault, json, persistSelection).also { it.load() }

        private const val LEGACY_KEY = "repository-v1"
        private const val LEGACY_ASSOCIATED_DATA = "Vezuna repository v1"
        private const val INDEX_KEY = "repository-profile-index-v1"
        private const val INDEX_ASSOCIATED_DATA = "Vezuna repository index v1"
        private const val INDEX_PROFILES = "profiles"
        private const val SELECTION_KEY = "repository-selected-profile-v1"
        private const val HEALTH_LINK_KEY = "repository-health-link-v1"
        private const val DELETED_KEY = "repository-deleted-profiles-v1"
        private const val DELETED_ASSOCIATED_DATA = "Vezuna repository deleted profiles v1"
        private const val INDEX_DELETED = "deleted"
        private const val PROFILE_PREFIX = "repository-profile-"
        private const val PROFILE_SUFFIX = "-v1"
        private fun profileKey(profileId: String) = "$PROFILE_PREFIX$profileId$PROFILE_SUFFIX"
        private fun profileAssociatedData(profileId: String) = "Vezuna repository profile v1:$profileId"
    }
}
