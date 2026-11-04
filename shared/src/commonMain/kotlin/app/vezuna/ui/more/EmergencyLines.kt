package app.vezuna.ui.more

import app.vezuna.domain.ConditionRecord
import app.vezuna.domain.EmergencyMedication
import app.vezuna.localization.L10nKey
import kotlinx.datetime.LocalDate

/** One detail line on an emergency card entry: a label [key] (with an optional [date] argument) or text the person typed. */
data class EmergencyLine(val key: L10nKey? = null, val date: LocalDate? = null, val text: String? = null)

fun conditionLines(condition: ConditionRecord): List<EmergencyLine> =
    // Resolved conditions never reach the card, so a stated status here is ongoing.
    listOf(EmergencyLine(if (condition.status == null) L10nKey.EM_CONDITION_UNKNOWN else L10nKey.HR_STATUS_ONGOING)) + listOfNotNull(condition.notes?.let { EmergencyLine(text = it) })

fun medicationLines(entry: EmergencyMedication): List<EmergencyLine> {
    val medication = entry.medication
    return listOfNotNull(
        medication.strength?.let { EmergencyLine(text = it) } ?: EmergencyLine(L10nKey.EM_DOSE_NONE),
        medication.instructions?.let { EmergencyLine(text = it) },
        entry.startsOn?.let { EmergencyLine(L10nKey.EM_MED_STARTS, it) },
        if (entry.endedToday) EmergencyLine(L10nKey.EM_MED_ENDED_TODAY) else null,
        if (entry.paused) EmergencyLine(L10nKey.EM_MED_PAUSED) else null,
    )
}

/** Medications under the "Medications" heading (including paused ones) and those under "Starting later". */
data class MedicationSections(val current: List<EmergencyMedication>, val later: List<EmergencyMedication>)

fun splitMedications(medications: List<EmergencyMedication>): MedicationSections =
    medications.partition { it.startsOn != null }.let { (later, current) -> MedicationSections(current, later) }
