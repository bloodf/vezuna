package com.bloodf.vitahearth.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bloodf.vitahearth.VitaHearthApplication
import com.bloodf.vitahearth.domain.DoseEvent
import com.bloodf.vitahearth.ui.DashboardRefreshSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Instant
import java.nio.charset.StandardCharsets
import java.util.UUID

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as VitaHearthApplication
        val pending = goAsync()
        app.applicationScope.launch {
            try {
                app.notifications.showReminder(intent)
                app.notifications.rescheduleAll(app.repository)
                DashboardRefreshSignal.notifyRepositoryChanged()
            } finally {
                pending.finish()
            }
        }
    }
}

class DoseActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as VitaHearthApplication
        val pending = goAsync()
        app.applicationScope.launch {
            try {
                val action = DoseAction.valueOf(intent.action.orEmpty())
                when (action) {
                    DoseAction.SNOOZE -> app.notifications.snooze(intent)
                    DoseAction.TAKEN, DoseAction.SKIP -> recordDose(app, intent, action)
                }
                app.notifications.cancel(intent.getIntExtra(MedicationNotificationScheduler.EXTRA_NOTIFICATION_ID, 0))
                app.notifications.rescheduleAll(app.repository)
                DashboardRefreshSignal.notifyRepositoryChanged()
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun recordDose(app: VitaHearthApplication, intent: Intent, action: DoseAction) {
        val status = action.status ?: return
        val scheduledEpoch = intent.getLongExtra(
            MedicationNotificationScheduler.EXTRA_ORIGINAL_SCHEDULED_FOR,
            intent.getLongExtra(MedicationNotificationScheduler.EXTRA_SCHEDULED_FOR, -1L),
        )
        val profileId = intent.getStringExtra(MedicationNotificationScheduler.EXTRA_PROFILE_ID) ?: return
        val medicationId = intent.getStringExtra(MedicationNotificationScheduler.EXTRA_MEDICATION_ID) ?: return
        val scheduleId = intent.getStringExtra(MedicationNotificationScheduler.EXTRA_SCHEDULE_ID) ?: return
        require(scheduledEpoch >= 0) { "Missing reminder time" }
        val eventId = UUID.nameUUIDFromBytes("$profileId/$medicationId/$scheduleId/$scheduledEpoch/${action.name}".toByteArray(StandardCharsets.UTF_8)).toString()
        try {
            app.repository.addDoseEvent(
                DoseEvent(
                    profileId = profileId,
                    eventId = eventId,
                    medicationId = medicationId,
                    scheduleId = scheduleId,
                    scheduledFor = Instant.fromEpochMilliseconds(scheduledEpoch),
                    status = status,
                    recordedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                ),
            )
        } catch (error: IllegalArgumentException) {
            if (app.repository.readProfile(profileId).doseEvents.none { it.eventId == eventId }) throw error
        }
    }
}

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as VitaHearthApplication
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.notifications.rescheduleAll(app.repository)
                DashboardRefreshSignal.notifyRepositoryChanged()
            } catch (error: CancellationException) {
                throw error
            } finally {
                pending.finish()
            }
        }
    }
}
