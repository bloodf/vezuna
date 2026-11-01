package app.vezuna

import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.DashboardSnapshotFactory
import app.vezuna.domain.DoseEvent
import app.vezuna.domain.DoseEventStatus
import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.LabObservation
import app.vezuna.domain.Medication
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.Recurrence
import app.vezuna.domain.ReferenceRange
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DashboardSnapshotTest {
    private val now = Instant.parse("2026-06-15T12:00:00Z")
    private val zone = TimeZone.UTC

    @Test
    fun upcomingDosesAreOrderedAcrossSchedulesAndDays() {
        val snapshot = DashboardSnapshotFactory.create(
            data(
                medications = listOf(
                    medication("p", "a", "A", times = listOf("09:00", "13:00")),
                    medication("p", "b", "B", times = listOf("14:00")),
                ),
            ),
            now,
            zone,
        )
        assertEquals(listOf("2026-06-15T13:00:00Z", "2026-06-15T14:00:00Z", "2026-06-16T09:00:00Z"), snapshot.upcoming.map { it.scheduledFor.toString() })
        assertEquals("A", snapshot.nextDose?.medicationName)
    }

    @Test
    fun takenAndSkippedOccurrencesAreFilteredOnlyAtTheirExactTime() {
        val snapshot = DashboardSnapshotFactory.create(
            data(
                medications = listOf(medication("p", "a", "A", times = listOf("09:00", "13:00", "14:00"))),
                events = listOf(
                    event("p", "a", "2026-06-15T13:00:00Z", DoseEventStatus.TAKEN),
                    event("p", "a", "2026-06-15T14:00:00Z", DoseEventStatus.SKIPPED),
                    event("p", "a", "2026-06-16T13:00:00Z", DoseEventStatus.MISSED),
                ),
            ),
            now,
            zone,
        )
        assertEquals(listOf("2026-06-16T09:00:00Z", "2026-06-16T14:00:00Z", "2026-06-17T09:00:00Z"), snapshot.upcoming.map { it.scheduledFor.toString() })
    }

    @Test
    fun snoozeReplacesOriginalOccurrenceWithoutDuplicatingIt() {
        val original = Instant.parse("2026-06-15T13:00:00Z")
        val snoozed = Instant.parse("2026-06-15T13:10:00Z")
        val snapshot = DashboardSnapshotFactory.create(
            data(
                medications = listOf(medication("p", "a", "A", times = listOf("13:00", "13:10"))),
                events = listOf(
                    event("p", "a", snoozed.toString(), DoseEventStatus.SCHEDULED, replaces = original),
                ),
            ),
            now,
            zone,
        )
        assertEquals(listOf("2026-06-15T13:10:00Z", "2026-06-16T13:00:00Z", "2026-06-16T13:10:00Z"), snapshot.upcoming.map { it.scheduledFor.toString() })
    }

    @Test
    fun takenAndSkippedSnoozedOccurrencesAreNotRestored() {
        listOf(DoseEventStatus.TAKEN, DoseEventStatus.SKIPPED).forEach { terminalStatus ->
            val original = Instant.parse("2026-06-15T13:00:00Z")
            val snoozed = Instant.parse("2026-06-15T13:10:00Z")
            val snapshot = DashboardSnapshotFactory.create(
                data(
                    medications = listOf(medication("p", "a", "A")),
                    events = listOf(
                        event("p", "a", snoozed.toString(), DoseEventStatus.SCHEDULED, replaces = original),
                        event("p", "a", snoozed.toString(), terminalStatus),
                    ),
                ),
                now,
                zone,
            )
            assertEquals(listOf("2026-06-16T13:00:00Z", "2026-06-17T13:00:00Z", "2026-06-18T13:00:00Z"), snapshot.upcoming.map { it.scheduledFor.toString() })
        }
    }

    @Test
    fun laterSnoozeSupersedesEarlierSnoozeDeterministically() {
        val original = Instant.parse("2026-06-15T13:00:00Z")
        val firstSnooze = Instant.parse("2026-06-15T13:10:00Z")
        val secondSnooze = Instant.parse("2026-06-15T13:20:00Z")
        val snapshot = DashboardSnapshotFactory.create(
            data(
                medications = listOf(medication("p", "a", "A")),
                events = listOf(
                    event("p", "a", secondSnooze.toString(), DoseEventStatus.SCHEDULED, replaces = firstSnooze),
                    event("p", "a", firstSnooze.toString(), DoseEventStatus.SCHEDULED, replaces = original),
                ),
            ),
            now,
            zone,
        )
        assertEquals(listOf("2026-06-15T13:20:00Z", "2026-06-16T13:00:00Z", "2026-06-17T13:00:00Z"), snapshot.upcoming.map { it.scheduledFor.toString() })
    }

    @Test
    fun otherProfilesCannotSuppressMatchingScheduleOccurrences() {
        val snapshot = DashboardSnapshotFactory.create(
            data(
                medications = listOf(medication("p", "shared", "Current", times = listOf("13:00", "14:00"))),
                events = listOf(
                    event("other", "shared", "2026-06-15T13:00:00Z", DoseEventStatus.TAKEN),
                    event("other", "shared", "2026-06-15T14:10:00Z", DoseEventStatus.SCHEDULED, replaces = Instant.parse("2026-06-15T14:00:00Z")),
                ),
            ),
            now,
            zone,
        )
        assertEquals(listOf("2026-06-15T13:00:00Z", "2026-06-15T14:00:00Z", "2026-06-16T13:00:00Z"), snapshot.upcoming.map { it.scheduledFor.toString() })
    }

    @Test
    fun adherenceUsesTerminalEventsInWindowAndIgnoresScheduledAndOtherProfiles() {
        val events = buildList {
            repeat(5) { index -> add(event("p", "a", "2026-06-0${index + 1}T09:00:00Z", DoseEventStatus.TAKEN)) }
            repeat(2) { index -> add(event("p", "a", "2026-06-0${index + 6}T09:00:00Z", DoseEventStatus.SKIPPED)) }
            add(event("p", "a", "2026-06-08T09:00:00Z", DoseEventStatus.MISSED))
            add(event("p", "a", "2026-06-09T09:00:00Z", DoseEventStatus.SCHEDULED))
            add(event("p", "a", "2026-01-01T09:00:00Z", DoseEventStatus.TAKEN))
            add(event("other", "a", "2026-06-10T09:00:00Z", DoseEventStatus.TAKEN))
            add(event("p", "a", "2026-06-01T09:00:00Z", DoseEventStatus.TAKEN))
        }
        val snapshot = DashboardSnapshotFactory.create(data(events = events), now, zone)
        assertEquals(8, snapshot.adherence.completed)
        assertEquals(5, snapshot.adherence.taken)
        assertEquals(0.625, snapshot.adherence.fraction)
    }

    @Test
    fun emptyAdherenceAndProfileIsolationAreDeterministic() {
        val snapshot = DashboardSnapshotFactory.create(
            data(
                medications = listOf(medication("p", "a", "A"), medication("other", "b", "Other")),
                events = listOf(event("other", "b", "2026-06-10T09:00:00Z", DoseEventStatus.TAKEN)),
            ),
            now,
            zone,
        )
        assertNull(snapshot.adherence.fraction)
        assertEquals(0, snapshot.adherence.completed)
        assertEquals("A", snapshot.nextDose?.medicationName)
        assertEquals(0, snapshot.reviewCount)
    }

    private fun data(
        medications: List<Medication> = emptyList(),
        events: List<DoseEvent> = emptyList(),
    ) = ProfileData(
        profile = Profile("p", "Current", createdAt = now),
        observations = emptyList<LabObservation>(),
        medications = medications,
        doseEvents = events,
    )

    private fun medication(profileId: String, id: String, name: String, times: List<String> = listOf("13:00")) = Medication(
        profileId = profileId,
        medicationId = id,
        displayName = name,
        schedules = listOf(
            DoseSchedule(
                profileId = profileId,
                scheduleId = id,
                medicationId = id,
                recurrence = Recurrence.DAILY,
                startDate = LocalDate(2026, 1, 1),
                timesLocal = times,
            ),
        ),
    )

    private fun event(
        profileId: String,
        scheduleId: String,
        at: String,
        status: DoseEventStatus,
        replaces: Instant? = null,
    ) = DoseEvent(
        profileId = profileId,
        eventId = "$profileId-$scheduleId-$at-$status",
        medicationId = scheduleId,
        scheduleId = scheduleId,
        scheduledFor = Instant.parse(at),
        status = status,
        replacesScheduledFor = replaces,
    )
}
