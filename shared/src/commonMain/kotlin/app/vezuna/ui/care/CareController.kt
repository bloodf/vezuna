package app.vezuna.ui.care

import app.vezuna.domain.DashboardDose
import app.vezuna.domain.DoseEvent
import app.vezuna.domain.DoseEventStatus
import app.vezuna.domain.DoseEvents
import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.Medication
import app.vezuna.domain.MedicationSupplies
import app.vezuna.domain.MedicationSupply
import app.vezuna.domain.ProfileData
import app.vezuna.localization.L10nKey
import app.vezuna.localization.failMessage
import app.vezuna.platform.currentInstant
import app.vezuna.platform.randomId
import app.vezuna.ui.ControllerCore
import app.vezuna.ui.ScheduleDraft
import kotlinx.datetime.toLocalDateTime

/** Medications, supply and dose events of the current profile. */
internal class CareController(private val core: ControllerCore) {
    private val repository get() = core.repository

    suspend fun addMedication(name: String, strength: String, instructions: String, time: String) =
        addMedication(name, strength, instructions, listOf(ScheduleDraft(times = listOf(time), startDate = core.clock().toLocalDateTime(core.timeZone()).date)))

    suspend fun addMedication(name: String, strength: String, instructions: String, drafts: List<ScheduleDraft>, supplyUnits: String = "", lowThreshold: String = "") = core.operation {
        val data = core.requireData()
        if (name.isBlank()) failMessage(L10nKey.MEDICATION_NAME_REQUIRED)
        val medicationId = randomId()
        val schedules = buildSchedules(data.profile.profileId, medicationId, drafts)
        val supply = parseSupply(supplyUnits, lowThreshold)?.let { (units, threshold) -> MedicationSupply(units, core.clock(), threshold) }
        repository.addMedication(Medication(data.profile.profileId, medicationId, name.trim(), strength.trim().ifBlank { null }, instructions.trim().ifBlank { null }, schedules, supply = supply))
        core.loadProfile(data.profile.profileId)
    }

    private fun buildSchedules(profileId: String, medicationId: String, drafts: List<ScheduleDraft>, previous: List<DoseSchedule> = emptyList()): List<DoseSchedule> {
        if (drafts.isEmpty()) failMessage(L10nKey.SCHEDULE_TIMES_REQUIRED)
        drafts.firstNotNullOfOrNull { it.errors().firstOrNull() }?.let { failMessage(it) }
        val now = core.clock()
        return drafts.map { draft ->
            val schedule = draft.toSchedule(profileId, medicationId, ::randomId)
            val old = previous.firstOrNull { it.scheduleId == schedule.scheduleId }
            // Doses count from the moment the schedule was created or its times/recurrence last changed.
            val unchanged = old != null && old.copy(trackedSince = null) == schedule
            schedule.copy(trackedSince = if (unchanged) old?.trackedSince else now)
        }
    }

    suspend fun updateMedication(medicationId: String, name: String, strength: String, instructions: String, drafts: List<ScheduleDraft>, supplyUnits: String? = null, lowThreshold: String = "", unitsShown: String? = null) = core.operation {
        val data = core.requireData()
        val medication = findMedication(data, medicationId)
        if (name.isBlank()) failMessage(L10nKey.MEDICATION_NAME_REQUIRED)
        val schedules = buildSchedules(data.profile.profileId, medicationId, drafts, medication.schedules)
        // Null leaves the supply untouched (callers that do not edit it); a string, even blank, is the form's value.
        val supply = if (supplyUnits == null) medication.supply else {
            val parsed = parseSupply(supplyUnits, lowThreshold)
            MedicationSupplies.edited(medication, data.doseEvents, parsed?.first, parsed?.second, core.clock(), unitsShown?.trim()?.toIntOrNull())
        }
        repository.updateMedication(
            medication.copy(
                displayName = name.trim(),
                strength = strength.trim().ifBlank { null },
                instructions = instructions.trim().ifBlank { null },
                schedules = schedules,
                supply = supply,
            ),
        )
        core.loadProfile(data.profile.profileId)
    }

    /** Adds [added] units to what is left now and restarts the count; only a medication that tracks supply can be refilled. */
    suspend fun recordRefill(medicationId: String, added: String) = core.operation {
        val data = core.requireData()
        val medication = findMedication(data, medicationId)
        val units = added.trim().toIntOrNull()?.takeIf { it in 1..MAX_SUPPLY } ?: failMessage(L10nKey.SUPPLY_REFILL_INVALID)
        if (medication.supply == null) failMessage(L10nKey.SUPPLY_NOT_TRACKED)
        if ((MedicationSupplies.remaining(medication, data.doseEvents) ?: 0) + units > MAX_SUPPLY) failMessage(L10nKey.SUPPLY_REFILL_INVALID)
        repository.updateMedication(medication.copy(supply = MedicationSupplies.refilled(medication, data.doseEvents, units, core.clock())))
        core.loadProfile(data.profile.profileId)
    }

    /** Blank units means no tracking; the threshold is optional and ignored without units. */
    private fun parseSupply(units: String, threshold: String): Pair<Int, Int?>? {
        if (units.isBlank()) return null
        val count = units.trim().toIntOrNull()?.takeIf { it in 0..MAX_SUPPLY } ?: failMessage(L10nKey.SUPPLY_INVALID)
        val low = if (threshold.isBlank()) null else threshold.trim().toIntOrNull()?.takeIf { it in 0..MAX_SUPPLY } ?: failMessage(L10nKey.SUPPLY_INVALID)
        return count to low
    }

    suspend fun updateMedication(medicationId: String, name: String, strength: String, instructions: String, time: String) = core.operation {
        val data = core.requireData()
        val medication = findMedication(data, medicationId)
        if (name.isBlank()) failMessage(L10nKey.MEDICATION_NAME_REQUIRED)
        val canonicalTime = time.trim()
        if (!REMINDER_TIME.matches(canonicalTime)) failMessage(L10nKey.INVALID_REMINDER_TIME)
        // The editor exposes one reminder time; it replaces the first time of the first schedule.
        val schedules = medication.schedules.mapIndexed { index, schedule ->
            if (index == 0 && schedule.timesLocal.firstOrNull() != canonicalTime) {
                schedule.copy(timesLocal = listOf(canonicalTime) + schedule.timesLocal.drop(1), trackedSince = core.clock())
            } else {
                schedule
            }
        }
        repository.updateMedication(
            medication.copy(
                displayName = name.trim(),
                strength = strength.trim().ifBlank { null },
                instructions = instructions.trim().ifBlank { null },
                schedules = schedules,
            ),
        )
        core.loadProfile(data.profile.profileId)
    }

    /** Pausing keeps history; resuming also clears an end date that has already passed (a stopped medication). */
    suspend fun setMedicationActive(medicationId: String, active: Boolean) = core.operation {
        val data = core.requireData()
        val medication = findMedication(data, medicationId)
        val today = core.clock().toLocalDateTime(core.timeZone()).date
        val schedules = if (active) {
            // Paused or stopped days are not due: doses count again only from the moment of resuming.
            medication.schedules.map { (if (it.endDate != null && it.endDate <= today) it.copy(endDate = null) else it).copy(trackedSince = core.clock()) }
        } else {
            medication.schedules
        }
        repository.updateMedication(medication.copy(active = active, schedules = schedules))
        core.loadProfile(data.profile.profileId)
    }

    /** Stopping ends the schedules today (never before they start) and deactivates reminders immediately. */
    suspend fun stopMedication(medicationId: String) = core.operation {
        val data = core.requireData()
        val medication = findMedication(data, medicationId)
        val today = core.clock().toLocalDateTime(core.timeZone()).date
        val schedules = medication.schedules.map { it.copy(endDate = maxOf(today, it.startDate)) }
        repository.updateMedication(medication.copy(active = false, schedules = schedules))
        core.loadProfile(data.profile.profileId)
    }

    suspend fun deleteMedication(medicationId: String) = core.operation {
        val data = core.requireData()
        findMedication(data, medicationId)
        repository.deleteMedication(data.profile.profileId, medicationId)
        core.loadProfile(data.profile.profileId)
    }

    private fun findMedication(data: ProfileData, medicationId: String): Medication =
        data.medications.firstOrNull { it.medicationId == medicationId } ?: failMessage(L10nKey.MEDICATION_NOT_FOUND)

    suspend fun takeDose(dose: DashboardDose) = recordTerminalDose(dose, DoseEventStatus.TAKEN)

    suspend fun skipDose(dose: DashboardDose) = recordTerminalDose(dose, DoseEventStatus.SKIPPED)

    suspend fun snoozeDose(dose: DashboardDose) = core.operation {
        val data = core.requireData()
        require(dose.profileId == data.profile.profileId)
        val now = currentInstant()
        val snoozedAt = kotlin.time.Instant.fromEpochSeconds(now.epochSeconds + 600)
        val event = DoseEvent(
            profileId = data.profile.profileId,
            eventId = DoseEvents.snoozeIdFor(dose.scheduleId, snoozedAt),
            medicationId = dose.medicationId,
            scheduleId = dose.scheduleId,
            scheduledFor = snoozedAt,
            status = DoseEventStatus.SCHEDULED,
            recordedAt = now,
            replacesScheduledFor = dose.scheduledFor,
        )
        if (data.doseEvents.none { it.eventId == event.eventId }) repository.addDoseEvent(event)
        core.loadProfile(data.profile.profileId)
    }

    private suspend fun recordTerminalDose(dose: DashboardDose, status: DoseEventStatus) = core.operation {
        val data = core.requireData()
        require(dose.profileId == data.profile.profileId)
        repository.addDoseEvent(
            DoseEvent(
                profileId = data.profile.profileId,
                eventId = DoseEvents.idFor(dose.scheduleId, dose.scheduledFor),
                medicationId = dose.medicationId,
                scheduleId = dose.scheduleId,
                scheduledFor = dose.scheduledFor,
                status = status,
                recordedAt = currentInstant(),
            ),
        )
        core.loadProfile(data.profile.profileId)
    }
}

private val REMINDER_TIME = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")
private const val MAX_SUPPLY = 100_000
