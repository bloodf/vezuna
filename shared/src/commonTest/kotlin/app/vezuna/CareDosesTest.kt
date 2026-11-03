package app.vezuna

import app.vezuna.domain.DoseEvent
import app.vezuna.domain.DoseEventStatus
import app.vezuna.domain.DoseEvents
import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.Medication
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.Recurrence
import app.vezuna.ui.ScheduleDraft
import app.vezuna.ui.care.CareDoses
import app.vezuna.ui.care.DoseState
import app.vezuna.ui.care.MedicationStatus
import app.vezuna.ui.care.medicationFormValid
import app.vezuna.ui.care.status
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CareDosesTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val zone = TimeZone.UTC
    private val schedule = DoseSchedule("p", "s", "m", Recurrence.DAILY, startDate = LocalDate(2026, 10, 1), timesLocal = listOf("08:00", "20:00"))

    private fun data(events: List<DoseEvent> = emptyList(), active: Boolean = true, endDate: LocalDate? = null) = ProfileData(
        profile = Profile("p", "Ana", createdAt = Instant.parse("2026-01-01T00:00:00Z")),
        medications = listOf(Medication("p", "m", "Vitamin D", "1,000 IU", "1 capsule", listOf(schedule.copy(endDate = endDate)), active)),
        doseEvents = events,
    )

    private fun event(at: String, status: DoseEventStatus, recorded: String = at, replaces: Instant? = null): DoseEvent {
        val time = Instant.parse(at)
        val id = if (status == DoseEventStatus.SCHEDULED) DoseEvents.snoozeIdFor("s", time) else DoseEvents.idFor("s", time)
        return DoseEvent("p", id, "m", "s", time, status, Instant.parse(recorded), replaces)
    }

    @Test
    fun todayListsEveryDoseWithItsRecordedState() {
        val doses = CareDoses.today(data(listOf(event("2026-11-01T08:00:00Z", DoseEventStatus.TAKEN, recorded = "2026-11-01T08:04:00Z"))), now, zone)
        assertEquals(listOf(DoseState.TAKEN, DoseState.PENDING), doses.map { it.state })
        assertEquals(Instant.parse("2026-11-01T08:04:00Z"), doses.first().recordedAt)
        assertEquals("Vitamin D", doses.first().dose.medicationName)
    }

    @Test
    fun snoozedDoseAppearsOnceAtItsNewTime() {
        val original = Instant.parse("2026-11-01T08:00:00Z")
        val doses = CareDoses.today(data(listOf(event("2026-11-01T08:10:00Z", DoseEventStatus.SCHEDULED, replaces = original))), now, zone)
        assertEquals(listOf("2026-11-01T08:10:00Z" to DoseState.SNOOZED, "2026-11-01T20:00:00Z" to DoseState.PENDING), doses.map { it.dose.scheduledFor.toString() to it.state })
    }

    @Test
    fun anOldRecordAtAWholeMinuteSnoozeTimeStaysThere() {
        val original = Instant.parse("2026-11-01T08:00:00Z")
        val events = listOf(
            event("2026-11-01T08:10:00Z", DoseEventStatus.SCHEDULED, replaces = original),
            event("2026-11-01T08:10:00Z", DoseEventStatus.TAKEN, recorded = "2026-11-01T08:12:00Z"),
        )
        val first = CareDoses.today(data(events), now, zone).first()
        assertEquals(DoseState.TAKEN, first.state)
        assertEquals(Instant.parse("2026-11-01T08:10:00Z"), first.dose.scheduledFor)
    }

    @Test
    fun latestUserActionWinsAndReplacesMissed() {
        val events = listOf(
            event("2026-10-31T08:00:00Z", DoseEventStatus.MISSED, recorded = "2026-11-01T08:30:00Z"),
            event("2026-10-31T08:00:00Z", DoseEventStatus.TAKEN, recorded = "2026-11-01T09:00:00Z"),
            event("2026-10-30T08:00:00Z", DoseEventStatus.SKIPPED, recorded = "2026-10-30T08:01:00Z"),
            event("2026-10-30T08:00:00Z", DoseEventStatus.TAKEN, recorded = "2026-10-30T08:05:00Z"),
        )
        val recorded = CareDoses.recorded(data(events))
        assertEquals(listOf(DoseState.TAKEN, DoseState.TAKEN), recorded.map { it.state })
        assertEquals(Instant.parse("2026-10-31T08:00:00Z"), recorded.first().dose.scheduledFor)
    }

    @Test
    fun pausedMedicationHasNoDosesTodayButKeepsHistory() {
        val events = listOf(event("2026-10-31T08:00:00Z", DoseEventStatus.TAKEN))
        assertEquals(emptyList(), CareDoses.today(data(events, active = false), now, zone))
        assertEquals(1, CareDoses.recorded(data(events, active = false)).size)
    }

    @Test
    fun countsAndRangeAreDescriptive() {
        val events = listOf(
            event("2026-10-31T08:00:00Z", DoseEventStatus.TAKEN),
            event("2026-10-30T08:00:00Z", DoseEventStatus.SKIPPED),
            event("2026-10-29T08:00:00Z", DoseEventStatus.MISSED),
            event("2026-06-01T08:00:00Z", DoseEventStatus.TAKEN),
        )
        val all = CareDoses.recorded(data(events))
        val recent = CareDoses.within(all, 3, now, zone)
        assertEquals(3, recent.size)
        val counts = CareDoses.counts(recent)
        assertEquals(Triple(1, 1, 1), Triple(counts.taken, counts.skipped, counts.missed))
        assertEquals(3, counts.completed)
        assertEquals(4, CareDoses.within(all, null, now, zone).size)
    }

    @Test
    fun activePausedAndStoppedStayDistinct() {
        assertEquals(MedicationStatus.ACTIVE, data().medications.single().status)
        assertEquals(MedicationStatus.PAUSED, data(active = false).medications.single().status)
        assertEquals(MedicationStatus.STOPPED, data(active = false, endDate = LocalDate(2026, 11, 1)).medications.single().status)
    }

    @Test
    fun formNeedsANameAndValidSchedules() {
        val good = ScheduleDraft(startDate = LocalDate(2026, 11, 1))
        assertTrue(medicationFormValid("Vitamin D", listOf(good)))
        assertFalse(medicationFormValid(" ", listOf(good)))
        assertFalse(medicationFormValid("Vitamin D", listOf(good.copy(times = listOf("25:70")))))
        assertFalse(medicationFormValid("Vitamin D", emptyList()))
    }
}
