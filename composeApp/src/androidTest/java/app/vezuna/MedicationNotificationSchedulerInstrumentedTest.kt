package app.vezuna

import app.vezuna.notifications.DoseActionReceiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.vezuna.domain.DoseEvent
import app.vezuna.domain.DoseEventStatus
import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.Medication
import app.vezuna.domain.Profile
import app.vezuna.domain.Recurrence
import app.vezuna.notifications.MedicationNotificationScheduler
import app.vezuna.notifications.NotificationScheduleLogic
import app.vezuna.notifications.ReminderReceiver
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MedicationNotificationSchedulerInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as VezunaApplication

    @Test
    fun takenCancelsScheduledAlarm() = terminalStatusCancelsScheduledAlarm(DoseEventStatus.TAKEN)

    @Test
    fun skippedCancelsScheduledAlarm() = terminalStatusCancelsScheduledAlarm(DoseEventStatus.SKIPPED)

    @Test
    fun laterSnoozeCancelsOriginalAndEarlierSnoozeAlarms() = runBlocking {
        val fixture = createFixture()
        val firstAt = fixture.baseAt.plusSeconds(600)
        val secondAt = fixture.baseAt.plusSeconds(1200)
        try {
            app.repository.addDoseEvent(fixture.event(firstAt, DoseEventStatus.SCHEDULED, fixture.baseAt))
            app.notifications.rescheduleAll(app.repository, fixture.now)
            assertNull(findAlarm(fixture, fixture.baseAt))
            assertNotNull(findAlarm(fixture, firstAt))

            app.repository.addDoseEvent(fixture.event(secondAt, DoseEventStatus.SCHEDULED, firstAt))
            app.notifications.rescheduleAll(app.repository, fixture.now)
            assertNull(findAlarm(fixture, fixture.baseAt))
            assertNull(findAlarm(fixture, firstAt))
            assertNotNull(findAlarm(fixture, secondAt))
        } finally {
            cancelAlarm(fixture, fixture.baseAt)
            cancelAlarm(fixture, firstAt)
            cancelAlarm(fixture, secondAt)
        }
    }

    @Test
    fun notificationSnoozeCreatesFutureReminderReceiverAlarm() {
        val scheduleId = "notification-snooze-${UUID.randomUUID()}"
        val original = Intent(context, DoseActionReceiver::class.java).apply {
            action = "SNOOZE"
            putExtra(MedicationNotificationScheduler.EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(MedicationNotificationScheduler.EXTRA_SCHEDULED_FOR, Instant.now().toEpochMilli())
        }
        var pending: PendingIntent? = null
        try {
            val before = Instant.now()
            app.notifications.snooze(original)
            val after = Instant.now()
            for (atMillis in before.toEpochMilli() + MedicationNotificationScheduler.SNOOZE_MS..after.toEpochMilli() + MedicationNotificationScheduler.SNOOZE_MS) {
                val at = Instant.ofEpochMilli(atMillis)
                val intent = Intent(context, ReminderReceiver::class.java).apply {
                    action = MedicationNotificationScheduler.ACTION_SHOW
                    data = Uri.parse("vezuna://reminder/snooze/$atMillis/$scheduleId")
                }
                pending = PendingIntent.getBroadcast(
                    context,
                    MedicationNotificationScheduler.requestCode(scheduleId, at),
                    intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                )
                if (pending != null) break
            }
            assertNotNull(pending)
        } finally {
            pending?.let {
                context.getSystemService(AlarmManager::class.java).cancel(it)
                it.cancel()
            }
        }
    }

    private fun terminalStatusCancelsScheduledAlarm(status: DoseEventStatus) = runBlocking {
        val fixture = createFixture()
        try {
            app.notifications.rescheduleAll(app.repository, fixture.now)
            assertNotNull(findAlarm(fixture, fixture.baseAt))

            app.repository.addDoseEvent(fixture.event(fixture.baseAt, status))
            app.notifications.rescheduleAll(app.repository, fixture.now)
            assertNull(findAlarm(fixture, fixture.baseAt))
        } finally {
            cancelAlarm(fixture, fixture.baseAt)
        }
    }

    private suspend fun createFixture(): Fixture {
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val zone = ZoneId.systemDefault()
        val doseLocal = now.plusSeconds(2 * 60 * 60).atZone(zone)
        val profileId = "alarm-profile-${UUID.randomUUID()}"
        val medicationId = "alarm-medication-${UUID.randomUUID()}"
        val scheduleId = "alarm-schedule-${UUID.randomUUID()}"
        val schedule = DoseSchedule(
            profileId = profileId,
            scheduleId = scheduleId,
            medicationId = medicationId,
            recurrence = Recurrence.DAILY,
            startDate = LocalDate(doseLocal.year, doseLocal.monthValue, doseLocal.dayOfMonth),
            timesLocal = listOf("%02d:%02d".format(doseLocal.hour, doseLocal.minute)),
        )
        val medication = Medication(profileId, medicationId, "Alarm regression", schedules = listOf(schedule))
        app.repository.createProfile(Profile(profileId, "Alarm regression", createdAt = kotlinx.datetime.Instant.fromEpochMilliseconds(now.toEpochMilli())))
        app.repository.addMedication(medication)
        val baseAt = NotificationScheduleLogic.nextOccurrences(schedule, now, zone).single().toInstant()
        return Fixture(profileId, medicationId, scheduleId, now, baseAt)
    }

    private fun findAlarm(fixture: Fixture, at: Instant): PendingIntent? {
        val alarmId = "${fixture.scheduleId}:${at.epochSecond}"
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = MedicationNotificationScheduler.ACTION_SHOW
            data = Uri.parse("vezuna://reminder/${fixture.medicationId}/$alarmId")
        }
        return PendingIntent.getBroadcast(
            context,
            alarmId.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelAlarm(fixture: Fixture, at: Instant) {
        val pending = findAlarm(fixture, at) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pending)
        pending.cancel()
    }

    private data class Fixture(
        val profileId: String,
        val medicationId: String,
        val scheduleId: String,
        val now: Instant,
        val baseAt: Instant,
    ) {
        fun event(at: Instant, status: DoseEventStatus, replaces: Instant? = null) = DoseEvent(
            profileId = profileId,
            eventId = UUID.randomUUID().toString(),
            medicationId = medicationId,
            scheduleId = scheduleId,
            scheduledFor = kotlinx.datetime.Instant.fromEpochMilliseconds(at.toEpochMilli()),
            status = status,
            recordedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(now.toEpochMilli()),
            replacesScheduledFor = replaces?.let { kotlinx.datetime.Instant.fromEpochMilliseconds(it.toEpochMilli()) },
        )
    }
}
