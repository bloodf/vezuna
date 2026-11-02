package app.vezuna.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.vezuna.NativeKey
import app.vezuna.VezunaApplication
import app.vezuna.nativeMessage
import app.vezuna.domain.DoseEvent
import app.vezuna.domain.DoseEvents
import app.vezuna.ui.DashboardRefreshSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.time.Instant

private const val TAG = "VezunaReminders"

/**
 * Runs [block] inside goAsync(): a failure is logged and swallowed so a bad intent or storage error never crashes the
 * process, and the broadcast is always finished.
 */
private fun guarded(app: VezunaApplication, pending: BroadcastReceiver.PendingResult, name: String, block: suspend () -> Unit) {
    app.applicationScope.launch {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "$name failed", error)
        } finally {
            pending.finish()
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as VezunaApplication
        guarded(app, goAsync(), "ReminderReceiver") {
            val ready = app.awaitRepository()
            // With the vault unavailable we cannot tell whether the medication is current; still remind the user.
            try {
                if (!ready || isCurrent(app, intent)) app.notifications.showReminder(intent)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // A failed notification must not skip re-arming the next alarm in the chain.
                Log.e(TAG, "Could not show reminder", error)
            }
            if (ready) {
                app.notifications.rescheduleAll(app.repository)
                DashboardRefreshSignal.notifyRepositoryChanged()
            }
        }
    }
}

/** A snoozed alarm survives edits, so re-check the medication before notifying. */
private suspend fun isCurrent(app: VezunaApplication, intent: Intent): Boolean {
    val profileId = intent.getStringExtra(MedicationNotificationScheduler.EXTRA_PROFILE_ID) ?: return false
    val medicationId = intent.getStringExtra(MedicationNotificationScheduler.EXTRA_MEDICATION_ID) ?: return false
    val scheduleId = intent.getStringExtra(MedicationNotificationScheduler.EXTRA_SCHEDULE_ID) ?: return false
    val data = try {
        app.repository.readProfile(profileId)
    } catch (missing: NoSuchElementException) {
        null
    }
    return NotificationScheduleLogic.isReminderCurrent(data, medicationId, scheduleId)
}

class DoseActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as VezunaApplication
        guarded(app, goAsync(), "DoseActionReceiver") {
            val action = DoseAction.parse(intent.action)
            if (action == null) {
                Log.w(TAG, "Ignoring unknown dose action ${intent.action}")
                return@guarded
            }
            if (action != DoseAction.SNOOZE && !app.awaitRepository()) {
                // Leave the notification and its actions in place and tell the user why nothing was recorded.
                app.notifications.showReminder(intent, nativeMessage(NativeKey.DOSE_RECORD_UNAVAILABLE))
                return@guarded
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
        }
    }

    private suspend fun recordDose(app: VezunaApplication, intent: Intent, action: DoseAction) {
        val status = action.status ?: return
        // A notification left over from a deleted medication or profile must not crash or write orphan events.
        if (!isCurrent(app, intent)) return
        val scheduledEpoch = intent.getLongExtra(
            MedicationNotificationScheduler.EXTRA_ORIGINAL_SCHEDULED_FOR,
            intent.getLongExtra(MedicationNotificationScheduler.EXTRA_SCHEDULED_FOR, -1L),
        )
        val profileId = intent.getStringExtra(MedicationNotificationScheduler.EXTRA_PROFILE_ID) ?: return
        val medicationId = intent.getStringExtra(MedicationNotificationScheduler.EXTRA_MEDICATION_ID) ?: return
        val scheduleId = intent.getStringExtra(MedicationNotificationScheduler.EXTRA_SCHEDULE_ID) ?: return
        if (scheduledEpoch < 0) {
            Log.w(TAG, "Reminder action without a scheduled time")
            return
        }
        val scheduledFor = Instant.fromEpochMilliseconds(scheduledEpoch)
        // The repository enforces one terminal status per dose, so a repeated action is a no-op.
        app.repository.addDoseEvent(
            DoseEvent(
                profileId = profileId,
                eventId = DoseEvents.idFor(scheduleId, scheduledFor),
                medicationId = medicationId,
                scheduleId = scheduleId,
                scheduledFor = scheduledFor,
                status = status,
                recordedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            ),
        )
    }
}

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as VezunaApplication
        guarded(app, goAsync(), "RescheduleReceiver") {
            if (app.awaitRepository()) {
                app.notifications.rescheduleAll(app.repository)
                DashboardRefreshSignal.notifyRepositoryChanged()
            }
        }
    }
}
