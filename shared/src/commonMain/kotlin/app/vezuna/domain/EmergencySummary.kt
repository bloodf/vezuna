package app.vezuna.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.monthsUntil

/** A medication on the emergency card: [paused] means reminders are off (the person may still take it); [startsOn] is set for a course that has not begun. */
data class EmergencyMedication(val medication: Medication, val paused: Boolean, val startsOn: LocalDate?)

/**
 * What the in-app emergency card shows (#102): only what the person entered for this profile.
 * Lab results and unreviewed candidates are never read.
 */
data class EmergencySummary(
    val profileName: String,
    val birthDate: LocalDate?,
    val ageYears: Int?,
    /** Completed months, only for a child under 2, where months matter more than years. */
    val ageMonths: Int?,
    val allergies: List<AllergyRecord>,
    /** Everything not resolved; a condition with no stated status stays. */
    val conditions: List<ConditionRecord>,
    val medications: List<EmergencyMedication>,
    /** True only when the profile has no allergy, condition or medication at all, before any filtering. */
    val nothingRecorded: Boolean,
)

fun ProfileData.emergencySummary(today: LocalDate): EmergencySummary {
    val id = profile.profileId
    val ownAllergies = allergies.filter { it.profileId == id }
    val ownConditions = conditions.filter { it.profileId == id }
    val ownMedications = medications.filter { it.profileId == id }
    val years = ProfileAge.years(profile.birthDate, today)
    return EmergencySummary(
        profileName = profile.displayName,
        birthDate = profile.birthDate,
        ageYears = years,
        ageMonths = if (years != null && years < 2) profile.birthDate?.monthsUntil(today) else null,
        // Severe first; an unstated severity ranks above mild, because unknown must not look safe.
        allergies = ownAllergies.sortedWith(compareBy<AllergyRecord> { it.severity.rank() }.thenBy { it.substance.lowercase() }),
        conditions = ownConditions.filter { it.status != ConditionStatus.RESOLVED }.sortedBy { it.name.lowercase() },
        medications = ownMedications.mapNotNull { it.onEmergencyCard(today) }
            .sortedWith(compareBy<EmergencyMedication> { it.startsOn != null }.thenBy { it.medication.displayName.lowercase() }),
        nothingRecorded = ownAllergies.isEmpty() && ownConditions.isEmpty() && ownMedications.isEmpty(),
    )
}

private fun AllergySeverity?.rank() = when (this) {
    AllergySeverity.SEVERE -> 0
    AllergySeverity.MODERATE -> 1
    null -> 2
    AllergySeverity.MILD -> 3
}

/**
 * Null when the medication is over. Pausing (reminders off, no end date) keeps it on the card; stopping ends the schedules
 * (`endDate = max(today, start)`, see CareController.stopMedication). A paused medication whose end date is still ahead is not
 * a stopped one, so it stays. A course whose schedules all start later is listed with its start date.
 */
private fun Medication.onEmergencyCard(today: LocalDate): EmergencyMedication? {
    if (schedules.isNotEmpty()) {
        if (schedules.all { it.endDate != null && it.endDate < today }) return null
        if (!active && schedules.all { it.endDate != null && it.endDate <= maxOf(today, it.startDate) }) return null
    }
    val upcoming = schedules.takeIf { it.isNotEmpty() && it.all { s -> s.startDate > today } }?.minOf { it.startDate }
    return EmergencyMedication(this, paused = !active, startsOn = upcoming)
}
