package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.data.VaultBackedRepository
import app.vezuna.domain.DashboardSnapshotFactory
import app.vezuna.domain.DoseEvent
import app.vezuna.domain.DoseEventStatus
import app.vezuna.domain.DoseEvents
import app.vezuna.domain.DoseHistoryFactory
import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.Medication
import app.vezuna.domain.MedicationSupplies
import app.vezuna.domain.MedicationSupply
import app.vezuna.domain.MissedDoses
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.Recurrence
import app.vezuna.export.DoctorExportContent
import app.vezuna.export.ExportOptions
import app.vezuna.ui.care.CareDoses
import app.vezuna.ui.care.DoseState
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** One real dose, however it was snoozed and whichever path reported it, is recorded and shown once. */
class SnoozedDoseTest {
    private val zone = TimeZone.UTC
    private val original = Instant.parse("2026-11-01T08:00:00Z")
    private val snoozed = Instant.parse("2026-11-01T08:10:07Z")
    private val snoozedAgain = Instant.parse("2026-11-01T08:20:09Z")
    private val takenAt = Instant.parse("2026-11-01T08:21:00Z")
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val schedule = DoseSchedule("p", "s", "m", Recurrence.DAILY, startDate = LocalDate(2026, 10, 1), timesLocal = listOf("08:00", "20:00"), trackedSince = Instant.parse("2026-10-01T00:00:00Z"))
    private val second = schedule.copy(scheduleId = "s2")
    private val medication = Medication("p", "m", "Vitamin D", schedules = listOf(schedule, second), supply = MedicationSupply(10, Instant.parse("2026-10-01T00:00:00Z")))

    private fun data(events: List<DoseEvent>) = ProfileData(Profile("p", "Ana", createdAt = original), medications = listOf(medication), doseEvents = events)

    private fun snooze(at: Instant, replaces: Instant, scheduleId: String = "s") =
        DoseEvent("p", DoseEvents.snoozeIdFor(scheduleId, at), "m", scheduleId, at, DoseEventStatus.SCHEDULED, replaces, replaces)

    private fun terminal(at: Instant, status: DoseEventStatus = DoseEventStatus.TAKEN, recorded: Instant = takenAt, scheduleId: String = "s") =
        DoseEvent("p", DoseEvents.idFor(scheduleId, at), "m", scheduleId, at, status, recorded)

    private fun record(vararg events: DoseEvent) = events.fold(emptyList<DoseEvent>(), DoseEvents::merge)

    private fun assertOneRow(events: List<DoseEvent>, state: DoseState = DoseState.TAKEN, recordedAt: Instant = takenAt) {
        val rows = CareDoses.recorded(data(events))
        assertEquals(listOf(original to state), rows.map { it.dose.scheduledFor to it.state })
        assertEquals(recordedAt, rows.single().recordedAt)
        val today = CareDoses.today(data(events), now, zone).filter { it.dose.scheduleId == "s" && it.dose.scheduledFor < Instant.parse("2026-11-01T20:00:00Z") }
        assertEquals(listOf(original to state), today.map { it.dose.scheduledFor to it.state })
    }

    @Test
    fun snoozeThenTakeFromTheNotificationPlusInAppTakeIsOneRow() {
        val events = record(snooze(snoozed, original), terminal(snoozed), terminal(original, recorded = takenAt + 60.seconds))
        // recordedAt stays the real first time; scheduledFor the original.
        assertEquals(listOf(DoseEventStatus.SCHEDULED, DoseEventStatus.TAKEN), events.map { it.status })
        assertOneRow(events)
        assertEquals(9, MedicationSupplies.remaining(medication, events))
        assertEquals(1, DoctorExportContent.doseRows(data(events), ExportOptions(), zone).size)
        assertEquals(original, DoctorExportContent.doseRows(data(events), ExportOptions(), zone).single().let { Instant.parse("${it.date}T${it.time}:00Z") })
        assertEquals(1, DoseHistoryFactory.create(data(events), now, zone).last7Days.taken)
    }

    @Test
    fun aSecondSnoozeDoesNotDrift() {
        val events = record(snooze(snoozed, original), snooze(snoozedAgain, snoozed), terminal(snoozedAgain))
        assertEquals(original, events.last().scheduledFor)
        assertEquals(DoseEvents.idFor("s", original), events.last().eventId)
        assertOneRow(events)
        assertEquals(9, MedicationSupplies.remaining(medication, events))
    }

    @Test
    fun takeThenUndoAndSkipAfterSnoozeStayOneRowAndGiveTheUnitBack() {
        val taken = record(snooze(snoozed, original), terminal(snoozed))
        val undone = DoseEvents.merge(taken, terminal(snoozed, DoseEventStatus.SKIPPED, takenAt + 30.seconds))
        assertOneRow(undone, DoseState.SKIPPED, takenAt + 30.seconds)
        assertEquals(10, MedicationSupplies.remaining(medication, undone))
        val retaken = DoseEvents.merge(undone, terminal(original, DoseEventStatus.TAKEN, takenAt + 60.seconds))
        assertOneRow(retaken, DoseState.TAKEN, takenAt + 60.seconds)
        assertEquals(9, MedicationSupplies.remaining(medication, retaken))
    }

    @Test
    fun aSnoozeIsOpenUntilItsDoseIsRecordedAndIsNeverMissedOnceRecorded() {
        val marker = snooze(snoozed, original)
        assertTrue(DoseEvents.isOpen(listOf(marker), marker))
        assertFalse(DoseEvents.isOpen(record(marker, terminal(snoozed)), marker), "recorded from the notification")
        assertFalse(DoseEvents.isOpen(record(marker, terminal(original)), marker), "recorded in the app")
        assertFalse(DoseEvents.isOpen(listOf(marker, snooze(snoozedAgain, snoozed)), marker), "snoozed again")
        // While waiting: one row at the snoozed time, and the original is not missed.
        val waiting = CareDoses.today(data(listOf(marker)), now, zone).filter { it.dose.scheduleId == "s" && it.dose.scheduledFor < Instant.parse("2026-11-01T20:00:00Z") }
        assertEquals(listOf(snoozed to DoseState.SNOOZED), waiting.map { it.dose.scheduledFor to it.state })
        val dayLater = Instant.parse("2026-11-02T08:30:00Z")
        val missed = MissedDoses.pending(data(listOf(marker)), dayLater, zone).filter { it.scheduleId == "s" }
        assertFalse(missed.any { it.scheduledFor == original })
        assertTrue(missed.any { it.scheduledFor == snoozed }, "judged at the snoozed time")
        // Recorded as missed, it is filed at the original time and judged only once.
        val recorded = missed.fold(listOf(marker), DoseEvents::merge)
        assertTrue(recorded.any { it.status == DoseEventStatus.MISSED && it.scheduledFor == original })
        assertFalse(MissedDoses.pending(data(recorded), dayLater, zone).any { it.scheduleId == "s" && (it.scheduledFor == snoozed || it.scheduledFor == original) })
        // Taken: no MISSED appears for the snooze.
        assertFalse(MissedDoses.pending(data(record(marker, terminal(snoozed))), dayLater, zone).any { it.scheduleId == "s" && (it.scheduledFor == snoozed || it.scheduledFor == original) })
    }

    @Test
    fun theHomeScreenForgetsARecordedSnooze() {
        val future = Instant.parse("2026-11-01T08:10:07Z")
        val beforeSnoozeFires = Instant.parse("2026-11-01T08:05:00Z")
        val waiting = listOf(snooze(future, original))
        assertTrue(DashboardSnapshotFactory.create(data(waiting), beforeSnoozeFires, zone).upcoming.any { it.scheduledFor == future })
        val taken = record(waiting.single(), terminal(original, recorded = beforeSnoozeFires))
        assertFalse(DashboardSnapshotFactory.create(data(taken), beforeSnoozeFires, zone).upcoming.any { it.scheduledFor == future })
    }

    @Test
    fun twoSchedulesAtTheSameTimeAreTwoDoses() {
        val events = record(snooze(snoozed, original), terminal(snoozed), terminal(original, scheduleId = "s2"), terminal(snoozed, scheduleId = "s2"))
        val rows = CareDoses.recorded(data(events))
        assertEquals(setOf(original to "s", original to "s2", snoozed to "s2"), rows.map { it.dose.scheduledFor to it.dose.scheduleId }.toSet())
    }

    @Test
    fun oldDuplicatesReadAsOneRowAndAreNotRewritten() = runTest {
        val old = listOf(snooze(snoozed, original), terminal(original), terminal(snoozed, recorded = takenAt + 60.seconds))
        assertOneRow(old, recordedAt = takenAt)
        assertEquals(9, MedicationSupplies.remaining(medication, old))
        assertEquals(old.toSet(), DoseEvents.collapse(old).toSet(), "the storage-level collapse keeps both")

        val vault = GenerationalMemoryVault()
        val first = VaultBackedRepository.open(vault)
        first.createProfile(Profile("p", "Ana", createdAt = original))
        first.addMedication(medication)
        // Terminal events first, so they are stored where old builds put them.
        first.addDoseEvent(terminal(original))
        first.addDoseEvent(terminal(snoozed, recorded = takenAt + 60.seconds))
        first.addDoseEvent(snooze(snoozed, original))
        val reopened = VaultBackedRepository.open(vault).readProfile("p").doseEvents
        assertEquals(old.toSet(), reopened.toSet())
    }

    @Test
    fun anOldEventOnAWholeMinuteIsNotProvenToBeTheSnoozeSoItIsNotMerged() {
        // 08:10:00 can be a real dose of a schedule with both times: nothing in old data says otherwise.
        val whole = Instant.parse("2026-11-01T08:10:00Z")
        val events = listOf(snooze(whole, original), terminal(original), terminal(whole))
        assertEquals(setOf(original, whole), CareDoses.recorded(data(events)).map { it.dose.scheduledFor }.toSet())
        // Supply counts exactly as it did before this view existed.
        assertEquals(9, MedicationSupplies.remaining(medication, events))
        // A new write at that time stays too, but a closed marker is closed either way.
        assertEquals(whole, DoseEvents.merge(listOf(snooze(whole, original)), terminal(whole)).last().scheduledFor)
        assertFalse(DoseEvents.isOpen(listOf(snooze(whole, original), terminal(original)), snooze(whole, original)))
    }

    @Test
    fun aRowFromOldMixedDuplicatesCanAlwaysBeCorrected() {
        val marker = snooze(snoozed, original)
        // Shown SKIPPED, the latest action, with TAKEN stored at the original time.
        val shownSkipped = listOf(marker, terminal(original), terminal(snoozed, DoseEventStatus.SKIPPED, takenAt + 30.seconds))
        assertOneRow(shownSkipped, DoseState.SKIPPED, takenAt + 30.seconds)
        assertEquals(10, MedicationSupplies.remaining(medication, shownSkipped))
        val changedToTaken = DoseEvents.merge(shownSkipped, terminal(original, recorded = takenAt + 60.seconds))
        assertOneRow(changedToTaken, DoseState.TAKEN, takenAt + 60.seconds)
        assertEquals(9, MedicationSupplies.remaining(medication, changedToTaken))
        assertTrue(terminal(snoozed, DoseEventStatus.SKIPPED, takenAt + 30.seconds) in changedToTaken, "the old event stays stored")

        // The mirror: shown TAKEN, with SKIPPED stored at the original time.
        val shownTaken = listOf(marker, terminal(original, DoseEventStatus.SKIPPED), terminal(snoozed, recorded = takenAt + 30.seconds))
        assertEquals(9, MedicationSupplies.remaining(medication, shownTaken))
        val changedToSkipped = DoseEvents.merge(shownTaken, terminal(original, DoseEventStatus.SKIPPED, takenAt + 60.seconds))
        assertOneRow(changedToSkipped, DoseState.SKIPPED, takenAt + 60.seconds)
        assertEquals(10, MedicationSupplies.remaining(medication, changedToSkipped))

        // Repeating what is shown changes nothing, and MISSED never lands on a record.
        assertEquals(shownSkipped, DoseEvents.merge(shownSkipped, terminal(snoozed, DoseEventStatus.SKIPPED, takenAt + 60.seconds)))
        assertEquals(shownTaken, DoseEvents.merge(shownTaken, terminal(original, recorded = takenAt + 60.seconds)))
        assertEquals(shownTaken, DoseEvents.merge(shownTaken, terminal(snoozed, DoseEventStatus.MISSED, takenAt + 60.seconds)))
    }

    @Test
    fun aRealDoseSnoozedAfterAnOldMarkerNamedItsTimeStaysItsOwnDose() {
        // An old snooze of 08:00 landed exactly on 12:00:00, also a real dose of this schedule. That real dose is snoozed now.
        val real = Instant.parse("2026-11-01T12:00:00Z")
        val newSnooze = Instant.parse("2026-11-01T12:10:07Z")
        val oldMarker = snooze(real, original)
        val marker = snooze(newSnooze, real)
        val events = listOf(oldMarker, terminal(original), marker)
        assertEquals(real, DoseEvents.originals(events)("s", newSnooze))
        assertTrue(DoseEvents.isOpen(events, marker), "the first dose being recorded does not close the second")

        val after = DoseEvents.merge(events, terminal(newSnooze, recorded = newSnooze + 60.seconds))
        assertEquals(setOf(original, real), CareDoses.recorded(data(after)).map { it.dose.scheduledFor }.toSet())
        assertFalse(DoseEvents.isOpen(after, marker))
    }

    @Test
    fun oldDuplicatesKeepTheFirstTimeOfConsecutiveTapsButTheCountKeepsItsMeaning() {
        val marker = snooze(snoozed, original)
        val first = takenAt
        val second = takenAt + 60.seconds
        assertOneRow(listOf(marker, terminal(snoozed, recorded = second), terminal(original, recorded = first)), recordedAt = first)
        // A recount between the two taps still counts the dose as used after it, as before.
        val recounted = medication.copy(supply = MedicationSupply(10, first + 30.seconds))
        assertEquals(9, MedicationSupplies.remaining(recounted, listOf(marker, terminal(original, recorded = first), terminal(snoozed, recorded = second))))
    }

    @Test
    fun theExportNamesTheRecordedDateOnlyWhenItIsNotTheRowsDate() {
        val sameDay = DoctorExportContent.doseRows(data(listOf(terminal(original))), ExportOptions(), zone).single()
        assertEquals(null, sameDay.recordedDate)
        val nextDay = DoctorExportContent.doseRows(data(listOf(terminal(original, recorded = Instant.parse("2026-11-02T00:06:00Z")))), ExportOptions(), zone).single()
        assertEquals(LocalDate(2026, 11, 2), nextDay.recordedDate)
        assertEquals("00:06", nextDay.recordedTime)
    }

    @Test
    fun theSupplyCountIgnoresOtherMedications() {
        val other = DoseEvent("p", "o", "other", "s9", original, DoseEventStatus.TAKEN, takenAt)
        assertEquals(10, MedicationSupplies.remaining(medication, listOf(other)))
    }

    @Test
    fun aSecondDoseSnoozedToATakenSecondMovesOnAndTheSameSnoozeStaysPut() {
        val target = Instant.parse("2026-11-01T08:10:01Z")
        val first = snooze(target, original)
        val another = Instant.parse("2026-11-01T20:00:00Z")
        assertEquals(target, DoseEvents.snoozeSlot(emptyList(), "s", original, target))
        assertEquals(target + 1.seconds, DoseEvents.snoozeSlot(listOf(first), "s", another, target), "a different dose")
        assertEquals(target, DoseEvents.snoozeSlot(listOf(first), "s", original, target), "the same snooze")
        assertEquals(target, DoseEvents.snoozeSlot(listOf(first), "s2", another, target), "another schedule")
        assertEquals(listOf(first), DoseEvents.merge(listOf(first), first), "a repeated marker does not throw")
        // Never onto a whole minute, even when skipping a taken second would land there.
        val edge = Instant.parse("2026-11-01T08:10:59Z")
        assertEquals(Instant.parse("2026-11-01T08:11:01Z"), DoseEvents.snoozeSlot(listOf(snooze(edge, original)), "s", another, edge))
    }

    @Test
    fun newSnoozesNeverLandOnAWholeMinute() {
        assertEquals(Instant.parse("2026-11-01T08:10:01Z"), DoseEvents.snoozeUntil(Instant.parse("2026-11-01T08:00:00Z")))
        assertEquals(Instant.parse("2026-11-01T08:10:07Z"), DoseEvents.snoozeUntil(Instant.parse("2026-11-01T08:00:07.900Z")))
    }

    @Test
    fun aBackupRoundTripKeepsTheRelationshipAndTheCounts() {
        val stored = data(record(snooze(snoozed, original), snooze(snoozedAgain, snoozed), terminal(snoozedAgain)))
        val json = Json { encodeDefaults = true }
        val restored = json.decodeFromString(ProfileData.serializer(), json.encodeToString(ProfileData.serializer(), stored))
        assertEquals(stored.doseEvents, restored.doseEvents)
        assertEquals(CareDoses.recorded(stored), CareDoses.recorded(restored))
        assertEquals(9, MedicationSupplies.remaining(medication, restored.doseEvents))
    }

    @Test
    fun repositoryWritesGoThroughTheSameRule() = runTest {
        val repository = InMemoryVezunaRepository()
        repository.createProfile(Profile("p", "Ana", createdAt = original))
        repository.addMedication(medication)
        repository.addDoseEvent(snooze(snoozed, original))
        repository.addDoseEvent(terminal(snoozed))
        repository.addDoseEvent(terminal(original, recorded = takenAt + 60.seconds))
        val taken = repository.readProfile("p").doseEvents.single { it.status == DoseEventStatus.TAKEN }
        assertEquals(original, taken.scheduledFor)
        assertEquals(takenAt, taken.recordedAt)
    }
}
