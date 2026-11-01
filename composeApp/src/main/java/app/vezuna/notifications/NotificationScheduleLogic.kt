package app.vezuna.notifications

import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.ProfileData
import app.vezuna.domain.Recurrence
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

data class ShownReminder(val notificationId: Int, val profileId: String, val medicationId: String, val scheduleId: String)

object NotificationScheduleLogic {
    private const val ARMED_SEPARATOR = "|"

    /** Stable identity of one dose alarm: schedule plus its scheduled instant. */
    fun alarmId(scheduleId: String, at: Instant): String = "$scheduleId:${at.epochSecond}"

    fun armedKey(medicationId: String, alarmId: String): String = "$medicationId$ARMED_SEPARATOR$alarmId"

    fun splitArmedKey(key: String): Pair<String, String>? =
        key.split(ARMED_SEPARATOR, limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }

    /** Alarms armed by an earlier pass that the latest pass no longer produces (zone/time change, pause, delete). */
    fun staleArmedKeys(previous: Set<String>, armed: Set<String>): Set<String> = previous - armed

    /** Delivered reminders whose medication was paused, stopped or deleted, or whose profile was removed. */
    fun staleNotificationIds(shown: List<ShownReminder>, profiles: Map<String, ProfileData>): List<Int> =
        shown.filterNot { isReminderCurrent(profiles[it.profileId], it.medicationId, it.scheduleId) }.map { it.notificationId }

    /** False once the medication was paused, stopped or deleted (or its profile removed) after the alarm was armed. */
    fun isReminderCurrent(data: ProfileData?, medicationId: String, scheduleId: String): Boolean =
        data?.medications?.any { medication ->
            medication.medicationId == medicationId && medication.active && medication.schedules.any { it.scheduleId == scheduleId }
        } == true

    fun nextOccurrences(schedule: DoseSchedule, now: Instant, zone: ZoneId): List<ZonedDateTime> {
        val localNow = now.atZone(zone)
        val start = LocalDate.ofEpochDay(schedule.startDate.toEpochDays())
        val end = schedule.endDate?.let { LocalDate.ofEpochDay(it.toEpochDays()) }
        return schedule.timesLocal.mapNotNull { rawTime ->
            val time = LocalTime.parse(rawTime)
            nextForTime(schedule, localNow, start, end, time)
        }.sortedBy { it.toInstant() }
    }

    private fun nextForTime(
        schedule: DoseSchedule,
        now: ZonedDateTime,
        start: LocalDate,
        end: LocalDate?,
        time: LocalTime,
    ): ZonedDateTime? {
        var date = maxOf(start, now.toLocalDate().minusDays(1))
        val searchLimit = maxOf(end ?: now.toLocalDate().plusYears(4), now.toLocalDate().plusDays(8))
        while (!date.isAfter(searchLimit)) {
            if ((end == null || !date.isAfter(end)) && occursOn(schedule, start, date)) {
                val candidate = ZonedDateTime.of(LocalDateTime.of(date, time), now.zone)
                if (candidate.toInstant().isAfter(now.toInstant())) return candidate
            }
            date = date.plusDays(1)
        }
        return null
    }

    private fun occursOn(schedule: DoseSchedule, start: LocalDate, date: LocalDate): Boolean = when (schedule.recurrence) {
        Recurrence.DAILY -> !date.isBefore(start)
        Recurrence.WEEKLY -> !date.isBefore(start) && date.dayOfWeek.name in schedule.daysOfWeek.map { it.name }
        Recurrence.EVERY_N_DAYS -> !date.isBefore(start) && ChronoUnit.DAYS.between(start, date) % schedule.intervalDays == 0L
    }
}
