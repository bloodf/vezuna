package app.vezuna.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** Terminal outcome counts for a window. Descriptive only: no target or judgement is attached. */
data class DoseCounts(val taken: Int, val skipped: Int, val missed: Int) {
    val completed: Int get() = taken + skipped + missed

    /** Share of recorded doses that were taken, or null when nothing was recorded. */
    val adherenceFraction: Double? get() = if (completed == 0) null else taken.toDouble() / completed
}

data class MedicationDoseHistory(
    val medicationId: String,
    val medicationName: String,
    val last7Days: DoseCounts,
    val last30Days: DoseCounts,
)

data class DoseHistory(
    val last7Days: DoseCounts,
    val last30Days: DoseCounts,
    val medications: List<MedicationDoseHistory>,
)

object DoseHistoryFactory {
    fun create(data: ProfileData, now: Instant, zone: TimeZone): DoseHistory {
        val terminal = DoseEvents.collapse(data.doseEvents)
            .filter { it.profileId == data.profile.profileId && DoseEvents.isTerminal(it.status) && it.scheduledFor <= now }
        fun counts(events: List<DoseEvent>, days: Int): DoseCounts {
            val start = now.minus(days, DateTimeUnit.DAY, zone)
            val inWindow = events.filter { it.scheduledFor >= start }
            return DoseCounts(
                inWindow.count { it.status == DoseEventStatus.TAKEN },
                inWindow.count { it.status == DoseEventStatus.SKIPPED },
                inWindow.count { it.status == DoseEventStatus.MISSED },
            )
        }
        val medications = data.medications.filter { it.profileId == data.profile.profileId }.map { medication ->
            val events = terminal.filter { it.medicationId == medication.medicationId }
            MedicationDoseHistory(medication.medicationId, medication.displayName, counts(events, 7), counts(events, 30))
        }
        return DoseHistory(counts(terminal, 7), counts(terminal, 30), medications)
    }
}

/** Single missed-dose rule shared by Android and iOS: a dose with no terminal event 24 hours after its time is MISSED. */
object MissedDoses {
    const val GRACE_SECONDS = 24 * 60 * 60L
    private const val LOOKBACK_DAYS = 7

    /** Legacy schedules (no [DoseSchedule.trackedSince]) stamped with [now]: nothing before the upgrade is ever judged missed. */
    fun stampLegacy(data: ProfileData, now: Instant): List<DoseSchedule> =
        data.medications.flatMap { it.schedules }.filter { it.trackedSince == null }.map { it.copy(trackedSince = now) }

    /** MISSED events to record now. Only active medications; snoozed doses are judged at their snoozed time. */
    fun pending(data: ProfileData, now: Instant, zone: TimeZone): List<DoseEvent> {
        val cutoff = Instant.fromEpochSeconds(now.epochSeconds - GRACE_SECONDS)
        val earliest = now.minus(LOOKBACK_DAYS, DateTimeUnit.DAY, zone)
        val today = now.toLocalDateTime(zone).date
        val terminal = data.doseEvents.filter { DoseEvents.isTerminal(it.status) }
            .map { it.scheduleId to it.scheduledFor.epochSeconds }.toSet()
        val snoozes = data.doseEvents.filter { it.status == DoseEventStatus.SCHEDULED && it.replacesScheduledFor != null }
        val superseded = snoozes.map { it.scheduleId to it.replacesScheduledFor!!.epochSeconds }.toSet()
        val snoozeTimes = snoozes.map { it.scheduledFor.epochSeconds }.toSet()
        val result = linkedMapOf<String, DoseEvent>()

        fun consider(medication: Medication, scheduleId: String, at: Instant) {
            val key = scheduleId to at.epochSeconds
            if (at > cutoff || at < earliest || key in terminal || key in superseded) return
            val id = DoseEvents.idFor(scheduleId, at)
            result.getOrPut(id) {
                DoseEvent(medication.profileId, id, medication.medicationId, scheduleId, at, DoseEventStatus.MISSED, now)
            }
        }

        data.medications.filter { it.active }.forEach { medication ->
            medication.schedules.forEach { schedule ->
                // Never judge doses from before the schedule existed in its current form. Legacy schedules wait for [stampLegacy].
                val trackedFrom = schedule.trackedSince ?: return@forEach
                // ponytail: keyed on the local date in the current zone, so an evening dose recorded before a big zone shift may land on the next date; refine with the recorded zone if that ever matters.
                // A terminal event on a day at a time this schedule does not have (time zone change) means the day is already accounted for.
                val foreignDays = data.doseEvents.filter {
                    it.scheduleId == schedule.scheduleId && DoseEvents.isTerminal(it.status) && it.scheduledFor.epochSeconds !in snoozeTimes &&
                        it.scheduledFor.toLocalDateTime(zone).time.toString().take(5) !in schedule.timesLocal
                }.map { it.scheduledFor.toLocalDateTime(zone).date }.toSet()
                var date = maxOf(schedule.startDate, today.minus(LOOKBACK_DAYS + 1, DateTimeUnit.DAY))
                val limit = minOf(schedule.endDate ?: today, today)
                while (date <= limit) {
                    if (date !in foreignDays && MedicationRecurrence.occursOn(schedule, date)) {
                        schedule.timesLocal.forEach { time ->
                            val at = LocalDateTime.parse("${date}T$time").toInstant(zone)
                            if (at >= trackedFrom) consider(medication, schedule.scheduleId, at)
                        }
                    }
                    date = date.plus(1, DateTimeUnit.DAY)
                }
            }
        }
        snoozes.forEach { snooze ->
            val medication = data.medications.firstOrNull { it.medicationId == snooze.medicationId && it.active } ?: return@forEach
            consider(medication, snooze.scheduleId, snooze.scheduledFor)
        }
        return result.values.toList()
    }
}
