package app.vezuna.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.vezuna.NativeKey
import app.vezuna.NativeText
import app.vezuna.R
import app.vezuna.nativeMessage
import app.vezuna.resolve
import app.vezuna.data.VaultBackedRepository
import app.vezuna.domain.AppointmentReminders
import app.vezuna.domain.DoseEventStatus
import app.vezuna.domain.DoseEvents
import app.vezuna.domain.Medication
import app.vezuna.domain.MedicationSupplies
import app.vezuna.domain.MissedDoses
import app.vezuna.domain.ProfileData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

class MedicationNotificationScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val reconciliationMutex = Mutex()
    private val preferences = ReminderPreferences(context)
    private val armedStore = context.getSharedPreferences(ARMED_PREFS, Context.MODE_PRIVATE)
    private val supplyStore = context.getSharedPreferences(SUPPLY_PREFS, Context.MODE_PRIVATE)
    private val appointmentStore = context.getSharedPreferences(APPOINTMENT_PREFS, Context.MODE_PRIVATE)

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.medication_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.medication_channel_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(APPOINTMENT_CHANNEL_ID, context.getString(R.string.appointment_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.appointment_channel_description) },
        )
    }

    suspend fun rescheduleAll(repository: VaultBackedRepository, now: Instant = Instant.now()) = reconciliationMutex.withLock {
        val zone = ZoneId.systemDefault()
        val armed = mutableSetOf<String>()
        val loaded = mutableMapOf<String, ProfileData>()
        val supplyKeys = mutableSetOf<String>()
        val appointmentIds = mutableSetOf<String>()
        var failed = false
        repository.profiles.value.forEach { profile ->
            try {
                val data = repository.peekProfile(profile.profileId)
                loaded[profile.profileId] = data
                try {
                    recordMissedDoses(repository, data, now, zone)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Missed-dose bookkeeping must never stop reminders from being armed.
                    Log.e(TAG, "Could not record missed doses", error)
                }
                // The member name only disambiguates reminders when the household has more than one profile.
                val profileName = data.profile.displayName.takeIf { repository.profiles.value.size > 1 }
                armProfile(data, now, zone, armed, profileName)
                armSupply(data, now, zone, supplyKeys)
                armAppointments(data, now, appointmentIds, profileName)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // One unreadable profile must not stop the others from being armed.
                failed = true
                Log.e(TAG, "Could not arm reminders for a profile", error)
            }
        }
        if (failed) {
            // Keep what an earlier pass armed: without this profile's data its alarms would look stale.
            val previous = armedStore.getStringSet(ARMED_KEY, emptySet()).orEmpty()
            armedStore.edit().putStringSet(ARMED_KEY, previous + armed).apply()
            val previousVisits = appointmentStore.getStringSet(ARMED_KEY, emptySet()).orEmpty()
            appointmentStore.edit().putStringSet(ARMED_KEY, previousVisits + appointmentIds).apply()
            return@withLock
        }
        cancelStale(armed)
        cancelStaleNotifications(loaded)
        forgetSupplyKeys(supplyKeys)
        cancelStaleAppointments(appointmentIds)
    }

    /**
     * Visit reminders: one local alarm per opted-in upcoming appointment, replaced on every pass. Deleting the appointment or
     * switching its reminder off drops it from [AppointmentReminders.notices], and [cancelStaleAppointments] then cancels the alarm.
     */
    private fun armAppointments(data: ProfileData, now: Instant, armed: MutableSet<String>, profileName: String?) {
        AppointmentReminders.notices(data, kotlin.time.Instant.fromEpochSeconds(now.epochSecond)).forEach { notice ->
            armed += notice.appointmentId
            setAlarm(notice.fireAt.toEpochMilliseconds(), appointmentIntent(notice.appointmentId, PendingIntent.FLAG_UPDATE_CURRENT) {
                putExtra(EXTRA_PROFILE_ID, notice.profileId)
                putExtra(EXTRA_CLINICIAN, notice.clinician)
                putExtra(EXTRA_APPOINTMENT_AT, notice.appointmentAt.toEpochMilliseconds())
                profileName?.let { putExtra(EXTRA_PROFILE_NAME, it) }
            }!!)
        }
    }

    private fun cancelStaleAppointments(armed: Set<String>) {
        val previous = appointmentStore.getStringSet(ARMED_KEY, emptySet()).orEmpty()
        NotificationScheduleLogic.staleArmedKeys(previous, armed).forEach { id ->
            appointmentIntent(id, PendingIntent.FLAG_NO_CREATE)?.let {
                alarms.cancel(it)
                it.cancel()
            }
        }
        appointmentStore.edit().putStringSet(ARMED_KEY, armed.toSet()).apply()
    }

    private fun appointmentIntent(appointmentId: String, flags: Int, extras: Intent.() -> Unit = {}): PendingIntent? {
        val intent = Intent(context, AppointmentReceiver::class.java).apply {
            action = ACTION_APPOINTMENT
            data = Uri.parse("vezuna://appointment/$appointmentId")
            putExtra(EXTRA_APPOINTMENT_ID, appointmentId)
            extras()
        }
        return PendingIntent.getBroadcast(context, appointmentNotificationId(appointmentId), intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun appointmentNotificationId(appointmentId: String): Int = "appointment:$appointmentId".hashCode()

    /** Shown by [AppointmentReceiver] once the alarm fires and the appointment has been re-checked. */
    fun showAppointmentReminder(appointmentId: String, clinician: String, appointmentAtMillis: Long, profileName: String? = null) {
        val hideName = preferences.hideMedicationNames
        val title = nativeMessage(NativeKey.APPOINTMENT_REMINDER_TITLE).resolve(context)
        val generic = nativeMessage(NativeKey.APPOINTMENT_REMINDER_GENERIC_TEXT).resolve(context)
        val whenText = java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)
            .withLocale(java.util.Locale.getDefault()).format(Instant.ofEpochMilli(appointmentAtMillis).atZone(ZoneId.systemDefault()))
        val builder = NotificationCompat.Builder(context, APPOINTMENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (hideName) generic else nativeMessage(NativeKey.APPOINTMENT_REMINDER_TEXT, clinician, whenText).resolve(context))
            .setSubText(profileName?.takeUnless { hideName }?.let { nativeMessage(NativeKey.REMINDER_FOR_PROFILE, it).resolve(context) })
            .setVisibility(if (hideName) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .apply {
                if (hideName) {
                    setPublicVersion(
                        NotificationCompat.Builder(context, APPOINTMENT_CHANNEL_ID).setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(generic).build(),
                    )
                }
            }
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    appointmentNotificationId(appointmentId),
                    context.packageManager.getLaunchIntentForPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(appointmentNotificationId(appointmentId), builder.build())
        }
    }

    /**
     * Low-supply reminders: one alarm per medication, replaced on every pass. [MedicationSupplies.fireTime] keeps a
     * notice that already fired from repeating, using the fire time stored for its key.
     */
    private fun armSupply(data: ProfileData, now: Instant, zone: ZoneId, keys: MutableSet<String>) {
        val kNow = kotlin.time.Instant.fromEpochSeconds(now.epochSecond)
        val notices = MedicationSupplies.notices(data, kNow, kotlinx.datetime.TimeZone.of(zone.id)).associateBy { it.medicationId }
        data.medications.forEach { medication ->
            val notice = notices[medication.medicationId]
            if (notice == null) {
                cancelSupplyAlarm(medication)
                return@forEach
            }
            keys += notice.key
            val stored = supplyStore.getLong(notice.key, -1L).takeIf { it >= 0 }
            val fireAt = MedicationSupplies.fireTime(notice, stored, kNow) ?: return@forEach
            supplyStore.edit().putLong(notice.key, fireAt).apply()
            supplyIntent(medication, PendingIntent.FLAG_UPDATE_CURRENT)?.let { setAlarm(fireAt * 1000, it) }
        }
    }

    private fun forgetSupplyKeys(keep: Set<String>) {
        val editor = supplyStore.edit()
        supplyStore.all.keys.filterNot { it in keep }.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun supplyIntent(medication: Medication, flags: Int): PendingIntent? {
        val intent = Intent(context, SupplyReceiver::class.java).apply {
            action = ACTION_SUPPLY
            data = Uri.parse("vezuna://supply/${medication.medicationId}")
            putExtra(EXTRA_PROFILE_ID, medication.profileId)
            putExtra(EXTRA_MEDICATION_ID, medication.medicationId)
        }
        return PendingIntent.getBroadcast(context, supplyNotificationId(medication.medicationId), intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun cancelSupplyAlarm(medication: Medication) {
        supplyIntent(medication, PendingIntent.FLAG_NO_CREATE)?.let {
            alarms.cancel(it)
            it.cancel()
        }
    }

    /** Shown by [SupplyReceiver] once the alarm fires; the caller has already confirmed the medication is low. */
    fun showLowSupply(medicationId: String, medicationName: String, remaining: Int, profileName: String? = null) {
        val hideName = preferences.hideMedicationNames
        val title = nativeMessage(NativeKey.SUPPLY_LOW_TITLE).resolve(context)
        val generic = nativeMessage(NativeKey.SUPPLY_LOW_GENERIC_TEXT).resolve(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (hideName) generic else nativeMessage(NativeKey.SUPPLY_LOW_TEXT, medicationName, remaining).resolve(context))
            .setSubText(profileName?.takeUnless { hideName }?.let { nativeMessage(NativeKey.REMINDER_FOR_PROFILE, it).resolve(context) })
            .setVisibility(if (hideName) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .apply {
                if (hideName) {
                    setPublicVersion(
                        NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(generic).build(),
                    )
                }
            }
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    supplyNotificationId(medicationId),
                    context.packageManager.getLaunchIntentForPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(supplyNotificationId(medicationId), builder.build())
        }
    }

    private fun supplyNotificationId(medicationId: String): Int = "supply:$medicationId".hashCode()

    /** Same rule as iOS: shared [MissedDoses] decides which past doses become MISSED; the repository keeps user actions on top. */
    private suspend fun recordMissedDoses(repository: VaultBackedRepository, data: ProfileData, now: Instant, zone: ZoneId) {
        MissedDoses.stampLegacy(data, kotlin.time.Instant.fromEpochSeconds(now.epochSecond)).forEach { repository.updateSchedule(it) }
        MissedDoses.pending(data, kotlin.time.Instant.fromEpochSeconds(now.epochSecond), kotlinx.datetime.TimeZone.of(zone.id))
            .let { repository.addDoseEvents(data.profile.profileId, it) }
    }

    private fun armProfile(data: ProfileData, now: Instant, zone: ZoneId, armed: MutableSet<String>, profileName: String?) {
        val terminalEvents = data.doseEvents.filter {
            it.status == DoseEventStatus.TAKEN || it.status == DoseEventStatus.SKIPPED || it.status == DoseEventStatus.MISSED
        }
        val scheduledEvents = data.doseEvents.filter { it.status == DoseEventStatus.SCHEDULED }
        data.medications.filter(Medication::active).forEach { medication ->
            medication.schedules.forEach { schedule ->
                val occurrence = NotificationScheduleLogic.nextOccurrences(schedule, now, zone).firstOrNull() ?: return@forEach
                val at = occurrence.toInstant()
                val alarmId = NotificationScheduleLogic.alarmId(schedule.scheduleId, at)
                cancelAlarm(medication, schedule.scheduleId, at, alarmId)
                val completed = terminalEvents.any { it.scheduleId == schedule.scheduleId && it.scheduledFor.epochSeconds == at.epochSecond }
                val superseded = scheduledEvents.any { it.scheduleId == schedule.scheduleId && it.replacesScheduledFor?.epochSeconds == at.epochSecond }
                if (!completed && !superseded) {
                    armed += armedKey(medication.medicationId, alarmId)
                    setAlarm(at.toEpochMilli(), showIntent(medication, schedule.scheduleId, at, PendingIntent.FLAG_UPDATE_CURRENT, alarmId, profileName))
                }
            }
        }
        val original = DoseEvents.originals(data.doseEvents)
        scheduledEvents.distinctBy { it.scheduleId to it.scheduledFor }.forEach { event ->
            val medication = data.medications.firstOrNull { it.medicationId == event.medicationId } ?: return@forEach
            val at = java.time.Instant.ofEpochSecond(event.scheduledFor.epochSeconds)
            val alarmId = NotificationScheduleLogic.alarmId(event.scheduleId, at)
            cancelAlarm(medication, event.scheduleId, at, alarmId)
            if (medication.active && event.scheduledFor.epochSeconds > now.epochSecond && DoseEvents.isOpen(data.doseEvents, event)) {
                armed += armedKey(medication.medicationId, alarmId)
                val first = java.time.Instant.ofEpochSecond(original(event.scheduleId, event.scheduledFor).epochSeconds)
                setAlarm(at.toEpochMilli(), showIntent(medication, event.scheduleId, at, PendingIntent.FLAG_UPDATE_CURRENT, alarmId, profileName, first))
            }
        }
    }

    /**
     * Alarms armed by an earlier pass for medications that are now paused, stopped or deleted (or whose
     * profile was deleted) are no longer produced by the loops above, so cancel them. Shown notifications are
     * handled separately: the alarm that just fired is also absent from [armed], and its notification must stay.
     */
    private fun cancelStale(armed: Set<String>) {
        val previous = armedStore.getStringSet(ARMED_KEY, emptySet()).orEmpty()
        NotificationScheduleLogic.staleArmedKeys(previous, armed).forEach { key ->
            val (medicationId, alarmId) = NotificationScheduleLogic.splitArmedKey(key) ?: return@forEach
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                action = ACTION_SHOW
                data = Uri.parse("vezuna://reminder/$medicationId/$alarmId")
            }
            PendingIntent.getBroadcast(context, alarmId.hashCode(), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
                alarms.cancel(it)
                it.cancel()
            }
        }
        armedStore.edit().putStringSet(ARMED_KEY, armed.toSet()).apply()
    }

    private fun cancelStaleNotifications(profiles: Map<String, ProfileData>) {
        val shown = context.getSystemService(NotificationManager::class.java).activeNotifications.mapNotNull { sbn ->
            val extras = sbn.notification.extras
            if (sbn.notification.channelId != CHANNEL_ID) return@mapNotNull null
            ShownReminder(
                sbn.id,
                extras.getString(EXTRA_PROFILE_ID) ?: return@mapNotNull null,
                extras.getString(EXTRA_MEDICATION_ID) ?: return@mapNotNull null,
                extras.getString(EXTRA_SCHEDULE_ID) ?: return@mapNotNull null,
            )
        }
        NotificationScheduleLogic.staleNotificationIds(shown, profiles).forEach { cancel(it) }
    }

    private fun armedKey(medicationId: String, alarmId: String) = NotificationScheduleLogic.armedKey(medicationId, alarmId)


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
        setAlarm(at.toEpochMilli(), pending)
    }

    /** False when the user (or Android 14+ default) has not allowed exact alarms; reminders then use inexact timing. */
    fun canScheduleExactAlarms(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    private fun setAlarm(atMillis: Long, pending: PendingIntent) {
        if (canScheduleExactAlarms()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
                return
            } catch (denied: SecurityException) {
                // Permission revoked between the check and the call: fall through to the inexact alarm.
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
    }

    /** Re-posts the same notification (actions intact) with a note, so an unrecordable action is never silently dropped. */
    fun showReminder(intent: Intent, note: NativeText? = null) {
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val medicationName = intent.getStringExtra(EXTRA_MEDICATION_NAME).orEmpty()
        val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME)
        val content = PendingIntent.getActivity(
            context,
            notificationId,
            context.packageManager.getLaunchIntentForPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hideName = preferences.hideMedicationNames
        val title = nativeMessage(NativeKey.MEDICATION_REMINDER_TITLE).resolve(context)
        val genericText = nativeMessage(NativeKey.MEDICATION_REMINDER_GENERIC_TEXT).resolve(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (hideName) genericText else medicationName)
            .setSubText(note?.resolve(context) ?: profileName?.takeUnless { hideName }?.let { nativeMessage(NativeKey.REMINDER_FOR_PROFILE, it).resolve(context) })
            // Names are shown on the lock screen by default; the Settings toggle swaps in a generic public version.
            .setVisibility(if (hideName) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .apply {
                if (hideName) {
                    setPublicVersion(
                        NotificationCompat.Builder(context, CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_notification)
                            .setContentTitle(title)
                            .setContentText(genericText)
                            .build(),
                    )
                }
            }
            .setContentIntent(content)
            .setAutoCancel(true)
            .addExtras(android.os.Bundle().apply {
                putString(EXTRA_PROFILE_ID, intent.getStringExtra(EXTRA_PROFILE_ID))
                putString(EXTRA_MEDICATION_ID, intent.getStringExtra(EXTRA_MEDICATION_ID))
                putString(EXTRA_SCHEDULE_ID, intent.getStringExtra(EXTRA_SCHEDULE_ID))
            })
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, nativeMessage(NativeKey.TAKEN).resolve(context), actionIntent(intent, DoseAction.TAKEN, notificationId + 1))
            .addAction(0, nativeMessage(NativeKey.SKIP).resolve(context), actionIntent(intent, DoseAction.SKIP, notificationId + 2))
            .addAction(0, nativeMessage(NativeKey.MINUTES_SNOOZE).resolve(context), actionIntent(intent, DoseAction.SNOOZE, notificationId + 3))
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        }
    }

    fun cancel(notificationId: Int) = NotificationManagerCompat.from(context).cancel(notificationId)

    private fun showIntent(medication: Medication, scheduleId: String, at: Instant, flags: Int, alarmId: String = scheduleId, profileName: String? = null, original: Instant = at): PendingIntent {
        val notificationId = alarmId.hashCode()
        return PendingIntent.getBroadcast(context, notificationId, reminderIntent(medication, scheduleId, at, alarmId, profileName, original), flags or PendingIntent.FLAG_IMMUTABLE)
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

    private fun reminderIntent(medication: Medication, scheduleId: String, at: Instant, alarmId: String, profileName: String? = null, original: Instant = at): Intent {
        val notificationId = alarmId.hashCode()
        return Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_SHOW
            data = Uri.parse("vezuna://reminder/${medication.medicationId}/$alarmId")
            putExtra(EXTRA_PROFILE_ID, medication.profileId)
            putExtra(EXTRA_MEDICATION_ID, medication.medicationId)
            putExtra(EXTRA_MEDICATION_NAME, medication.displayName)
            profileName?.let { putExtra(EXTRA_PROFILE_NAME, it) }
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(EXTRA_SCHEDULED_FOR, at.toEpochMilli())
            putExtra(EXTRA_ORIGINAL_SCHEDULED_FOR, original.toEpochMilli())
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
        private const val TAG = "VezunaReminders"
        const val CHANNEL_ID = "recorded-medication-reminders"
        const val ACTION_SHOW = "app.vezuna.action.SHOW_RECORDED_REMINDER"
        const val EXTRA_PROFILE_ID = "profileId"
        const val EXTRA_MEDICATION_ID = "medicationId"
        const val EXTRA_MEDICATION_NAME = "medicationName"
        const val EXTRA_PROFILE_NAME = "profileName"
        const val EXTRA_SCHEDULE_ID = "scheduleId"
        const val EXTRA_SCHEDULED_FOR = "scheduledFor"
        const val EXTRA_ORIGINAL_SCHEDULED_FOR = "originalScheduledFor"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
        const val ACTION_SUPPLY = "app.vezuna.action.LOW_SUPPLY"
        const val APPOINTMENT_CHANNEL_ID = "appointment-reminders"
        const val ACTION_APPOINTMENT = "app.vezuna.action.APPOINTMENT_REMINDER"
        const val EXTRA_APPOINTMENT_ID = "appointmentId"
        const val EXTRA_CLINICIAN = "clinician"
        const val EXTRA_APPOINTMENT_AT = "appointmentAt"
        private const val APPOINTMENT_PREFS = "recorded-appointment-armed-alarms"
        private const val SUPPLY_PREFS = "recorded-medication-supply-notices"
        private const val ARMED_PREFS = "recorded-medication-armed-alarms"
        private const val ARMED_KEY = "armed"
        const val SNOOZE_MS = 10 * 60 * 1000L

        fun requestCode(scheduleId: String?, at: Instant): Int = 31 * (scheduleId ?: "snooze").hashCode() + at.epochSecond.hashCode()
    }
}

enum class DoseAction(val status: DoseEventStatus?) {
    TAKEN(DoseEventStatus.TAKEN),
    SKIP(DoseEventStatus.SKIPPED),
    SNOOZE(null);

    companion object {
        /** Null for a missing or foreign action string, so a receiver never throws on an unexpected intent. */
        fun parse(name: String?): DoseAction? = entries.firstOrNull { it.name == name }
    }
}
