package app.vezuna

import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.Medication
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.Recurrence
import app.vezuna.notifications.NotificationScheduleLogic
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import java.time.Instant
import java.time.ZoneId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationScheduleLogicTest {
    private val utc: ZoneId = ZoneId.of("UTC")

    @Test
    fun dailyTimesUseTheNextFutureLocalOccurrence() {
        val schedule = schedule(Recurrence.DAILY, times = listOf("08:00", "20:00"))
        val next = NotificationScheduleLogic.nextOccurrences(schedule, Instant.parse("2026-10-29T08:30:00Z"), utc)
        assertEquals(listOf("2026-10-29T20:00:00Z", "2026-10-30T08:00:00Z"), next.map { it.toInstant().toString() })
    }

    @Test
    fun weeklyAndIntervalRecurrencesRespectTheirAnchors() {
        val weekly = schedule(Recurrence.WEEKLY, days = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))
        assertEquals(
            "2026-10-30T08:00:00Z",
            NotificationScheduleLogic.nextOccurrences(weekly, Instant.parse("2026-10-29T07:00:00Z"), utc).single().toInstant().toString(),
        )
        val interval = schedule(Recurrence.EVERY_N_DAYS, intervalDays = 3)
        assertEquals(
            "2026-10-04T08:00:00Z",
            NotificationScheduleLogic.nextOccurrences(interval, Instant.parse("2026-10-02T07:00:00Z"), utc).single().toInstant().toString(),
        )
    }

    @Test
    fun reminderIsStaleAfterPauseStopOrDelete() {
        val schedule = schedule(Recurrence.DAILY)
        val medication = Medication(schedule.profileId, schedule.medicationId, "Med", schedules = listOf(schedule))
        val profile = Profile(schedule.profileId, "Me", createdAt = kotlinx.datetime.Instant.fromEpochSeconds(1))
        fun current(data: ProfileData?) = NotificationScheduleLogic.isReminderCurrent(data, schedule.medicationId, schedule.scheduleId)
        assertTrue(current(ProfileData(profile, medications = listOf(medication))))
        assertEquals(false, current(ProfileData(profile, medications = listOf(medication.copy(active = false)))))
        assertEquals(false, current(ProfileData(profile)))
        assertEquals(false, current(null))
    }

    @Test
    fun firedReminderNotificationIsKeptButPausedOrDeletedOnesAreCancelled() {
        val schedule = schedule(Recurrence.DAILY)
        val medication = Medication(schedule.profileId, schedule.medicationId, "Med", schedules = listOf(schedule))
        val profile = Profile(schedule.profileId, "Me", createdAt = kotlinx.datetime.Instant.fromEpochSeconds(1))
        val shown = listOf(
            app.vezuna.notifications.ShownReminder(1, "p", "m", "s"),
            app.vezuna.notifications.ShownReminder(2, "gone", "m", "s"),
        )
        // The alarm that just fired is not "armed" any more, but its medication is current: keep the notification.
        val active = mapOf("p" to ProfileData(profile, medications = listOf(medication)))
        assertEquals(listOf(2), NotificationScheduleLogic.staleNotificationIds(shown, active))
        val paused = mapOf("p" to ProfileData(profile, medications = listOf(medication.copy(active = false))))
        assertEquals(listOf(1, 2), NotificationScheduleLogic.staleNotificationIds(shown, paused))
    }

    @Test
    fun endedSchedulesProduceNoAlarmAndDstUsesValidLocalTime() {
        val ended = schedule(Recurrence.DAILY).copy(endDate = LocalDate(2026, 10, 1))
        assertTrue(NotificationScheduleLogic.nextOccurrences(ended, Instant.parse("2026-10-29T07:00:00Z"), utc).isEmpty())
        val dst = schedule(Recurrence.DAILY, start = LocalDate(2026, 3, 8)).copy(timesLocal = listOf("02:30"))
        val next = NotificationScheduleLogic.nextOccurrences(dst, Instant.parse("2026-03-08T06:00:00Z"), ZoneId.of("America/New_York")).single()
        assertEquals("2026-03-08T07:30:00Z", next.toInstant().toString())
    }

    private fun schedule(
        recurrence: Recurrence,
        intervalDays: Int = 1,
        days: Set<DayOfWeek> = emptySet(),
        start: LocalDate = LocalDate(2026, 10, 1),
        times: List<String> = listOf("08:00"),
    ) = DoseSchedule("p", "s", "m", recurrence, intervalDays, days, start, timesLocal = times)
}
