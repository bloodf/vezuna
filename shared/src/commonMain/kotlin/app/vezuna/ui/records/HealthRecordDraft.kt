package app.vezuna.ui.records

import app.vezuna.domain.AllergyRecord
import app.vezuna.domain.AllergySeverity
import app.vezuna.domain.ConditionRecord
import app.vezuna.domain.ConditionStatus
import app.vezuna.domain.HealthRecord
import app.vezuna.domain.HealthRecordKind
import app.vezuna.domain.PartialDate
import app.vezuna.domain.VaccinationRecord
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** One form for the three record kinds; each kind reads only its own fields. */
internal data class HealthRecordDraft(
    val kind: HealthRecordKind,
    val name: String = "",
    val reaction: String = "",
    val severity: AllergySeverity? = null,
    val notes: String = "",
    val onsetYear: String = "",
    val onsetMonth: String = "",
    val onsetDay: String = "",
    val status: ConditionStatus? = null,
    val date: Instant? = null,
    val dose: String = "",
    val lot: String = "",
) {
    val nameMissing: Boolean get() = name.isBlank()

    private val onsetBlank: Boolean get() = onsetYear.isBlank() && onsetMonth.isBlank() && onsetDay.isBlank()
    private val onset: PartialDate? get() = PartialDate.parse(onsetYear, onsetMonth, onsetDay)

    /** Blank is fine (unknown); anything typed must be a real date that is not in the future. */
    fun onsetInvalid(today: LocalDate): Boolean = kind == HealthRecordKind.CONDITION && !onsetBlank && onset?.isAfter(today) != false

    fun dateInFuture(today: LocalDate): Boolean =
        kind == HealthRecordKind.VACCINATION && date != null && date.toLocalDateTime(TimeZone.UTC).date > today

    fun isValid(today: LocalDate): Boolean = !nameMissing && !onsetInvalid(today) && !dateInFuture(today)

    /** Surrounding spaces go; every other character stays as written. */
    fun toRecord(profileId: String, id: String): HealthRecord = when (kind) {
        HealthRecordKind.ALLERGY -> AllergyRecord(profileId, id, name.trim(), reaction.optional(), severity, notes.optional())
        HealthRecordKind.CONDITION -> ConditionRecord(profileId, id, name.trim(), onset, status, notes.optional())
        HealthRecordKind.VACCINATION -> VaccinationRecord(profileId, id, name.trim(), date, dose.optional(), lot.optional())
    }

    private fun String.optional(): String? = trim().ifEmpty { null }
}

internal fun HealthRecord.toDraft(): HealthRecordDraft = when (this) {
    is AllergyRecord -> HealthRecordDraft(kind, substance, reaction.orEmpty(), severity, notes.orEmpty())
    is ConditionRecord -> HealthRecordDraft(
        kind, name, notes = notes.orEmpty(), status = status,
        onsetYear = onset?.year?.toString().orEmpty(), onsetMonth = onset?.month?.toString().orEmpty(), onsetDay = onset?.day?.toString().orEmpty(),
    )
    is VaccinationRecord -> HealthRecordDraft(kind, vaccine, date = date, dose = doseNumber.orEmpty(), lot = lot.orEmpty())
}

/**
 * The open form's unsaved text, in memory only (the same rule as ExportSession): it survives a rotation and never
 * reaches a saved-instance Bundle. Keyed by profile, kind and record, so another form never sees it.
 */
internal object HealthRecordFormSession {
    private var kept: Pair<Any, HealthRecordDraft>? = null

    fun restore(key: Any): HealthRecordDraft? = kept?.takeIf { it.first == key }?.second

    fun keep(key: Any, draft: HealthRecordDraft?) {
        kept = draft?.let { key to it }
    }
}
