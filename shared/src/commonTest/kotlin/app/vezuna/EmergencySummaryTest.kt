package app.vezuna

import app.vezuna.domain.AllergyRecord
import app.vezuna.domain.AllergySeverity
import app.vezuna.domain.ConditionRecord
import app.vezuna.domain.ConditionStatus
import app.vezuna.domain.Medication
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
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
    private fun med(name: String, active: Boolean = true, profile: String = p) = Medication(profile, "m-$name", name, strength = "10 mg", active = active)

    @Test
    fun emptyProfileIsEmptyAndKeepsNameAndAge() {
        val s = data().emergencySummary(today)
        assertTrue(s.isEmpty)
        assertEquals("Ana", s.profileName)
        assertEquals(35, s.ageYears)
    }

    @Test
    fun unknownBirthDateGivesNoAge() {
        val s = data(birth = null).emergencySummary(today)
        assertNull(s.birthDate)
        assertNull(s.ageYears)
    }

    @Test
    fun allergiesAreMostSevereFirstThenUnstatedThenByName() {
        val s = data(allergies = listOf(allergy("Pollen"), allergy("Latex", AllergySeverity.MILD), allergy("Peanut", AllergySeverity.SEVERE), allergy("Dust", AllergySeverity.MILD), allergy("Shellfish", AllergySeverity.MODERATE)))
            .emergencySummary(today)
        assertEquals(listOf("Peanut", "Shellfish", "Dust", "Latex", "Pollen"), s.allergies.map { it.substance })
    }

    @Test
    fun resolvedConditionsAreLeftOutAndUnstatedOnesStay() {
        val s = data(conditions = listOf(condition("Asthma", ConditionStatus.ONGOING), condition("Chickenpox", ConditionStatus.RESOLVED), condition("Anemia", null)))
            .emergencySummary(today)
        assertEquals(listOf("Anemia", "Asthma"), s.conditions.map { it.name })
    }

    @Test
    fun onlyActiveMedicationsAreIncludedByName() {
        val s = data(medications = listOf(med("Metformin"), med("Aspirin", active = false), med("Atenolol"))).emergencySummary(today)
        assertEquals(listOf("Atenolol", "Metformin"), s.medications.map { it.displayName })
        assertFalse(s.isEmpty)
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
    fun onlyResolvedOrStoppedRecordsStillCountAsNothingRecorded() {
        val s = data(conditions = listOf(condition("Chickenpox", ConditionStatus.RESOLVED)), medications = listOf(med("Aspirin", active = false))).emergencySummary(today)
        assertTrue(s.isEmpty)
    }

    @Test
    fun routeNameOpensTheCard() {
        assertEquals(AppScreen.EmergencyCard, routeFromName("EmergencyCard"))
    }
}
