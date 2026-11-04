package app.vezuna.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.monthsUntil

/**
 * A medication on the emergency card. [paused]: reminders are off, which may or may not mean the person stopped.
 * [endedToday]: inactive with its last day today, so stopped or paused today; kept until midnight because listing too much
 * is the safer mistake. [startsOn] is set for a course that has not begun.
 */
data class EmergencyMedication(val medication: Medication, val paused: Boolean, val endedToday: Boolean, val startsOn: LocalDate?)

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
    /** Whole days, only for a baby under one month. */
    val ageDays: Int?,
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
    val months = if (years != null && years < 2) profile.birthDate?.monthsUntil(today) else null
    return EmergencySummary(
        profileName = profile.displayName,
        birthDate = profile.birthDate,
        ageYears = years,
        ageMonths = months,
        ageDays = if (months == 0) profile.birthDate?.daysUntil(today) else null,
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
 * Null when the medication is over: every schedule ended before today. Inactive with its last day today is kept (a future course that was stopped or paused is just paused: its end date is its start date) and flagged
 * [EmergencyMedication.endedToday], because stopping (`endDate = max(today, start)`, see CareController.stopMedication) and
 * pausing cannot be told apart from the dates. Only schedules not yet ended count towards "upcoming", so an old course plus
 * a future one is upcoming, not current.
 */
private fun Medication.onEmergencyCard(today: LocalDate): EmergencyMedication? {
    val remaining = schedules.filter { it.endDate == null || it.endDate >= today }
    if (schedules.isNotEmpty() && remaining.isEmpty()) return null
    val endedToday = !active && remaining.isNotEmpty() && remaining.all { it.endDate == today }
    val upcoming = remaining.takeIf { it.isNotEmpty() && it.all { s -> s.startDate > today } }?.minOf { it.startDate }
    return EmergencyMedication(this, paused = !active && !endedToday, endedToday = endedToday, startsOn = upcoming)
}
