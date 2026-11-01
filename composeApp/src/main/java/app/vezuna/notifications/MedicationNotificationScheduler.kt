package app.vezuna.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.vezuna.NativeKey
import app.vezuna.NativeText
import app.vezuna.R
import app.vezuna.nativeMessage
import app.vezuna.resolve
import app.vezuna.data.PersistentVezunaRepository
import app.vezuna.domain.DoseEventStatus
import app.vezuna.domain.Medication
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

class MedicationNotificationScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val reconciliationMutex = Mutex()

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.medication_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.medication_channel_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    suspend fun rescheduleAll(repository: PersistentVezunaRepository, now: Instant = Instant.now()) = reconciliationMutex.withLock {
        val zone = ZoneId.systemDefault()
        repository.profiles.value.forEach { profile ->
            val data = repository.readProfile(profile.profileId)
            val terminalEvents = data.doseEvents.filter {
                it.status == DoseEventStatus.TAKEN || it.status == DoseEventStatus.SKIPPED || it.status == DoseEventStatus.MISSED
            }
            val scheduledEvents = data.doseEvents.filter { it.status == DoseEventStatus.SCHEDULED }
            data.medications.filter(Medication::active).forEach { medication ->
                medication.schedules.forEach { schedule ->
                    val occurrence = NotificationScheduleLogic.nextOccurrences(schedule, now, zone).firstOrNull() ?: return@forEach
                    val at = occurrence.toInstant()
                    val alarmId = "${schedule.scheduleId}:${at.epochSecond}"
                    cancelAlarm(medication, schedule.scheduleId, at, alarmId)
                    val completed = terminalEvents.any { it.scheduleId == schedule.scheduleId && it.scheduledFor.epochSeconds == at.epochSecond }
                    val superseded = scheduledEvents.any { it.scheduleId == schedule.scheduleId && it.replacesScheduledFor?.epochSeconds == at.epochSecond }
                    if (!completed && !superseded) {
                        alarms.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            at.toEpochMilli(),
                            showIntent(medication, schedule.scheduleId, at, PendingIntent.FLAG_UPDATE_CURRENT, alarmId),
                        )
                    }
                }
            }
            scheduledEvents.distinctBy { it.scheduleId to it.scheduledFor }.forEach { event ->
                val medication = data.medications.firstOrNull { it.medicationId == event.medicationId } ?: return@forEach
                val at = java.time.Instant.ofEpochSecond(event.scheduledFor.epochSeconds)
                val alarmId = "${event.scheduleId}:${event.scheduledFor.epochSeconds}"
                cancelAlarm(medication, event.scheduleId, at, alarmId)
                val completed = terminalEvents.any { it.scheduleId == event.scheduleId && it.scheduledFor == event.scheduledFor }
                val superseded = scheduledEvents.any { it.scheduleId == event.scheduleId && it.replacesScheduledFor == event.scheduledFor }
                if (medication.active && event.scheduledFor.epochSeconds > now.epochSecond && !completed && !superseded) {
                    alarms.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        at.toEpochMilli(),
                        showIntent(medication, event.scheduleId, at, PendingIntent.FLAG_UPDATE_CURRENT, alarmId),
                    )
                }
            }
        }
    }

    fun snooze(original: Intent) {
        val at = Instant.now().plusMillis(SNOOZE_MS)
        val intent = Intent(original).apply {
            setClass(context, ReminderReceiver::class.java)
            action = ACTION_SHOW
            if (!hasExtra(EXTRA_ORIGINAL_SCHEDULED_FOR)) putExtra(EXTRA_ORIGINAL_SCHEDULED_FOR, original.getLongExtra(EXTRA_SCHEDULED_FOR, -1L))
            putExtra(EXTRA_SCHEDULED_FOR, at.toEpochMilli())
            data = Uri.parse("vezuna://reminder/snooze/${at.toEpochMilli()}/${original.getStringExtra(EXTRA_SCHEDULE_ID)}")
        }
        val pending = PendingIntent.getBroadcast(context, requestCode(original.getStringExtra(EXTRA_SCHEDULE_ID), at), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending)
    }

    /** Re-posts the same notification (actions intact) with a note, so an unrecordable action is never silently dropped. */
    fun showReminder(intent: Intent, note: NativeText? = null) {
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val medicationName = intent.getStringExtra(EXTRA_MEDICATION_NAME).orEmpty()
        val content = PendingIntent.getActivity(
            context,
            notificationId,
            context.packageManager.getLaunchIntentForPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(nativeMessage(NativeKey.MEDICATION_REMINDER_TITLE).resolve(context))
            .setContentText(medicationName)
            .setContentIntent(content)
            .setAutoCancel(true)
            .setSubText(note?.resolve(context))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, nativeMessage(NativeKey.TAKEN).resolve(context), actionIntent(intent, DoseAction.TAKEN, notificationId + 1))
            .addAction(0, nativeMessage(NativeKey.SKIP).resolve(context), actionIntent(intent, DoseAction.SKIP, notificationId + 2))
            .addAction(0, nativeMessage(NativeKey.MINUTES_SNOOZE).resolve(context), actionIntent(intent, DoseAction.SNOOZE, notificationId + 3))
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        }
    }

    fun cancel(notificationId: Int) = NotificationManagerCompat.from(context).cancel(notificationId)

    private fun showIntent(medication: Medication, scheduleId: String, at: Instant, flags: Int, alarmId: String = scheduleId): PendingIntent {
        val notificationId = alarmId.hashCode()
        return PendingIntent.getBroadcast(context, notificationId, reminderIntent(medication, scheduleId, at, alarmId), flags or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun cancelAlarm(medication: Medication, scheduleId: String, at: Instant, alarmId: String) {
        val notificationId = alarmId.hashCode()
        val pending = PendingIntent.getBroadcast(
            context,
            notificationId,
            reminderIntent(medication, scheduleId, at, alarmId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarms.cancel(pending)
        pending.cancel()
    }

    private fun reminderIntent(medication: Medication, scheduleId: String, at: Instant, alarmId: String): Intent {
        val notificationId = alarmId.hashCode()
        return Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_SHOW
            data = Uri.parse("vezuna://reminder/${medication.medicationId}/$alarmId")
            putExtra(EXTRA_PROFILE_ID, medication.profileId)
            putExtra(EXTRA_MEDICATION_ID, medication.medicationId)
            putExtra(EXTRA_MEDICATION_NAME, medication.displayName)
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(EXTRA_SCHEDULED_FOR, at.toEpochMilli())
            putExtra(EXTRA_ORIGINAL_SCHEDULED_FOR, at.toEpochMilli())
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
    }

    private fun actionIntent(original: Intent, action: DoseAction, requestCode: Int): PendingIntent {
        val intent = Intent(original).apply {
            setClass(context, DoseActionReceiver::class.java)
            this.action = action.name
            putExtra(EXTRA_NOTIFICATION_ID, original.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val CHANNEL_ID = "recorded-medication-reminders"
        const val ACTION_SHOW = "app.vezuna.action.SHOW_RECORDED_REMINDER"
        const val EXTRA_PROFILE_ID = "profileId"
        const val EXTRA_MEDICATION_ID = "medicationId"
        const val EXTRA_MEDICATION_NAME = "medicationName"
        const val EXTRA_SCHEDULE_ID = "scheduleId"
        const val EXTRA_SCHEDULED_FOR = "scheduledFor"
        const val EXTRA_ORIGINAL_SCHEDULED_FOR = "originalScheduledFor"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
        const val SNOOZE_MS = 10 * 60 * 1000L

        fun requestCode(scheduleId: String?, at: Instant): Int = 31 * (scheduleId ?: "snooze").hashCode() + at.epochSecond.hashCode()
    }
}

enum class DoseAction(val status: DoseEventStatus?) {
    TAKEN(DoseEventStatus.TAKEN),
    SKIP(DoseEventStatus.SKIPPED),
    SNOOZE(null),
}
