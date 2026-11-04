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
 * opted in and has not started yet; deleting the appointment or switching the reminder off removes it from the list, which
 * is what makes the platform cancel the alarm.
 *
 * A reminder whose time has passed is still listed while the visit is ahead, unless the platform already [handled] it
 * (delivered on Android, handed to the system on iOS): that is how a reminder due while the phone was off or the vault was not
 * ready still fires, late, and never fires twice. [Notice.fireAt] stays the original time; the platform fires a late one right away.
 */
object AppointmentReminders {
    data class Notice(val profileId: String, val appointmentId: String, val clinician: String, val appointmentAt: Instant, val fireAt: Instant) {
        /** Changes when the visit time or lead time changes, so a moved visit is reminded again. */
        val key: String get() = "$appointmentId:${fireAt.epochSeconds}"
    }

    fun notices(data: ProfileData, now: Instant, handled: Set<String> = emptySet()): List<Notice> = data.appointments.mapNotNull { appointment ->
        val fireAt = appointment.reminderAt ?: return@mapNotNull null
        if (appointment.at <= now) return@mapNotNull null
        val notice = Notice(appointment.profileId, appointment.appointmentId, appointment.clinician, appointment.at, fireAt)
        notice.takeIf { fireAt > now || it.key !in handled }
    }.sortedBy { it.fireAt }

    /** Notices of every profile, soonest first across all of them, so a cap keeps the nearest reminders of the household. */
    fun noticesOf(profiles: List<ProfileData>, now: Instant, handled: Set<String> = emptySet()): List<Notice> =
        profiles.flatMap { notices(it, now, handled) }.sortedBy { it.fireAt }

    /** Appointments with a reminder on whose visit has not started: the ones a pending request or a handled key may still serve. */
    fun aheadIds(data: ProfileData, now: Instant): Set<String> =
        data.appointments.filter { it.reminderMinutesBefore != null && it.at > now }.map { it.appointmentId }.toSet()

    /** The [Notice.key] each visit still ahead has now: a pending request with any other key is out of date. */
    fun currentKeys(data: ProfileData, now: Instant): Set<String> = data.appointments.mapNotNull { appointment ->
        val fireAt = appointment.reminderAt?.takeIf { appointment.at > now } ?: return@mapNotNull null
        Notice(appointment.profileId, appointment.appointmentId, appointment.clinician, appointment.at, fireAt).key
    }.toSet()

    /** Every appointment that currently has a reminder switched on, whether or not it is due: what a delivered notification must still match. */
    fun reminderIds(data: ProfileData): Set<String> = data.appointments.filter { it.reminderMinutesBefore != null }.map { it.appointmentId }.toSet()
}
