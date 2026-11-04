package app.vezuna.ui.records

import app.vezuna.domain.Appointment
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val TIME = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")

/** The visit form. [date] is the calendar day the date field returns (noon UTC); [time] is HH:mm on the device clock. */
internal data class AppointmentDraft(
    val date: Instant? = null,
    val time: String = "09:00",
    val clinician: String = "",
    val notes: String = "",
    val reminderMinutesBefore: Int? = null,
) {
    val clinicianMissing: Boolean get() = clinician.isBlank()
    val dateMissing: Boolean get() = date == null

    /** The moment of the visit in [zone]; null while the date or time is not usable. */
    fun at(zone: TimeZone): Instant? {
        val day = date?.toLocalDateTime(TimeZone.UTC)?.date ?: return null
        if (!TIME.matches(time)) return null
        val (hour, minute) = time.split(":").map(String::toInt)
        return day.atTime(LocalTime(hour, minute)).toInstant(zone)
    }

    /**
     * A reminder that would already have fired can never be delivered, so a new or changed one is refused instead of saved
     * silently. Saving other edits (notes) of a visit whose unchanged reminder already fired is fine.
     */
    fun reminderPassed(now: Instant, zone: TimeZone, existing: Appointment? = null): Boolean {
        val lead = reminderMinutesBefore ?: return false
        val at = at(zone) ?: return false
        if (existing != null && existing.at == at && existing.reminderMinutesBefore == lead) return false
        return at - lead.minutes <= now
    }

    fun isValid(now: Instant, zone: TimeZone, existing: Appointment? = null): Boolean =
        !clinicianMissing && at(zone) != null && !reminderPassed(now, zone, existing)

    /** Call only when [isValid]. Surrounding spaces go; every other character stays as written. */
    fun toAppointment(profileId: String, id: String, zone: TimeZone): Appointment =
        Appointment(profileId, id, at(zone)!!, clinician.trim(), notes.trim().ifEmpty { null }, reminderMinutesBefore)
}

internal fun Appointment.toDraft(zone: TimeZone): AppointmentDraft {
    val local = at.toLocalDateTime(zone)
    return AppointmentDraft(
        date = local.date.atTime(LocalTime(12, 0)).toInstant(TimeZone.UTC),
        time = "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}",
        clinician = clinician,
        notes = notes.orEmpty(),
        reminderMinutesBefore = reminderMinutesBefore,
    )
}

/** In memory only, like [HealthRecordFormSession]: survives a rotation, never reaches a saved-instance Bundle. */
internal object AppointmentFormSession {
    private var kept: Pair<Any, AppointmentDraft>? = null

    fun restore(key: Any): AppointmentDraft? = kept?.takeIf { it.first == key }?.second

    fun keep(key: Any, draft: AppointmentDraft?) {
        kept = draft?.let { key to it }
    }
}
