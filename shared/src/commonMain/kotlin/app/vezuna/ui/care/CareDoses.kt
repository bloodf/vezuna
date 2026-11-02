package app.vezuna.ui.care

import app.vezuna.domain.DashboardDose
import app.vezuna.domain.DoseCounts
import app.vezuna.domain.DoseEvent
import app.vezuna.domain.DoseEventStatus
import app.vezuna.domain.DoseEvents
import app.vezuna.domain.Medication
import app.vezuna.domain.MedicationRecurrence
import app.vezuna.domain.ProfileData
import kotlinx.datetime.DateTimeUnit
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** What is recorded for one dose. MISSED is the system's 24-hour record, never "not taken". */
enum class DoseState { PENDING, SNOOZED, TAKEN, SKIPPED, MISSED }

data class CareDose(val dose: DashboardDose, val state: DoseState, val recordedAt: Instant? = null) {
    val recorded: Boolean get() = state == DoseState.TAKEN || state == DoseState.SKIPPED || state == DoseState.MISSED
}

/** Active, paused (reminders off, history kept) and stopped (schedules ended) stay distinct. */
enum class MedicationStatus { ACTIVE, PAUSED, STOPPED }

val Medication.status: MedicationStatus
    get() = when {
        active -> MedicationStatus.ACTIVE
        schedules.any { it.endDate != null } -> MedicationStatus.STOPPED
        else -> MedicationStatus.PAUSED
    }

object CareDoses {
    /**
     * Today's doses of active medications in time order, each with its recorded state. A snoozed dose appears once,
     * at its snoozed time; a recorded dose keeps the latest user action (taken or skipped) over MISSED.
     */
    fun today(data: ProfileData, now: Instant, zone: TimeZone): List<CareDose> {
        val today = now.toLocalDateTime(zone).date
        val events = DoseEvents.collapse(data.doseEvents).filter { it.profileId == data.profile.profileId }
        val terminal = events.filter { DoseEvents.isTerminal(it.status) }.associateBy { it.scheduleId to it.scheduledFor.epochSeconds }
        val snoozes = events.filter { it.status == DoseEventStatus.SCHEDULED && it.replacesScheduledFor != null }
        val superseded = snoozes.map { it.scheduleId to it.replacesScheduledFor!!.epochSeconds }.toSet()
        val result = linkedMapOf<Pair<String, Long>, CareDose>()

        fun add(medication: Medication, scheduleId: String, at: Instant, snoozed: Boolean) {
            val key = scheduleId to at.epochSeconds
            val recorded = terminal[key]
            if (recorded == null && key in superseded) return
            result.getOrPut(key) {
                val state = recorded?.state() ?: if (snoozed) DoseState.SNOOZED else DoseState.PENDING
                CareDose(medication.dose(scheduleId, at), state, recorded?.recordedAt)
            }
        }

        val active = data.medications.filter { it.profileId == data.profile.profileId && it.active }
        active.forEach { medication ->
            medication.schedules.filter { MedicationRecurrence.occursOn(it, today) }.forEach { schedule ->
                schedule.timesLocal.forEach { time -> add(medication, schedule.scheduleId, LocalDateTime.parse("${today}T$time").toInstant(zone), snoozed = false) }
            }
        }
        snoozes.filter { it.scheduledFor.toLocalDateTime(zone).date == today }.forEach { snooze ->
            active.firstOrNull { it.medicationId == snooze.medicationId }?.let { add(it, snooze.scheduleId, snooze.scheduledFor, snoozed = true) }
        }
        return result.values.sortedWith(compareBy({ it.dose.scheduledFor }, { it.dose.medicationName }))
    }

    /** Recorded doses (one per dose, latest action wins), newest first, for one medication or the whole profile. */
    fun recorded(data: ProfileData, medicationId: String? = null): List<CareDose> =
        DoseEvents.collapse(data.doseEvents)
            .filter { it.profileId == data.profile.profileId && DoseEvents.isTerminal(it.status) && (medicationId == null || it.medicationId == medicationId) }
            .mapNotNull { event ->
                val medication = data.medications.firstOrNull { it.medicationId == event.medicationId } ?: return@mapNotNull null
                CareDose(medication.dose(event.scheduleId, event.scheduledFor), event.state(), event.recordedAt)
            }
            .sortedByDescending { it.dose.scheduledFor }

    /** Recorded doses on or after [months] months before [now]; null keeps them all. */
    fun within(doses: List<CareDose>, months: Int?, now: Instant, zone: TimeZone): List<CareDose> {
        if (months == null) return doses
        val start = now.minus(months, DateTimeUnit.MONTH, zone)
        return doses.filter { it.dose.scheduledFor >= start }
    }

    /** Plain counts of recorded doses; the denominator is [DoseCounts.completed], never a target. */
    fun counts(doses: List<CareDose>): DoseCounts = DoseCounts(
        taken = doses.count { it.state == DoseState.TAKEN },
        skipped = doses.count { it.state == DoseState.SKIPPED },
        missed = doses.count { it.state == DoseState.MISSED },
    )

    private fun DoseEvent.state(): DoseState = when (status) {
        DoseEventStatus.TAKEN -> DoseState.TAKEN
        DoseEventStatus.SKIPPED -> DoseState.SKIPPED
        DoseEventStatus.MISSED -> DoseState.MISSED
        DoseEventStatus.SCHEDULED -> DoseState.PENDING
    }

    private fun Medication.dose(scheduleId: String, at: Instant) =
        DashboardDose(profileId, medicationId, scheduleId, displayName, strength, instructions, at)
}
