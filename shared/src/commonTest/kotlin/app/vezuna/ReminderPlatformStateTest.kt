package app.vezuna

import app.vezuna.ui.ReminderPlatformState
import kotlinx.datetime.Instant
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
    fun coverageIsLowUnderThreeDays() {
        val now = Instant.parse("2026-11-01T12:00:00Z")
        assertTrue(ReminderPlatformState.isCoverageLow(Instant.parse("2026-11-04T11:59:59Z"), now))
        assertFalse(ReminderPlatformState.isCoverageLow(Instant.parse("2026-11-04T12:00:00Z"), now))
    }
}
