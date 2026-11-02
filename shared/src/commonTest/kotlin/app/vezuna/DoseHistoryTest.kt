package app.vezuna

import app.vezuna.domain.DoseCounts
import app.vezuna.domain.DoseEvent
import app.vezuna.domain.DoseEventStatus
import app.vezuna.domain.DoseHistoryFactory
import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.Medication
import app.vezuna.domain.MissedDoses
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.Recurrence
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DoseHistoryTest {
    private val now = Instant.parse("2026-06-15T12:00:00Z")
    private val zone = TimeZone.UTC

    @Test
    fun doseOlderThanGraceWithoutTerminalEventIsMissedOnce() {
        val missed = MissedDoses.pending(data(), now, zone)
        // 14th 09:00 is 27h ago (missed); 14th 13:00 and later are inside the 24h grace.
        assertEquals(true, missed.all { it.status == DoseEventStatus.MISSED && it.scheduledFor.epochSeconds <= now.epochSeconds - MissedDoses.GRACE_SECONDS })
        assertEquals("2026-06-14T09:00:00Z", missed.maxOf { it.scheduledFor }.toString())
        assertEquals(missed.size, missed.map { it.eventId }.toSet().size)
    }

    @Test
    fun recordedAndSnoozedDosesAreNotMissed() {
        val original = Instant.parse("2026-06-14T09:00:00Z")
        val snoozedAt = Instant.parse("2026-06-14T09:10:00Z")
        val events = listOf(
            event("2026-06-13T09:00:00Z", DoseEventStatus.TAKEN),
            event("2026-06-14T09:10:00Z", DoseEventStatus.SCHEDULED, replaces = original),
        )
        val missed = MissedDoses.pending(data(events = events), now, zone).map { it.scheduledFor }
        assertEquals(false, Instant.parse("2026-06-13T09:00:00Z") in missed)
        assertEquals(false, original in missed)
        assertEquals(true, snoozedAt in missed)
    }

    @Test
    fun pausedMedicationHasNoMissedDoses() {
        assertEquals(emptyList(), MissedDoses.pending(data(active = false), now, zone))
    }

    @Test
    fun historyCountsPerMedicationAndWindow() {
        val events = listOf(
            event("2026-06-14T09:00:00Z", DoseEventStatus.TAKEN),
            event("2026-06-13T09:00:00Z", DoseEventStatus.SKIPPED),
            event("2026-06-12T09:00:00Z", DoseEventStatus.MISSED),
            event("2026-06-01T09:00:00Z", DoseEventStatus.TAKEN),
            event("2026-04-01T09:00:00Z", DoseEventStatus.TAKEN),
        )
        val history = DoseHistoryFactory.create(data(events = events), now, zone)
        assertEquals(DoseCounts(taken = 1, skipped = 1, missed = 1), history.last7Days)
        assertEquals(DoseCounts(taken = 2, skipped = 1, missed = 1), history.last30Days)
        assertEquals(1.0 / 3.0, history.last7Days.adherenceFraction)
        assertEquals(5, history.medications.single().events.size)
        assertEquals("2026-06-14T09:00:00Z", history.medications.single().events.first().scheduledFor.toString())
    }

    @Test
    fun emptyHistoryHasNoAdherence() {
        assertNull(DoseHistoryFactory.create(data(), now, zone).last7Days.adherenceFraction)
    }

    private fun data(events: List<DoseEvent> = emptyList(), active: Boolean = true) = ProfileData(
        profile = Profile("p", "Current", createdAt = now),
        observations = emptyList(),
        medications = listOf(
            Medication(
                profileId = "p",
                medicationId = "m",
                displayName = "Med",
                active = active,
                schedules = listOf(
                    DoseSchedule("p", "s", "m", Recurrence.DAILY, startDate = LocalDate(2026, 6, 1), timesLocal = listOf("09:00")),
                ),
            ),
        ),
        doseEvents = events,
    )

    private fun event(at: String, status: DoseEventStatus, replaces: Instant? = null) = DoseEvent(
        profileId = "p",
        eventId = "e-$at-$status",
        medicationId = "m",
        scheduleId = "s",
        scheduledFor = Instant.parse(at),
        status = status,
        recordedAt = now,
        replacesScheduledFor = replaces,
    )
}
