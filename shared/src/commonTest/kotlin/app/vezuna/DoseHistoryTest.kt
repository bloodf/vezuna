package app.vezuna

import app.vezuna.domain.DoseCounts
import app.vezuna.domain.DoseEvent
import app.vezuna.domain.DoseEventStatus
import app.vezuna.domain.DoseEvents
import app.vezuna.domain.DoseHistoryFactory
import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.Medication
import app.vezuna.domain.MissedDoses
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.Recurrence
import kotlin.time.Instant
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
        assertEquals(DoseCounts(taken = 2, skipped = 1, missed = 1), history.medications.single().last30Days)
    }

    @Test
    fun emptyHistoryHasNoAdherence() {
        assertNull(DoseHistoryFactory.create(data(), now, zone).last7Days.adherenceFraction)
    }

    @Test
    fun editedTimeAndBackdatedStartAreNotJudgedBeforeTheyExisted() {
        val editedAt = Instant.parse("2026-06-15T08:00:00Z")
        val afterEdit = MissedDoses.pending(data(times = listOf("08:00"), trackedSince = editedAt), now, zone)
        assertEquals(emptyList(), afterEdit)
        val legacyWithoutEvents = MissedDoses.pending(data(trackedSince = null), now, zone)
        assertEquals(emptyList(), legacyWithoutEvents)
    }

    @Test
    fun legacyScheduleWithOldEventIsStampedAndNothingIsBackfilled() {
        val legacy = data(trackedSince = null, events = listOf(event("2026-03-01T09:00:00Z", DoseEventStatus.TAKEN)))
        assertEquals(emptyList(), MissedDoses.pending(legacy, now, zone))
        val stamped = MissedDoses.stampLegacy(legacy, now)
        assertEquals(listOf(now), stamped.map { it.trackedSince })
    }

    @Test
    fun resumedMedicationIsNotJudgedForPausedDays() {
        // Resume stamps trackedSince = now, so the paused days before it stay unjudged.
        assertEquals(emptyList(), MissedDoses.pending(data(trackedSince = now), now, zone))
    }

    @Test
    fun timeZoneShiftDoesNotMarkAnAlreadyRecordedDayMissed() {
        // Recorded at 09:00 in an old zone = 06:00 here; the 09:00 occurrence of that day must not become MISSED.
        val missed = MissedDoses.pending(
            data(events = listOf(event("2026-06-13T06:00:00Z", DoseEventStatus.TAKEN))),
            now,
            zone,
        ).map { it.scheduledFor }
        assertEquals(false, Instant.parse("2026-06-13T09:00:00Z") in missed)
        assertEquals(true, Instant.parse("2026-06-12T09:00:00Z") in missed)
    }

    @Test
    fun multipleTimesAndEndDateAreRespected() {
        val missed = MissedDoses.pending(
            data(times = listOf("09:00", "21:00"), endDate = LocalDate(2026, 6, 12), events = listOf(event("2026-06-12T09:00:00Z", DoseEventStatus.TAKEN))),
            now,
            zone,
        ).map { it.scheduledFor.toString() }
        assertEquals(true, "2026-06-12T21:00:00Z" in missed)
        assertEquals(false, "2026-06-12T09:00:00Z" in missed)
        assertEquals(false, missed.any { it.startsWith("2026-06-13") })
    }

    @Test
    fun applyingMissedTwiceThroughMergeIsIdempotent() {
        val first = MissedDoses.pending(data(), now, zone)
        val stored = first.fold(emptyList<DoseEvent>()) { acc, e -> DoseEvents.merge(acc, e) }
        assertEquals(emptyList(), MissedDoses.pending(data(events = stored), now, zone))
    }

    private fun data(
        events: List<DoseEvent> = emptyList(),
        active: Boolean = true,
        times: List<String> = listOf("09:00"),
        trackedSince: Instant? = Instant.parse("2026-06-01T00:00:00Z"),
        endDate: LocalDate? = null,
    ) = ProfileData(
        profile = Profile("p", "Current", createdAt = now),
        observations = emptyList(),
        medications = listOf(
            Medication(
                profileId = "p",
                medicationId = "m",
                displayName = "Med",
                active = active,
                schedules = listOf(
                    DoseSchedule("p", "s", "m", Recurrence.DAILY, startDate = LocalDate(2026, 6, 1), endDate = endDate, timesLocal = times, trackedSince = trackedSince),
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
