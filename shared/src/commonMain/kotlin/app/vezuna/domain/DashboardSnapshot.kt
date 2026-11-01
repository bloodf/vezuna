package app.vezuna.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

data class DashboardDose(
    val profileId: String,
    val medicationId: String,
    val scheduleId: String,
    val medicationName: String,
    val strength: String?,
    val instructions: String?,
    val scheduledFor: Instant,
)

data class DashboardAdherence(
    val taken: Int,
    val completed: Int,
) {
    val fraction: Double? get() = if (completed == 0) null else taken.toDouble() / completed.toDouble()
}

data class DashboardRecentResult(
    val observation: LabObservation,
    val changeFromPrevious: ChangeDirection,
)

data class DashboardSnapshot(
    val nextDose: DashboardDose?,
    val upcoming: List<DashboardDose>,
    val adherence: DashboardAdherence,
    val reviewCount: Int,
    val recentResults: List<DashboardRecentResult>,
)

object DashboardSnapshotFactory {
    private val terminalStatuses = setOf(DoseEventStatus.TAKEN, DoseEventStatus.SKIPPED, DoseEventStatus.MISSED)

    fun create(data: ProfileData, now: Instant, zone: TimeZone): DashboardSnapshot {
        val localToday = now.toLocalDateTime(zone).date
        val terminal = data.doseEvents.filter { it.profileId == data.profile.profileId && it.status in terminalStatuses }
        val snoozes = data.doseEvents.filter {
            it.profileId == data.profile.profileId && it.status == DoseEventStatus.SCHEDULED && it.replacesScheduledFor != null
        }
        val candidates = linkedMapOf<String, DashboardDose>()

        data.medications.filter { it.profileId == data.profile.profileId && it.active }.forEach { medication ->
            medication.schedules.filter { it.profileId == data.profile.profileId }.forEach { schedule ->
                var date = maxOf(schedule.startDate, localToday)
                val limit = schedule.endDate ?: localToday.plus(370, DateTimeUnit.DAY)
                while (date <= limit) {
                    if (MedicationRecurrence.occursOn(schedule, date)) {
                        schedule.timesLocal.sorted().forEach { rawTime ->
                            val candidate = LocalDateTime.parse("${date}T$rawTime").toInstant(zone)
                            val occurrenceKey = occurrenceKey(schedule.scheduleId, candidate)
                            if (candidate >= now &&
                                terminal.none { it.scheduleId == schedule.scheduleId && it.scheduledFor == candidate } &&
                                snoozes.none { it.scheduleId == schedule.scheduleId && it.replacesScheduledFor == candidate }
                            ) {
                                if (!candidates.containsKey(occurrenceKey)) {
                                    candidates[occurrenceKey] = medication.dose(schedule.scheduleId, candidate)
                                }
                            }
                        }
                    }
                    date = date.plus(1, DateTimeUnit.DAY)
                }
            }
        }

        data.doseEvents.filter { event ->
            event.profileId == data.profile.profileId && event.status == DoseEventStatus.SCHEDULED && event.scheduledFor >= now &&
                terminal.none { it.scheduleId == event.scheduleId && it.scheduledFor == event.scheduledFor } &&
                snoozes.none { it.scheduleId == event.scheduleId && it.replacesScheduledFor == event.scheduledFor }
        }.forEach { event ->
            val medication = data.medications.firstOrNull { it.profileId == event.profileId && it.medicationId == event.medicationId && it.active }
            if (medication != null) {
                candidates[event.scheduleId + ":" + event.scheduledFor.epochSeconds] = medication.dose(event.scheduleId, event.scheduledFor)
            }
        }

        val upcoming = candidates.values.sortedWith(compareBy({ it.scheduledFor }, { it.scheduleId })).take(3)
        val windowStart = now.minus(30, DateTimeUnit.DAY, zone)
        val completed = terminal.filter { it.profileId == data.profile.profileId && it.scheduledFor >= windowStart && it.scheduledFor <= now }
            .distinctBy { it.scheduleId to it.scheduledFor }
        val adherence = DashboardAdherence(
            taken = completed.count { it.status == DoseEventStatus.TAKEN },
            completed = completed.size,
        )
        val confirmed = data.observations.filter { it.profileId == data.profile.profileId && it.confirmation == ConfirmationStatus.CONFIRMED }
            .sortedByDescending { it.collectedAt }
        val recent = confirmed.take(3).map { observation ->
            val previous = confirmed.firstOrNull {
                it.observationId != observation.observationId && it.collectedAt < observation.collectedAt &&
                    UnitNormalizer.canonicalTestCode(it.testCode) == UnitNormalizer.canonicalTestCode(observation.testCode) &&
                    it.normalizedUnit.equals(observation.normalizedUnit, ignoreCase = true)
            }
            DashboardRecentResult(
                observation = observation,
                changeFromPrevious = previous?.let { LabComparator.compare(it, observation).direction } ?: ChangeDirection.NOT_COMPARABLE,
            )
        }
        return DashboardSnapshot(
            nextDose = upcoming.firstOrNull(),
            upcoming = upcoming,
            adherence = adherence,
            reviewCount = data.ocrCandidates.count { it.profileId == data.profile.profileId && it.status == ConfirmationStatus.UNREVIEWED },
            recentResults = recent,
        )
    }

    private fun occurrenceKey(scheduleId: String, at: Instant) = "$scheduleId:${at.epochSeconds}"

    private fun Medication.dose(scheduleId: String, at: Instant) = DashboardDose(
        profileId = profileId,
        medicationId = medicationId,
        scheduleId = scheduleId,
        medicationName = displayName,
        strength = strength,
        instructions = instructions,
        scheduledFor = at,
    )
}
