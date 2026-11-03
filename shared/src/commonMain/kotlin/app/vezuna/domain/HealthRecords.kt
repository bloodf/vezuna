package app.vezuna.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Allergies, conditions and vaccinations (#96): typed by the owner and kept as written. Plain text, no coding systems,
 * nothing inferred or derived from lab results. Severity and status are labels the owner picks; null means not stated.
 */
enum class HealthRecordKind { ALLERGY, CONDITION, VACCINATION }

@Serializable
enum class AllergySeverity { MILD, MODERATE, SEVERE }

@Serializable
enum class ConditionStatus { ONGOING, RESOLVED }

/** A date known only as precisely as the owner remembers it: a year, a month of a year, or a full day. */
@Serializable
data class PartialDate(val year: Int, val month: Int? = null, val day: Int? = null) {
    init {
        require(year in 1..9999)
        require(month == null || month in 1..12)
        require(day == null || month != null)
        if (month != null && day != null) LocalDate(year, month, day)
    }

    /** ISO 8601 at the known precision: 2015, 2015-03 or 2015-03-09. */
    val iso: String
        get() = listOfNotNull(year.toString().padStart(4, '0'), month?.toString()?.padStart(2, '0'), day?.toString()?.padStart(2, '0')).joinToString("-")

    fun isAfter(today: LocalDate): Boolean = when {
        year != today.year -> year > today.year
        month == null -> false
        month != today.monthNumber -> month > today.monthNumber
        else -> day != null && day > today.dayOfMonth
    }

    companion object {
        /** Null unless the fields form a real date (any year the type accepts, so a restored old record can be saved again); blank month and day mean unknown. */
        fun parse(year: String, month: String, day: String): PartialDate? {
            val y = year.trim().toIntOrNull()?.takeIf { it >= 1 } ?: return null
            val m = month.trim().ifEmpty { null }?.let { it.toIntOrNull() ?: return null }
            val d = day.trim().ifEmpty { null }?.let { it.toIntOrNull() ?: return null }
            return try {
                PartialDate(y, m, d)
            } catch (invalid: IllegalArgumentException) {
                null
            }
        }
    }
}

sealed interface HealthRecord {
    val profileId: String
    val id: String
    val kind: HealthRecordKind

    /** The substance, condition or vaccine, as written. */
    val title: String
}

@Serializable
data class AllergyRecord(
    override val profileId: String,
    val allergyId: String,
    val substance: String,
    val reaction: String? = null,
    val severity: AllergySeverity? = null,
    val notes: String? = null,
) : HealthRecord {
    init {
        require(substance.isNotBlank())
    }

    override val id get() = allergyId
    override val kind get() = HealthRecordKind.ALLERGY
    override val title get() = substance
}

@Serializable
data class ConditionRecord(
    override val profileId: String,
    val conditionId: String,
    val name: String,
    /** Null when the onset is unknown; never filled in. */
    val onset: PartialDate? = null,
    val status: ConditionStatus? = null,
    val notes: String? = null,
) : HealthRecord {
    init {
        require(name.isNotBlank())
    }

    override val id get() = conditionId
    override val kind get() = HealthRecordKind.CONDITION
    override val title get() = name
}

@Serializable
data class VaccinationRecord(
    override val profileId: String,
    val vaccinationId: String,
    val vaccine: String,
    /** Calendar day at noon UTC, like collection dates; null when not recorded. */
    val date: Instant? = null,
    /** As written on the card: "2", "Booster". */
    val doseNumber: String? = null,
    val lot: String? = null,
) : HealthRecord {
    init {
        require(vaccine.isNotBlank())
    }

    override val id get() = vaccinationId
    override val kind get() = HealthRecordKind.VACCINATION
    override val title get() = vaccine

    val day: LocalDate? get() = date?.toLocalDateTime(TimeZone.UTC)?.date
}

val ProfileData.healthRecords: List<HealthRecord> get() = allergies + conditions + vaccinations

fun ProfileData.healthRecords(kind: HealthRecordKind): List<HealthRecord> = when (kind) {
    HealthRecordKind.ALLERGY -> allergiesByName
    HealthRecordKind.CONDITION -> conditionsByName
    HealthRecordKind.VACCINATION -> vaccinationsByDate
}

// One display order everywhere (Records, PDF, CSV): alphabetical so order never implies importance.
val ProfileData.allergiesByName: List<AllergyRecord> get() = allergies.sortedBy { it.substance.lowercase() }
val ProfileData.conditionsByName: List<ConditionRecord> get() = conditions.sortedBy { it.name.lowercase() }

/** Newest first; undated entries last. */
val ProfileData.vaccinationsByDate: List<VaccinationRecord>
    get() = vaccinations.sortedWith(compareBy<VaccinationRecord> { it.date == null }.thenByDescending { it.date }.thenBy { it.vaccine.lowercase() })

fun ProfileData.withNewHealthRecord(record: HealthRecord): ProfileData {
    require(record.profileId == profile.profileId)
    require(healthRecords.none { it.id == record.id })
    return upserting(record)
}

fun ProfileData.withHealthRecord(record: HealthRecord): ProfileData {
    require(record.profileId == profile.profileId)
    require(healthRecords.any { it.id == record.id && it.kind == record.kind }) { "Record not found" }
    return upserting(record)
}

fun ProfileData.withoutHealthRecord(recordId: String): ProfileData {
    require(healthRecords.any { it.id == recordId }) { "Record not found" }
    return copy(
        allergies = allergies.filterNot { it.id == recordId },
        conditions = conditions.filterNot { it.id == recordId },
        vaccinations = vaccinations.filterNot { it.id == recordId },
    )
}

private fun ProfileData.upserting(record: HealthRecord): ProfileData = when (record) {
    is AllergyRecord -> copy(allergies = allergies.filterNot { it.id == record.id } + record)
    is ConditionRecord -> copy(conditions = conditions.filterNot { it.id == record.id } + record)
    is VaccinationRecord -> copy(vaccinations = vaccinations.filterNot { it.id == record.id } + record)
}
