package app.vezuna.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val restoreJson = Json { encodeDefaults = true }

/**
 * Restore-as-new: gives the profile and every child record fresh IDs. Works on the serialized form so that any
 * `*Id` field, including ones added to [ProfileData] later, is remapped consistently (same old ID, same new ID),
 * which keeps cross-references such as report -> source document intact.
 */
fun ProfileData.remappedAsNew(newId: () -> String): ProfileData {
    val mapping = mutableMapOf<String, String>()
    fun walk(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (key, value) ->
            when {
                key == "vaultKey" -> JsonNull
                key == "pageVaultKeys" -> kotlinx.serialization.json.JsonArray(emptyList())
                isIdField(key) && value is JsonPrimitive && value.isString -> JsonPrimitive(mapping.getOrPut(value.content, newId))
                else -> walk(value)
            }
        })
        is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(element.map(::walk))
        else -> element
    }
    val encoded = restoreJson.encodeToJsonElement(ProfileData.serializer(), this)
    return restoreJson.decodeFromJsonElement(ProfileData.serializer(), walk(encoded))
}

/** `recordId` is the platform's own record key (Health Connect / HealthKit), used for de-duplication; it stays as is. */
private fun isIdField(key: String) = key != "recordId" && (key == "profileId" || (key.length > 2 && key.endsWith("Id")))

/**
 * Backups carry records, not source files, so a restored document keeps its vault key only when [existing]
 * (the profile being replaced) still holds that exact file; otherwise the key would dangle.
 */
fun ProfileData.keepingOnlyAvailableSourceFiles(existing: ProfileData?): ProfileData {
    val held = existing?.allDocuments().orEmpty()
    return copy(
        documents = documents.map { it.keepingFileFrom(held) },
        vaultDocuments = vaultDocuments.map { it.copy(source = it.source.keepingFileFrom(held)) },
    )
}

private fun SourceDocument.keepingFileFrom(held: List<SourceDocument>): SourceDocument {
    val current = held.firstOrNull { it.profileId == profileId && it.documentId == documentId && sha256.isNotBlank() && it.sha256 == sha256 }
    return if (current != null && !current.vaultKey.isNullOrBlank()) {
        copy(vaultKey = current.vaultKey, pageVaultKeys = current.pageVaultKeys)
    } else {
        copy(vaultKey = null, pageVaultKeys = emptyList())
    }
}

/** True when every record belongs to the profile, IDs are unique per record type and references resolve inside it. */
fun ProfileData.isInternallyConsistent(): Boolean {
    val id = profile.profileId
    fun <T> List<T>.unique(key: (T) -> String) = map(key).toSet().size == size
    val ids = listOf(id) + allDocuments().map { it.documentId } + reports.map { it.reportId } + observations.flatMap { o -> listOf(o.observationId) + o.revisions.map { it.revisionId } } +
        ocrCandidates.flatMap { c -> listOf(c.candidateId) + c.revisions.map { it.revisionId } } +
        medications.flatMap { m -> listOf(m.medicationId) + m.schedules.map { it.scheduleId } } +
        doseEvents.map { it.eventId } + chat.map { it.messageId } + healthRecords.map { it.id } + appointments.map { it.appointmentId }
    return ids.all(::isValidId) &&
        allDocuments().unique { it.documentId } &&
        reports.unique { it.reportId } &&
        observations.unique { it.observationId } &&
        observations.all { o -> o.revisions.unique { it.revisionId } } &&
        ocrCandidates.unique { it.candidateId } &&
        ocrCandidates.all { c -> c.revisions.all { it.profileId == id } && c.revisions.unique { it.revisionId } } &&
        medications.unique { it.medicationId } &&
        medications.flatMap { it.schedules }.unique { it.scheduleId } &&
        doseEvents.unique { it.eventId } &&
        chat.unique { it.messageId } &&
        allDocuments().all { it.profileId == id } &&
        reports.all { r -> r.profileId == id && (r.sourceDocumentId == null || documents.any { it.documentId == r.sourceDocumentId }) } &&
        observations.all { o -> o.profileId == id && reports.any { it.reportId == o.reportId } } &&
        ocrCandidates.all { c -> c.profileId == id && documents.any { it.documentId == c.sourceDocumentId } } &&
        medications.all { m -> m.profileId == id && m.schedules.all { it.profileId == id && it.medicationId == m.medicationId } } &&
        doseEvents.all { e ->
            e.profileId == id && medications.any { m -> m.medicationId == e.medicationId && m.schedules.any { it.scheduleId == e.scheduleId } }
        } &&
        chat.all { it.profileId == id } &&
        healthRecords.all { it.profileId == id } &&
        healthRecords.unique { it.id } &&
        appointments.all { it.profileId == id } &&
        appointments.unique { it.appointmentId }
}

private val VALID_ID = Regex("[A-Za-z0-9_-]{1,128}")

/** The app generates UUIDs and `dose-<uuid>-<epoch>` event IDs; anything else would be hostile in a file name or key. */
private fun isValidId(value: String) = VALID_ID.matches(value)
