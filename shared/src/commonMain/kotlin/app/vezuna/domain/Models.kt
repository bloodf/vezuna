package app.vezuna.domain

import kotlinx.datetime.DayOfWeek
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val profileId: String,
    val displayName: String,
    val birthDate: LocalDate? = null,
    val createdAt: Instant,
)

@Serializable
data class SourceDocument(
    val profileId: String,
    val documentId: String,
    val displayName: String,
    val mediaType: String,
    val sha256: String,
    val importedAt: Instant,
    val vaultKey: String? = null,
    /** Vault keys of pages 2..n when one report was captured as several photos; [vaultKey] holds page 1. */
    val pageVaultKeys: List<String> = emptyList(),
)

@Serializable
data class LabReport(
    val profileId: String,
    val reportId: String,
    /** Null for a manual report: a result typed in by the user, with no source file. */
    val sourceDocumentId: String? = null,
    val laboratoryName: String? = null,
    val orderedAt: Instant? = null,
    val importedAt: Instant,
) {
    val isManual: Boolean get() = sourceDocumentId == null
}

@Serializable
enum class ConfirmationStatus { UNREVIEWED, CONFIRMED, REJECTED }

@Serializable
data class ReferenceRange(
    val lower: Double? = null,
    val upper: Double? = null,
    val text: String? = null,
    val unit: String? = null,
) {
    init {
        require(lower == null || lower.isFinite())
        require(upper == null || upper.isFinite())
        require(lower == null || upper == null || lower <= upper)
    }
}

@Serializable
data class LabObservation(
    val profileId: String,
    val observationId: String,
    val reportId: String,
    val testCode: String,
    val displayName: String,
    val originalValue: Double,
    val originalUnit: String,
    val normalizedValue: Double,
    val normalizedUnit: String,
    val referenceRange: ReferenceRange,
    val collectedAt: Instant,
    val confirmation: ConfirmationStatus = ConfirmationStatus.UNREVIEWED,
    /** Non-null for qualitative results (e.g. "Negative"); numeric fields are then 0.0 placeholders and must not be read. Absent in pre-qualitative stored data. */
    val qualitativeValue: String? = null,
    /** Fraction digits of the value as the report printed it (5.70 -> 2); display only, null in older stored data. */
    val valueDecimals: Int? = null,
    /** Earlier states of this result, oldest first; each edit of a confirmed result appends one. */
    val revisions: List<ObservationRevision> = emptyList(),
) {
    init {
        require(valueDecimals == null || valueDecimals in 0..MAX_VALUE_DECIMALS)
        require(originalValue.isFinite() && normalizedValue.isFinite())
        if (qualitativeValue != null) {
            require(qualitativeValue.isNotBlank())
            require(originalValue == 0.0 && normalizedValue == 0.0) { "Qualitative result must not carry numeric values" }
            require(originalUnit.isBlank() && normalizedUnit.isBlank()) { "Qualitative result must not carry units" }
            require(referenceRange.lower == null && referenceRange.upper == null) { "Qualitative result must not carry numeric range bounds" }
        }
    }

    val isQualitative: Boolean get() = qualitativeValue != null

    companion object {
        const val MAX_VALUE_DECIMALS = 9
    }
}

/** The state of a confirmed result before one edit, so every earlier value stays visible. */
@Serializable
data class ObservationRevision(
    val revisionId: String,
    val revisedAt: Instant,
    val previousValue: Double,
    val previousUnit: String,
    val previousValueDecimals: Int? = null,
    val previousReferenceRange: ReferenceRange,
    val previousCollectedAt: Instant,
    val previousQualitativeValue: String? = null,
)

@Serializable
data class OcrRevision(
    val profileId: String,
    val revisionId: String,
    val revisedAt: Instant,
    val previousValue: String,
    val replacementValue: String,
    val reason: String? = null,
)

@Serializable
data class OcrCandidate(
    val profileId: String,
    val candidateId: String,
    val sourceDocumentId: String,
    val testCode: String,
    val displayName: String,
    val rawValue: String,
    val rawUnit: String,
    val referenceRange: ReferenceRange = ReferenceRange(),
    val collectedAt: Instant,
    val confidence: Float? = null,
    val status: ConfirmationStatus = ConfirmationStatus.UNREVIEWED,
    val revisions: List<OcrRevision> = emptyList(),
    /** True when the result is text (e.g. "Negative") rather than a number. */
    val qualitative: Boolean = false,
    /** True only when the collection date came from the report or the user, not the import-time fallback. */
    val collectedAtKnown: Boolean = false,
    /** Original OCR number token, kept only when its reading is ambiguous (e.g. "1.020") so review can show it. */
    val rawToken: String? = null,
) {
    /** Text result: flagged at import, or (older stored candidates) a non-numeric value with no unit. */
    val looksQualitative: Boolean
        get() = qualitative || (rawUnit.isBlank() && app.vezuna.localization.parseDecimalInput(rawValue) == null)

    init {
        require(confidence == null || confidence in 0f..1f)
    }
}

@Serializable
enum class Recurrence { DAILY, WEEKLY, EVERY_N_DAYS }

@Serializable
data class DoseSchedule(
    val profileId: String,
    val scheduleId: String,
    val medicationId: String,
    val recurrence: Recurrence,
    val intervalDays: Int = 1,
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val timesLocal: List<String>,
    /** When doses of this schedule started to count (creation or last change of times/recurrence). Null for legacy records. */
    val trackedSince: Instant? = null,
) {
    init {
        require(intervalDays > 0)
        require(timesLocal.isNotEmpty() && timesLocal.all { TIME.matches(it) })
        require(endDate == null || endDate >= startDate)
        require(recurrence != Recurrence.WEEKLY || daysOfWeek.isNotEmpty())
    }

    private companion object {
        val TIME = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")
    }
}

@Serializable
data class Medication(
    val profileId: String,
    val medicationId: String,
    val displayName: String,
    val strength: String? = null,
    val instructions: String? = null,
    val schedules: List<DoseSchedule> = emptyList(),
    val active: Boolean = true,
    /** Optional refill tracking; null for medications that never set a supply and for data saved before this field. */
    val supply: MedicationSupply? = null,
)

@Serializable
enum class DoseEventStatus { SCHEDULED, TAKEN, SKIPPED, MISSED }

@Serializable
data class DoseEvent(
    val profileId: String,
    val eventId: String,
    val medicationId: String,
    val scheduleId: String,
    val scheduledFor: Instant,
    val status: DoseEventStatus = DoseEventStatus.SCHEDULED,
    val recordedAt: Instant? = null,
    val replacesScheduledFor: Instant? = null,
)

@Serializable
data class ChatMessage(
    val profileId: String,
    val messageId: String,
    val role: Role,
    val content: String,
    val createdAt: Instant,
) {
    @Serializable
    enum class Role { USER, ASSISTANT }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ProfileData(
    val profile: Profile,
    val documents: List<SourceDocument> = emptyList(),
    val reports: List<LabReport> = emptyList(),
    val observations: List<LabObservation> = emptyList(),
    val ocrCandidates: List<OcrCandidate> = emptyList(),
    val medications: List<Medication> = emptyList(),
    val doseEvents: List<DoseEvent> = emptyList(),
    val chat: List<ChatMessage> = emptyList(),
    // Device health data (Health Connect / HealthKit); populated only for the linked profile.
    val vitals: ProfileVitals = ProfileVitals(),
    // User-entered records (#96). Left out of the JSON while empty, so backups without them stay readable by older builds;
    // an older build refuses one that has them (unknown key), so nothing is ever silently dropped. Local storage is stamped v3 and refused by older builds.
    @EncodeDefault(EncodeDefault.Mode.NEVER) val allergies: List<AllergyRecord> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val conditions: List<ConditionRecord> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val vaccinations: List<VaccinationRecord> = emptyList(),
    // Visits (#101): same rule as the lists above; part of the same unreleased storage v3 / backup v5.
    @EncodeDefault(EncodeDefault.Mode.NEVER) val appointments: List<Appointment> = emptyList(),
    // region document vault
    /** Non-lab files. Left out of the JSON while empty, so a profile without any serializes exactly as before. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val vaultDocuments: List<VaultDocument> = emptyList(),
    // endregion document vault
)

// region document vault
/**
 * A non-lab file the person keeps in the vault (prescription, imaging report, ...), stored like an imported report's
 * original through [source]. Being in [ProfileData.vaultDocuments], never in [ProfileData.documents], is what makes it
 * non-lab: nothing that reads lab documents (OCR, candidates, results, trends, doctor export) can reach it, and nothing
 * is read from its contents. [tags] are stored as the person wrote them.
 */
@Serializable
data class VaultDocument(val source: SourceDocument, val tags: List<String> = emptyList())

/** Tag rules for vault documents; matching ignores case, spelling stays as written. */
object DocumentTags {
    const val MAX_LENGTH = 40
    const val MAX_PER_DOCUMENT = 20
    const val MAX_NAME_LENGTH = 255

    /** Trimmed, without blanks or case-insensitive repeats (the first spelling stays), capped. */
    fun clean(tags: List<String>): List<String> =
        tags.map { it.trim().take(MAX_LENGTH).trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(MAX_PER_DOCUMENT)

    /** Every tag in use, one spelling each, alphabetically. */
    fun inUse(documents: List<VaultDocument>): List<String> = documents.flatMap { it.tags }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

    /** Newest first; a null [tag] means all. */
    fun filter(documents: List<VaultDocument>, tag: String?): List<VaultDocument> =
        documents.filter { doc -> tag == null || doc.tags.any { it.equals(tag, ignoreCase = true) } }.sortedByDescending { it.source.importedAt }
}
// endregion document vault
