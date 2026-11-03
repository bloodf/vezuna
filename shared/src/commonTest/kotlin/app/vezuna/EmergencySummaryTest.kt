package app.vezuna

import app.vezuna.domain.AllergyRecord
import app.vezuna.domain.AllergySeverity
import app.vezuna.domain.ConditionRecord
import app.vezuna.domain.ConditionStatus
import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.Medication
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.Recurrence
import app.vezuna.domain.emergencySummary
import app.vezuna.ui.AppScreen
import app.vezuna.ui.nav.routeFromName
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class EmergencySummaryTest {
    private val today = LocalDate(2026, 11, 3)
    private val p = "A-profile"

    private fun data(
        birth: LocalDate? = LocalDate(1990, 11, 4),
        allergies: List<AllergyRecord> = emptyList(),
        conditions: List<ConditionRecord> = emptyList(),
        medications: List<Medication> = emptyList(),
    ) = ProfileData(Profile(p, "Ana", birth, Instant.parse("2026-01-01T00:00:00Z")), allergies = allergies, conditions = conditions, medications = medications)

    private fun allergy(name: String, severity: AllergySeverity? = null, profile: String = p) = AllergyRecord(profile, "al-$name", name, severity = severity)
    private fun condition(name: String, status: ConditionStatus?, profile: String = p) = ConditionRecord(profile, "co-$name", name, status = status)

    private fun schedule(name: String, start: LocalDate = LocalDate(2026, 1, 1), end: LocalDate? = null, profile: String = p) =
        DoseSchedule(profile, "s-$name", "m-$name", Recurrence.DAILY, startDate = start, endDate = end, timesLocal = listOf("08:00"))

    /** Built the way the app does it: a new medication is active; pausing flips the flag only; stopping ends the schedules (CareController.stopMedication). */
    private fun med(name: String, start: LocalDate = LocalDate(2026, 1, 1), end: LocalDate? = null, strength: String? = "10 mg", profile: String = p) =
        Medication(profile, "m-$name", name, strength = strength, schedules = listOf(schedule(name, start, end, profile)))

    private fun Medication.paused() = copy(active = false)
    private fun Medication.stopped() = copy(active = false, schedules = schedules.map { it.copy(endDate = maxOf(today, it.startDate)) })

    private fun names(d: ProfileData) = d.emergencySummary(today).medications.map { it.medication.displayName }

    @Test
    fun emptyProfileHasNothingRecordedAndKeepsNameAndAge() {
        val s = data().emergencySummary(today)
        assertTrue(s.nothingRecorded)
        assertEquals("Ana", s.profileName)
        assertEquals(35, s.ageYears)
        assertNull(s.ageMonths)
    }

    @Test
    fun unknownBirthDateGivesNoAge() {
        val s = data(birth = null).emergencySummary(today)
        assertNull(s.ageYears)
        assertNull(s.ageMonths)
    }

    @Test
    fun infantAgeIsInMonths() {
        val s = data(birth = LocalDate(2026, 6, 3)).emergencySummary(today)
        assertEquals(0, s.ageYears)
        assertEquals(5, s.ageMonths)
        assertNull(data(birth = LocalDate(2020, 1, 1)).emergencySummary(today).ageMonths)
    }

    @Test
    fun allergiesAreSevereThenModerateThenUnstatedThenMildThenByName() {
        val s = data(allergies = listOf(allergy("Pollen"), allergy("Latex", AllergySeverity.MILD), allergy("Peanut", AllergySeverity.SEVERE), allergy("Dust", AllergySeverity.MILD), allergy("Shellfish", AllergySeverity.MODERATE), allergy("Cat")))
            .emergencySummary(today)
        assertEquals(listOf("Peanut", "Shellfish", "Cat", "Pollen", "Dust", "Latex"), s.allergies.map { it.substance })
    }

    @Test
    fun resolvedConditionsAreLeftOutAndUnstatedOnesStay() {
        val s = data(conditions = listOf(condition("Asthma", ConditionStatus.ONGOING), condition("Chickenpox", ConditionStatus.RESOLVED), condition("Anemia", null)))
            .emergencySummary(today)
        assertEquals(listOf("Anemia", "Asthma"), s.conditions.map { it.name })
    }

    @Test
    fun activeMedicationIsListedByName() {
        val d = data(medications = listOf(med("Metformin"), med("Atenolol")))
        assertEquals(listOf("Atenolol", "Metformin"), names(d))
        assertFalse(d.emergencySummary(today).nothingRecorded)
    }

    @Test
    fun pausedMedicationStaysListedAndIsMarkedPaused() {
        val s = data(medications = listOf(med("Warfarin").paused(), med("Metformin"))).emergencySummary(today)
        assertEquals(listOf("Metformin" to false, "Warfarin" to true), s.medications.map { it.medication.displayName to it.paused })
    }

    @Test
    fun pausedMedicationWithEndDateStillAheadStaysListed() {
        assertEquals(listOf("Warfarin"), names(data(medications = listOf(med("Warfarin", end = LocalDate(2026, 12, 31)).paused()))))
    }

    @Test
    fun stoppedMedicationIsLeftOutTodayAndLater() {
        assertEquals(emptyList(), names(data(medications = listOf(med("Aspirin").stopped()))))
        val later = LocalDate(2026, 11, 20)
        val stoppedToday = med("Aspirin").stopped()
        assertEquals(emptyList(), data(medications = listOf(stoppedToday)).emergencySummary(later).medications)
    }

    @Test
    fun medicationStoppedBeforeItStartedIsLeftOut() {
        assertEquals(emptyList(), names(data(medications = listOf(med("Course", start = LocalDate(2026, 12, 1)).stopped()))))
    }

    @Test
    fun courseThatEndedBeforeTodayIsLeftOutButEndingTodayStays() {
        assertEquals(emptyList(), names(data(medications = listOf(med("Antibiotic", end = LocalDate(2026, 11, 2))))))
        assertEquals(listOf("Antibiotic"), names(data(medications = listOf(med("Antibiotic", end = today)))))
    }

    @Test
    fun courseThatHasNotStartedIsListedWithItsStartDateAfterTheCurrentOnes() {
        val s = data(medications = listOf(med("Alpha", start = LocalDate(2026, 12, 1)), med("Zeta"))).emergencySummary(today)
        assertEquals(listOf("Zeta" to null, "Alpha" to LocalDate(2026, 12, 1)), s.medications.map { it.medication.displayName to it.startsOn })
    }

    @Test
    fun medicationWithoutSchedulesIsStillListed() {
        assertEquals(listOf("Vitamin"), names(data(medications = listOf(Medication(p, "m-v", "Vitamin")))))
    }

    @Test
    fun doseAndInstructionsAreKeptAsEntered() {
        val m = med("Metformin", strength = "850 mg").copy(instructions = "With food")
        val shown = data(medications = listOf(m, med("Nodose", strength = null))).emergencySummary(today).medications.map { it.medication }
        assertEquals("850 mg", shown.first { it.displayName == "Metformin" }.strength)
        assertEquals("With food", shown.first { it.displayName == "Metformin" }.instructions)
        assertNull(shown.first { it.displayName == "Nodose" }.strength)
    }

    @Test
    fun recordsOfAnotherProfileNeverAppear() {
        val s = data(
            allergies = listOf(allergy("Mine"), allergy("Theirs", profile = "B-profile")),
            conditions = listOf(condition("Theirs", ConditionStatus.ONGOING, profile = "B-profile")),
            medications = listOf(med("Theirs", profile = "B-profile")),
        ).emergencySummary(today)
        assertEquals(listOf("Mine"), s.allergies.map { it.substance })
        assertTrue(s.conditions.isEmpty())
        assertTrue(s.medications.isEmpty())
    }

    @Test
    fun onlyResolvedOrStoppedRecordsAreNotReportedAsNothingRecorded() {
        val s = data(conditions = listOf(condition("Chickenpox", ConditionStatus.RESOLVED)), medications = listOf(med("Aspirin").stopped())).emergencySummary(today)
        assertFalse(s.nothingRecorded)
        assertTrue(s.conditions.isEmpty() && s.medications.isEmpty() && s.allergies.isEmpty())
    }

    @Test
    fun routeNameOpensTheCard() {
        assertEquals(AppScreen.EmergencyCard, routeFromName("EmergencyCard"))
    }
}
