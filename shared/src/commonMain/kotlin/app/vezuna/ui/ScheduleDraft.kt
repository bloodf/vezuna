package app.vezuna.ui

import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.Recurrence
import app.vezuna.localization.L10nKey
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

private val TIME = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")

/** Editable, not-yet-validated form of a [DoseSchedule]. [scheduleId] is kept for existing schedules so dose history stays linked. */
data class ScheduleDraft(
    val scheduleId: String? = null,
    val recurrence: Recurrence = Recurrence.DAILY,
    val intervalDays: String = "2",
    val daysOfWeek: Set<DayOfWeek> = emptySet(),
    val times: List<String> = listOf("08:00"),
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
) {
    fun errors(): List<L10nKey> = buildList {
        val trimmed = times.map(String::trim)
        when {
            trimmed.isEmpty() -> add(L10nKey.SCHEDULE_TIMES_REQUIRED)
            trimmed.any { !TIME.matches(it) } -> add(L10nKey.INVALID_REMINDER_TIME)
            trimmed.distinct().size != trimmed.size -> add(L10nKey.SCHEDULE_TIME_DUPLICATE)
        }
        if (recurrence == Recurrence.WEEKLY && daysOfWeek.isEmpty()) add(L10nKey.SCHEDULE_WEEKDAYS_REQUIRED)
        if (recurrence == Recurrence.EVERY_N_DAYS && (intervalDays.trim().toIntOrNull() ?: 0) < 1) add(L10nKey.SCHEDULE_INTERVAL_INVALID)
        if (endDate != null && endDate < startDate) add(L10nKey.SCHEDULE_END_BEFORE_START)
    }

    /** Call only when [errors] is empty. */
    fun toSchedule(profileId: String, medicationId: String, newId: () -> String): DoseSchedule = DoseSchedule(
        profileId = profileId,
        scheduleId = scheduleId ?: newId(),
        medicationId = medicationId,
        recurrence = recurrence,
        intervalDays = if (recurrence == Recurrence.EVERY_N_DAYS) intervalDays.trim().toInt() else 1,
        daysOfWeek = if (recurrence == Recurrence.WEEKLY) daysOfWeek else emptySet(),
        startDate = startDate,
        endDate = endDate,
        timesLocal = times.map(String::trim).sorted(),
    )

    companion object {
        fun from(schedule: DoseSchedule) = ScheduleDraft(
            scheduleId = schedule.scheduleId,
            recurrence = schedule.recurrence,
            intervalDays = schedule.intervalDays.toString(),
            daysOfWeek = schedule.daysOfWeek,
            times = schedule.timesLocal,
            startDate = schedule.startDate,
            endDate = schedule.endDate,
        )
    }
}
