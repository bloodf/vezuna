package app.vezuna.domain

import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * A visit with a clinician (#101), typed by the owner and kept as written. [reminderMinutesBefore] is the opt-in local
 * reminder: null means none. Reminders are scheduled on the device only and carry no data over any network.
 */
@Serializable
data class Appointment(
    val profileId: String,
    val appointmentId: String,
    /** The exact moment of the visit; shown in the device's time zone. */
    val at: Instant,
    val clinician: String,
    val notes: String? = null,
    val reminderMinutesBefore: Int? = null,
) {
    init {
        require(clinician.isNotBlank())
        require(reminderMinutesBefore == null || reminderMinutesBefore in 1..MAX_REMINDER_MINUTES)
    }

    /** When the reminder fires, regardless of the clock. */
    val reminderAt: Instant? get() = reminderMinutesBefore?.let { at - it.minutes }

    companion object {
        const val MAX_REMINDER_MINUTES = 7 * 24 * 60

        /** What the form offers; stored values outside it (a restored file) are still honoured. */
        val REMINDER_CHOICES = listOf(15, 60, 24 * 60)
    }
}

/** Soonest first. A visit that starts right now is still upcoming. */
fun ProfileData.upcomingAppointments(now: Instant): List<Appointment> = appointments.filter { it.at >= now }.sortedBy { it.at }

/** Most recent first. */
fun ProfileData.pastAppointments(now: Instant): List<Appointment> = appointments.filter { it.at < now }.sortedByDescending { it.at }

fun ProfileData.withNewAppointment(appointment: Appointment): ProfileData {
    require(appointment.profileId == profile.profileId)
    require(appointments.none { it.appointmentId == appointment.appointmentId })
    return copy(appointments = appointments + appointment)
}

fun ProfileData.withAppointment(appointment: Appointment): ProfileData {
    require(appointment.profileId == profile.profileId)
    require(appointments.any { it.appointmentId == appointment.appointmentId }) { "Appointment not found" }
    return copy(appointments = appointments.map { if (it.appointmentId == appointment.appointmentId) appointment else it })
}

fun ProfileData.withoutAppointment(appointmentId: String): ProfileData {
    require(appointments.any { it.appointmentId == appointmentId }) { "Appointment not found" }
    return copy(appointments = appointments.filterNot { it.appointmentId == appointmentId })
}

/**
 * Local reminders for upcoming visits, the one rule both platforms share. A reminder exists only for an appointment that
 * opted in and whose reminder time is still ahead; deleting the appointment or switching the reminder off removes it from
 * the list, which is what makes the platform cancel the alarm.
 */
object AppointmentReminders {
    data class Notice(val profileId: String, val appointmentId: String, val clinician: String, val appointmentAt: Instant, val fireAt: Instant)

    fun notices(data: ProfileData, now: Instant): List<Notice> = data.appointments.mapNotNull { appointment ->
        val fireAt = appointment.reminderAt?.takeIf { it > now } ?: return@mapNotNull null
        Notice(appointment.profileId, appointment.appointmentId, appointment.clinician, appointment.at, fireAt)
    }.sortedBy { it.fireAt }
}
