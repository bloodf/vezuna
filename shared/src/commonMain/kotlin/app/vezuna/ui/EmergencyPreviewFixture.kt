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
 * with `full`, `meds`, `later`, `later-only`, `partial` or `empty`). The canonical household stays untouched. Synthetic data, in-memory repository only.
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

    /** The same records with the allergy and condition lists trimmed, so the medications fit on the first screen of a screenshot. */
    private fun medications(base: ProfileData): ProfileData {
        val all = full(base)
        return all.copy(allergies = all.allergies.filter { it.allergyId == "allergy-peanut" }, conditions = emptyList(), medications = all.medications.takeLast(3), doseEvents = emptyList())
    }

    /** One allergy, a paused medication and the course that starts later, so "Starting later" and its card fit on the first screen. */
    private fun later(base: ProfileData): ProfileData {
        val all = medications(base)
        return all.copy(medications = all.medications.filter { it.medicationId != "med-inhaler" })
    }

    /** Only the course that starts later, for the largest text size where little else fits. */
    private fun laterOnly(base: ProfileData): ProfileData = later(base).copy(
        allergies = emptyList(),
        medications = later(base).medications.filter { it.medicationId == "med-amoxicillin" },
    )

    /** No allergy, condition or medication at all. */
    private fun empty(base: ProfileData) = base.copy(allergies = emptyList(), conditions = emptyList(), medications = emptyList(), doseEvents = emptyList())

    /** Records exist but none is current: a resolved condition and a stopped medication only. */
    private fun partial(base: ProfileData) = base.copy(
        allergies = emptyList(),
        doseEvents = emptyList(),
        conditions = listOf(ConditionRecord(P, "condition-appendectomy", "Appendectomy", status = ConditionStatus.RESOLVED)),
        medications = listOf(Medication(P, "med-old", "Amoxicillin", strength = "500 mg", active = false, schedules = listOf(schedule("old", LocalDate(2026, 1, 1), LocalDate(2026, 1, 10))))),
    )

    suspend fun install(repository: VezunaRepository, variant: String) {
        val base = DesignFixture.data()
        val data = when (variant) {
            "partial" -> partial(base)
            "empty" -> empty(base)
            "meds" -> medications(base)
            "later" -> later(base)
            "later-only" -> laterOnly(base)
            else -> full(base)
        }
        repository.restoreProfile(data.copy(vitals = ProfileVitals()))
        repository.replaceVitals(P, data.vitals)
        repository.setLinkedHealthProfile(P)
    }
}
