package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.domain.ConditionStatus
import app.vezuna.domain.emergencySummary
import app.vezuna.ui.DesignFixture
import app.vezuna.ui.EmergencyPreviewFixture
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmergencyPreviewFixtureTest {
    private val today = LocalDate(2026, 11, 3)

    private suspend fun summary(variant: String) = InMemoryVezunaRepository().let {
        EmergencyPreviewFixture.install(it, variant)
        it.readProfile(DesignFixture.PROFILE_ID).emergencySummary(today)
    }

    @Test
    fun fullHasASevereAllergyAndEveryMedicationKind() = runTest {
        val s = summary("full")
        assertTrue(s.allergies.any { it.substance == "Peanut" })
        assertTrue(s.conditions.any { it.status == null })
        val meds = s.medications.associateBy { it.medication.displayName }
        assertTrue(meds.getValue("Warfarin").paused)
        assertEquals(LocalDate(2027, 1, 15), meds.getValue("Amoxicillin").startsOn)
        assertEquals(null, meds.getValue("Rescue inhaler").medication.strength)
    }

    @Test
    fun medsKeepsOneAllergyAndOnlyTheThreeMedications() = runTest {
        val s = summary("meds")
        assertEquals(listOf("Peanut"), s.allergies.map { it.substance })
        assertEquals(setOf("Amoxicillin", "Rescue inhaler", "Warfarin"), s.medications.map { it.medication.displayName }.toSet())
    }

    @Test
    fun partialHasRecordsButNoneCurrent() = runTest {
        val s = summary("partial")
        assertFalse(s.nothingRecorded)
        assertTrue(s.allergies.isEmpty() && s.medications.isEmpty())
        assertTrue(s.conditions.none { it.status != ConditionStatus.RESOLVED })
    }

    @Test
    fun emptyHasNothingRecorded() = runTest {
        assertTrue(summary("empty").nothingRecorded)
    }

    @Test
    fun laterHasOnlyTheCourseThatStartsLater() = runTest {
        val s = summary("later")
        assertEquals(listOf(LocalDate(2027, 1, 15)), s.medications.map { it.startsOn })
    }
}
