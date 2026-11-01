package app.vezuna.domain

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
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
)

@Serializable
data class LabReport(
    val profileId: String,
    val reportId: String,
    val sourceDocumentId: String,
    val laboratoryName: String? = null,
    val orderedAt: Instant? = null,
    val importedAt: Instant,
)

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
) {
    init {
        require(originalValue.isFinite() && normalizedValue.isFinite())
        require(qualitativeValue == null || qualitativeValue.isNotBlank())
    }

    val isQualitative: Boolean get() = qualitativeValue != null
}

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
) {
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
)
