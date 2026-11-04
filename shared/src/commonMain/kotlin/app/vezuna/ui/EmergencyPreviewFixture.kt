package app.vezuna.ui

import app.vezuna.data.VezunaRepository
import app.vezuna.domain.AllergyRecord
import app.vezuna.domain.AllergySeverity
import app.vezuna.domain.ConditionRecord
import app.vezuna.domain.ConditionStatus
import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.Medication
import app.vezuna.domain.ProfileData
import app.vezuna.domain.ProfileVitals
import app.vezuna.domain.Recurrence
import kotlinx.datetime.LocalDate

/**
 * Screenshot-only variants of [DesignFixture] for the emergency card (debug launch argument `-VezunaEmergencyPreview`
 * with `full` or `partial`). The canonical household stays untouched. Synthetic data, in-memory repository only.
 */
object EmergencyPreviewFixture {
    private const val P = DesignFixture.PROFILE_ID

    private fun schedule(id: String, start: LocalDate, end: LocalDate? = null) =
        DoseSchedule(P, "schedule-$id", "med-$id", Recurrence.DAILY, startDate = start, endDate = end, timesLocal = listOf("08:00"))

    /** Severe allergy, an unknown-status condition, a paused medication, a course starting later and one without a dose. */
    private fun full(base: ProfileData) = base.copy(
        allergies = base.allergies + AllergyRecord(P, "allergy-peanut", "Peanut", reaction = "Throat swelling", severity = AllergySeverity.SEVERE),
        medications = base.medications + listOf(
            Medication(P, "med-warfarin", "Warfarin", strength = "5 mg", active = false, schedules = listOf(schedule("warfarin", LocalDate(2026, 3, 1)))),
            Medication(P, "med-amoxicillin", "Amoxicillin", strength = "500 mg", schedules = listOf(schedule("amoxicillin", LocalDate(2027, 1, 15)))),
            Medication(P, "med-inhaler", "Rescue inhaler", schedules = listOf(schedule("inhaler", LocalDate(2026, 5, 1)))),
        ),
    )

    /** Records exist but none is current: a resolved condition and a stopped medication only. */
    private fun partial(base: ProfileData) = base.copy(
        allergies = emptyList(),
        doseEvents = emptyList(),
        conditions = listOf(ConditionRecord(P, "condition-appendectomy", "Appendectomy", status = ConditionStatus.RESOLVED)),
        medications = listOf(Medication(P, "med-old", "Amoxicillin", strength = "500 mg", active = false, schedules = listOf(schedule("old", LocalDate(2026, 1, 1), LocalDate(2026, 1, 10))))),
    )

    suspend fun install(repository: VezunaRepository, variant: String) {
        val base = DesignFixture.data()
        val data = if (variant == "partial") partial(base) else full(base)
        repository.restoreProfile(data.copy(vitals = ProfileVitals()))
        repository.replaceVitals(P, data.vitals)
        repository.setLinkedHealthProfile(P)
    }
}
