package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.domain.DoseEvent
import app.vezuna.domain.DoseEventStatus
import app.vezuna.domain.DoseEvents
import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.Medication
import app.vezuna.domain.Profile
import app.vezuna.domain.Recurrence
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class DoseEventsTest {
    private val at = Instant.parse("2026-11-01T12:00:00Z")

    @Test
    fun idDependsOnlyOnScheduleAndScheduledTime() {
        assertEquals("dose-s1-${at.epochSeconds}", DoseEvents.idFor("s1", at))
        assertEquals(DoseEvents.idFor("s1", at), DoseEvents.idFor("s1", Instant.fromEpochMilliseconds(at.toEpochMilliseconds() + 400)))
    }

    @Test
    fun takenSkippedAndMissedFromAnyPathCollapseToOneTerminalEvent() = runTest {
        val repository = repositoryWithSchedule()
        // In-app, Android action and iOS bridge all derive the id with the shared helper.
        repository.addDoseEvent(event(DoseEventStatus.TAKEN))
        repository.addDoseEvent(event(DoseEventStatus.TAKEN))
        repository.addDoseEvent(event(DoseEventStatus.SKIPPED))
        repository.addDoseEvent(event(DoseEventStatus.MISSED))

        val events = repository.readProfile("p").doseEvents
        assertEquals(listOf(DoseEventStatus.TAKEN), events.map { it.status })
    }

    @Test
    fun userActionReplacesEarlierMissedButMissedNeverOverridesUserAction() = runTest {
        val repository = repositoryWithSchedule()
        repository.addDoseEvent(event(DoseEventStatus.MISSED))
        repository.addDoseEvent(event(DoseEventStatus.SKIPPED))
        repository.addDoseEvent(event(DoseEventStatus.MISSED))

        assertEquals(listOf(DoseEventStatus.SKIPPED), repository.readProfile("p").doseEvents.map { it.status })
    }

    @Test
    fun snoozeMarkerDoesNotCollideWithTerminalEventOfSnoozedDose() = runTest {
        val repository = repositoryWithSchedule()
        val snoozedFor = Instant.fromEpochSeconds(at.epochSeconds + 600)
        repository.addDoseEvent(event(DoseEventStatus.SCHEDULED, DoseEvents.snoozeIdFor("s", snoozedFor), snoozedFor))
        repository.addDoseEvent(event(DoseEventStatus.TAKEN, scheduledFor = snoozedFor))

        assertEquals(listOf(DoseEventStatus.SCHEDULED, DoseEventStatus.TAKEN), repository.readProfile("p").doseEvents.map { it.status })
    }

    private fun event(status: DoseEventStatus, id: String = DoseEvents.idFor("s", at), scheduledFor: Instant = at) =
        DoseEvent("p", if (status == DoseEventStatus.SCHEDULED) id else DoseEvents.idFor("s", scheduledFor), "m", "s", scheduledFor, status, at)

    private suspend fun repositoryWithSchedule() = InMemoryVezunaRepository().also { repository ->
        repository.createProfile(Profile("p", "P", createdAt = at))
        repository.addMedication(
            Medication("p", "m", "Med", schedules = listOf(DoseSchedule("p", "s", "m", Recurrence.DAILY, startDate = LocalDate(2026, 1, 1), timesLocal = listOf("09:00")))),
        )
    }
}
