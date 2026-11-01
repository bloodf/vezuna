package app.vezuna.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.vezuna.NativeKey
import app.vezuna.VezunaApplication
import app.vezuna.nativeMessage
import app.vezuna.domain.DoseEvent
import app.vezuna.ui.DashboardRefreshSignal
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
        val app = context.applicationContext as VezunaApplication
        val pending = goAsync()
        app.applicationScope.launch {
            try {
                app.notifications.showReminder(intent)
                if (app.awaitRepository()) {
                    app.notifications.rescheduleAll(app.repository)
                    DashboardRefreshSignal.notifyRepositoryChanged()
                }
            } finally {
                pending.finish()
            }
        }
    }
}

class DoseActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as VezunaApplication
        val pending = goAsync()
        app.applicationScope.launch {
            try {
                val action = DoseAction.valueOf(intent.action.orEmpty())
                if (action != DoseAction.SNOOZE && !app.awaitRepository()) {
                    // Leave the notification and its actions in place and tell the user why nothing was recorded.
                    app.notifications.showReminder(intent, nativeMessage(NativeKey.DOSE_RECORD_UNAVAILABLE))
                    return@launch
                }
                when (action) {
                    DoseAction.SNOOZE -> app.notifications.snooze(intent)
                    DoseAction.TAKEN, DoseAction.SKIP -> recordDose(app, intent, action)
                }
                app.notifications.cancel(intent.getIntExtra(MedicationNotificationScheduler.EXTRA_NOTIFICATION_ID, 0))
                if (app.awaitRepository()) {
                    app.notifications.rescheduleAll(app.repository)
                    DashboardRefreshSignal.notifyRepositoryChanged()
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun recordDose(app: VezunaApplication, intent: Intent, action: DoseAction) {
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
        val app = context.applicationContext as VezunaApplication
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (app.awaitRepository()) {
                    app.notifications.rescheduleAll(app.repository)
                    DashboardRefreshSignal.notifyRepositoryChanged()
                }
            } catch (error: CancellationException) {
                throw error
            } finally {
                pending.finish()
            }
        }
    }
}
