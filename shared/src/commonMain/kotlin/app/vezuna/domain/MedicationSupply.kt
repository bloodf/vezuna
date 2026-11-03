package app.vezuna.domain

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Units counted on hand at [countedAt], one unit per dose. Remaining supply is derived from the dose events recorded
 * as TAKEN after [countedAt], so undoing a taken dose (or any path that records one) can never drift the count.
 */
@Serializable
data class MedicationSupply(
    val unitsOnHand: Int,
    val countedAt: Instant,
    /** Remind when the remaining supply falls to this many units or fewer. */
    val lowThreshold: Int? = null,
) {
    init {
        require(unitsOnHand >= 0)
        require(lowThreshold == null || lowThreshold >= 0)
    }
}

/** A low-supply reminder to deliver at [fireAt], keyed so a platform can remember what it already scheduled. */
data class LowSupplyNotice(
    val profileId: String,
    val medicationId: String,
    val medicationName: String,
    val key: String,
    val remaining: Int,
    val fireAt: Instant,
    val lowNow: Boolean,
)

object MedicationSupplies {
    const val NOTICE_HOUR = 9
    private const val SEARCH_DAYS = 3650

    /** Doses taken since the count; skipped, missed and snoozed doses never use a unit. */
    fun remaining(medication: Medication, events: List<DoseEvent>): Int? {
        val supply = medication.supply ?: return null
        return (supply.unitsOnHand - takenSinceCount(supply, medication.medicationId, events)).coerceAtLeast(0)
    }

    /**
     * One unit per dose. Older builds recorded a dose taken after a snooze at the snoozed time, so history can hold two
     * TAKEN rows for it; they are counted by the original time. Known limit, old markers only since new snoozes stay off
     * the minute: a separate real dose of the same schedule on the exact second of an old snooze target would count once.
     * Duplicates are judged by their last tap, not the first time shown: a recount between the two taps counted the dose
     * as used after the recount, and stored history keeps the count it had.
     */
    private fun takenSinceCount(supply: MedicationSupply, medicationId: String, events: List<DoseEvent>): Int {
        val own = events.filter { it.medicationId == medicationId }
        val original = DoseEvents.oldCountOriginals(own)
        return DoseEvents.perDose(own, keepFirstRepeat = false)
            .filter { it.status == DoseEventStatus.TAKEN && (it.recordedAt ?: it.scheduledFor) >= supply.countedAt }
            .distinctBy { it.scheduleId to original(it.scheduleId, it.scheduledFor).epochSeconds }
            .size
    }

    /**
     * Supply to store when [dropped] dose events are deleted with their schedules: units they used stay used, so the
     * remaining count does not grow back.
     */
    fun withoutEvents(supply: MedicationSupply, medicationId: String, dropped: List<DoseEvent>): MedicationSupply =
        supply.copy(unitsOnHand = (supply.unitsOnHand - takenSinceCount(supply, medicationId, dropped)).coerceAtLeast(0))

    /** Whole-unit refill added on top of what is left now, counted from [now]. */
    fun refilled(medication: Medication, events: List<DoseEvent>, added: Int, now: Instant): MedicationSupply {
        require(added > 0)
        val supply = requireNotNull(medication.supply)
        return supply.copy(unitsOnHand = (remaining(medication, events) ?: 0) + added, countedAt = now)
    }

    /**
     * Supply after the user edits the fields: a blank count clears tracking; an unchanged count keeps its start time,
     * so doses already taken stay counted; a changed count is a new count starting now.
     */
    fun edited(medication: Medication, events: List<DoseEvent>, units: Int?, threshold: Int?, now: Instant, shown: Int? = null): MedicationSupply? {
        if (units == null) return null
        // Compared with the count the form showed, so a dose recorded while the form was open stays counted.
        val baseline = shown ?: remaining(medication, events)
        return if (baseline == units) medication.supply?.copy(lowThreshold = threshold) else MedicationSupply(units, now, threshold)
    }

    fun isLow(medication: Medication, remaining: Int): Boolean =
        medication.supply?.lowThreshold?.let { remaining <= it } == true

    /** Does the schedule produce a dose on [date]? Same recurrence rule as the reminder planners. */
    fun occursOn(schedule: DoseSchedule, date: LocalDate): Boolean {
        if (date < schedule.startDate || (schedule.endDate != null && date > schedule.endDate)) return false
        return when (schedule.recurrence) {
            Recurrence.DAILY -> true
            Recurrence.WEEKLY -> date.dayOfWeek in schedule.daysOfWeek
            Recurrence.EVERY_N_DAYS -> (date.toEpochDays() - schedule.startDate.toEpochDays()) % schedule.intervalDays == 0L
        }
    }

    private fun dosesOn(medication: Medication, date: LocalDate, after: LocalTime?): Int =
        medication.schedules.filter { occursOn(it, date) }.sumOf { schedule ->
            schedule.timesLocal.count { after == null || LocalTime.parse(it) > after }
        }

    /**
     * First date (today or later) on which the doses still ahead, from [now] on, use the last unit.
     * Null when nothing is scheduled ahead, the medication is paused or stopped, or no supply is tracked.
     */
    fun runOutDate(medication: Medication, remaining: Int, now: Instant, zone: TimeZone): LocalDate? {
        val today = now.toLocalDateTime(zone).date
        return daysUntil(medication, now, zone) { used -> used >= remaining }?.let { today.plus(DatePeriod(days = it)) }
    }

    /** Date from which the remaining supply is at or below [threshold] after that day's doses. */
    private fun crossingDate(medication: Medication, remaining: Int, threshold: Int, now: Instant, zone: TimeZone): LocalDate? =
        daysUntil(medication, now, zone) { used -> remaining - used <= threshold }?.let { now.toLocalDateTime(zone).date.plus(DatePeriod(days = it)) }

    /** Today counts only the doses whose time is still ahead: earlier ones are already taken, skipped or missed. */
    private fun daysUntil(medication: Medication, now: Instant, zone: TimeZone, reached: (Int) -> Boolean): Int? {
        if (!medication.active || medication.schedules.isEmpty()) return null
        val local = now.toLocalDateTime(zone)
        var used = 0
        for (offset in 0..SEARCH_DAYS) {
            used += dosesOn(medication, local.date.plus(DatePeriod(days = offset)), if (offset == 0) local.time else null)
            if (reached(used)) return offset
        }
        return null
    }

    /** One notice per tracked active medication with a threshold; see [fireTime] for when it is delivered. */
    fun notices(data: ProfileData, now: Instant, zone: TimeZone): List<LowSupplyNotice> {
        return data.medications.filter { it.active }.mapNotNull { medication ->
            val supply = medication.supply ?: return@mapNotNull null
            val threshold = supply.lowThreshold ?: return@mapNotNull null
            val remaining = remaining(medication, data.doseEvents) ?: return@mapNotNull null
            val lowNow = remaining <= threshold
            val fireAt = if (lowNow) now else {
                val date = crossingDate(medication, remaining, threshold, now, zone) ?: return@mapNotNull null
                val projected = date.atTime(LocalTime(NOTICE_HOUR, 0)).toInstant(zone)
                if (projected <= now) now else projected
            }
            LowSupplyNotice(
                medication.profileId, medication.medicationId, medication.displayName,
                "${medication.medicationId}|${supply.countedAt.epochSeconds}|$threshold", remaining, fireAt, lowNow || fireAt <= now,
            )
        }
    }

    /**
     * Epoch second at which to schedule [notice], or null when it was already delivered. [previous] is the fire time
     * stored for the same key by an earlier pass, so a notice that fired on schedule is not repeated by the next pass.
     */
    fun fireTime(notice: LowSupplyNotice, previous: Long?, now: Instant): Long? {
        if (!notice.lowNow) return notice.fireAt.epochSeconds
        val soon = now.epochSeconds + IMMEDIATE_DELAY_SECONDS
        return when {
            previous == null || previous > soon -> soon
            previous <= now.epochSeconds -> null
            else -> previous
        }
    }

    const val IMMEDIATE_DELAY_SECONDS = 60L
}
