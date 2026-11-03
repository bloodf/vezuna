package app.vezuna

import app.vezuna.ui.CoverageLevel
import app.vezuna.ui.ReminderPlatformState
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReminderPlatformStateTest {
    @Test
    fun hidingNamesIsPersistedAndObservable() {
        val saved = mutableListOf<Boolean>()
        val state = ReminderPlatformState(persistHideMedicationNames = { saved += it })
        assertFalse(state.hideMedicationNames.value)
        state.setHideMedicationNames(true)
        assertTrue(state.hideMedicationNames.value)
        assertEquals(listOf(true), saved)
    }

    @Test
    fun startsFromTheStoredPreference() {
        assertTrue(ReminderPlatformState(hideMedicationNames = true).hideMedicationNames.value)
    }

    private val now = Instant.parse("2026-11-01T12:00:00Z")

    @Test
    fun coverageOfFourteenDaysOrMoreNeedsNoNote() {
        assertEquals(CoverageLevel.OK, ReminderPlatformState.coverageLevel(Instant.parse("2026-11-15T12:00:00Z"), now))
    }

    @Test
    fun coverageUnderFourteenDaysIsOnlyAnInformationalNote() {
        assertEquals(CoverageLevel.SHORT, ReminderPlatformState.coverageLevel(Instant.parse("2026-11-15T11:59:59Z"), now))
        assertEquals(CoverageLevel.SHORT, ReminderPlatformState.coverageLevel(Instant.parse("2026-11-04T12:00:00Z"), now))
    }

    @Test
    fun coverageUnderThreeDaysIsUrgent() {
        assertEquals(CoverageLevel.URGENT, ReminderPlatformState.coverageLevel(Instant.parse("2026-11-04T11:59:59Z"), now))
        assertEquals(CoverageLevel.URGENT, ReminderPlatformState.coverageLevel(Instant.parse("2026-10-31T12:00:00Z"), now))
    }

    @Test
    fun noCoverageIsReportedUntilThePlatformSetsIt() {
        assertEquals(null, ReminderPlatformState().coverageUntil.value)
    }
}
