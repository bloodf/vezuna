package app.vezuna

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

    @Test
    fun coverageIsLowUnderFourteenDays() {
        val now = Instant.parse("2026-11-01T12:00:00Z")
        assertTrue(ReminderPlatformState.isCoverageLow(Instant.parse("2026-11-15T11:59:59Z"), now))
        assertFalse(ReminderPlatformState.isCoverageLow(Instant.parse("2026-11-15T12:00:00Z"), now))
    }

    @Test
    fun noCoverageIsReportedUntilThePlatformSetsIt() {
        // Android never schedules a limited window, so it never sets coverage and so never shows the low-coverage warning.
        assertEquals(null, ReminderPlatformState().coverageUntil.value)
    }
}
