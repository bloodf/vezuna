package app.vezuna.ui.records

import app.vezuna.localization.L10nKey
import app.vezuna.localization.failMessage
import app.vezuna.localization.message
import app.vezuna.platform.randomId
import app.vezuna.ui.ControllerCore

/** Add, edit and delete of visits for the current profile. Reminders follow the stored list on both platforms. */
internal class AppointmentsController(private val core: ControllerCore) {
    private val repository get() = core.repository

    /** Null [appointmentId] adds a new visit. */
    suspend fun save(appointmentId: String?, draft: AppointmentDraft) = core.operation {
        val data = core.requireData()
        val zone = core.timeZone()
        if (draft.clinicianMissing) failMessage(L10nKey.APPT_CLINICIAN_REQUIRED)
        if (draft.dateMissing || draft.at(zone) == null) failMessage(L10nKey.APPT_DATE_REQUIRED)
        val existing = appointmentId?.let { id -> data.appointments.firstOrNull { it.appointmentId == id } }
        if (draft.reminderPassed(core.clock(), zone, existing)) failMessage(L10nKey.APPT_REMINDER_PASSED)
        val profileId = data.profile.profileId
        if (appointmentId == null) {
            repository.addAppointment(draft.toAppointment(profileId, randomId(), zone))
        } else {
            if (data.appointments.none { it.appointmentId == appointmentId }) failMessage(L10nKey.APPT_NOT_FOUND)
            repository.updateAppointment(draft.toAppointment(profileId, appointmentId, zone))
        }
        core.reloadCurrentProfile()
        core.mutableState.value = core.mutableState.value.copy(notice = message(L10nKey.APPT_SAVED))
    }

    suspend fun delete(appointmentId: String) = core.operation {
        val data = core.requireData()
        if (data.appointments.none { it.appointmentId == appointmentId }) failMessage(L10nKey.APPT_NOT_FOUND)
        repository.deleteAppointment(data.profile.profileId, appointmentId)
        core.reloadCurrentProfile()
        core.mutableState.value = core.mutableState.value.copy(notice = message(L10nKey.APPT_DELETED))
    }
}
