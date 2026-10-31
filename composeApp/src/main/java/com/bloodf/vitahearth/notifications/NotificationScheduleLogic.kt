package com.bloodf.vitahearth.notifications

import com.bloodf.vitahearth.domain.DoseSchedule
import com.bloodf.vitahearth.domain.Recurrence
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

object NotificationScheduleLogic {
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
