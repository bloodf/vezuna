package app.vezuna.domain

import kotlinx.datetime.LocalDate

/**
 * What the in-app emergency card shows (#102): only what the person entered or confirmed for this profile.
 * Lab results, unreviewed candidates, stopped medications and resolved conditions are left out.
 */
data class EmergencySummary(
    val profileName: String,
    val birthDate: LocalDate?,
    val ageYears: Int?,
    val allergies: List<AllergyRecord>,
    val conditions: List<ConditionRecord>,
    val medications: List<Medication>,
) {
    /** True when nothing has been recorded at all, so the card says that instead of listing empty sections. */
    val isEmpty: Boolean get() = allergies.isEmpty() && conditions.isEmpty() && medications.isEmpty()
}

fun ProfileData.emergencySummary(today: LocalDate): EmergencySummary {
    val id = profile.profileId
    return EmergencySummary(
        profileName = profile.displayName,
        birthDate = profile.birthDate,
        ageYears = ProfileAge.years(profile.birthDate, today),
        // Most severe first (unstated last), then by name.
        allergies = allergies.filter { it.profileId == id }
            .sortedWith(compareBy<AllergyRecord> { it.severity?.let { s -> -s.ordinal } ?: 1 }.thenBy { it.substance.lowercase() }),
        // Not stated counts as current: hiding it could drop something that matters.
        conditions = conditions.filter { it.profileId == id && it.status != ConditionStatus.RESOLVED }.sortedBy { it.name.lowercase() },
        medications = medications.filter { it.profileId == id && it.active }.sortedBy { it.displayName.lowercase() },
    )
}
