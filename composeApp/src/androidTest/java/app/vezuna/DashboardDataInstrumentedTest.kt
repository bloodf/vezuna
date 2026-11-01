package app.vezuna

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.DoseEvent
import app.vezuna.domain.DoseEventStatus
import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.LabObservation
import app.vezuna.domain.LabReport
import app.vezuna.domain.Medication
import app.vezuna.domain.OcrCandidate
import app.vezuna.domain.Profile
import app.vezuna.domain.Recurrence
import app.vezuna.domain.ReferenceRange
import app.vezuna.domain.SourceDocument
import app.vezuna.notifications.MedicationNotificationScheduler
import app.vezuna.notifications.NotificationScheduleLogic
import app.vezuna.notifications.ReminderReceiver
import app.vezuna.localization.formatUiDateTime
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardDataInstrumentedTest {
    @Test
    fun populatedHomeSupportsDoseActionsAndShowsLabContext() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as VezunaApplication
        val repository = app.repository
        val suffix = UUID.randomUUID().toString()
        val profile = Profile(
            "dashboard-profile-$suffix",
            "AAA Dashboard QA $suffix",
            createdAt = kotlinx.datetime.Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        ).also { repository.createProfile(it) }
        val profileId = profile.profileId
        val medicationId = "dashboard-medication-$suffix"
        val scheduleId = "dashboard-schedule-$suffix"
        val documentId = "dashboard-document-$suffix"
        val reportId = "dashboard-report-$suffix"
        val now = Instant.now()
        val nowKotlin = kotlinx.datetime.Instant.fromEpochMilliseconds(now.toEpochMilli())
        val doseLocal = now.plusSeconds(2 * 60 * 60).atZone(ZoneId.systemDefault())
        val schedule = DoseSchedule(
            profileId = profileId,
            scheduleId = scheduleId,
            medicationId = medicationId,
            recurrence = Recurrence.DAILY,
            startDate = LocalDate(doseLocal.year, doseLocal.monthValue, doseLocal.dayOfMonth),
            timesLocal = listOf("%02d:%02d".format(doseLocal.hour, doseLocal.minute)),
        )
        val medicationName = "Dashboard medication"
        repository.addMedication(
            Medication(
                profileId = profileId,
                medicationId = medicationId,
                displayName = medicationName,
                strength = "500 mg capsules",
                instructions = "Take with food and a full glass of water.",
                schedules = listOf(schedule),
            ),
        )
        listOf(DoseEventStatus.TAKEN, DoseEventStatus.SKIPPED).forEachIndexed { index, status ->
            val scheduledFor = nowKotlin.minus(index + 1, DateTimeUnit.DAY, kotlinx.datetime.TimeZone.UTC)
            repository.addDoseEvent(
                DoseEvent(
                    profileId = profileId,
                    eventId = "dashboard-history-$index-$suffix",
                    medicationId = medicationId,
                    scheduleId = scheduleId,
                    scheduledFor = scheduledFor,
                    status = status,
                    recordedAt = scheduledFor,
                ),
            )
        }
        repository.addDocument(SourceDocument(profileId, documentId, "Dashboard QA.pdf", "application/pdf", suffix, nowKotlin))
        repository.addReport(LabReport(profileId, reportId, documentId, importedAt = nowKotlin))
        listOf(
            Triple(nowKotlin.minus(20, DateTimeUnit.DAY, kotlinx.datetime.TimeZone.UTC), 11.8, "dashboard-old-$suffix"),
            Triple(nowKotlin.minus(1, DateTimeUnit.DAY, kotlinx.datetime.TimeZone.UTC), 13.2, "dashboard-current-$suffix"),
        ).forEach { (collectedAt, value, observationId) ->
            repository.addObservation(
                LabObservation(
                    profileId = profileId,
                    observationId = observationId,
                    reportId = reportId,
                    testCode = "HGB",
                    displayName = "Hemoglobin",
                    originalValue = value,
                    originalUnit = "g/dL",
                    normalizedValue = value,
                    normalizedUnit = "g/dL",
                    referenceRange = ReferenceRange(12.0, 16.0, unit = "g/dL"),
                    collectedAt = collectedAt,
                    confirmation = ConfirmationStatus.CONFIRMED,
                ),
            )
        }
        repository.addCandidate(
            OcrCandidate(
                profileId = profileId,
                candidateId = "dashboard-candidate-$suffix",
                sourceDocumentId = documentId,
                testCode = "HCT",
                displayName = "Hematocrit",
                rawValue = "41",
                rawUnit = "%",
                collectedAt = nowKotlin,
            ),
        )
        val emptyName = "AAA AAA Empty"

        val device = UiDevice.getInstance(instrumentation)
        val scenario = ActivityScenario.launch(AndroidMainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                runBlocking { activity.controller.switchProfile(profileId) }
            }
            assertTrue(device.wait(Until.hasObject(By.textContains("Dashboard QA")), 15_000))
            assertTrue(device.wait(Until.hasObject(By.text(medicationName)), 15_000))
            val directory = context.getExternalFilesDir(null)!!
            device.takeScreenshot(java.io.File(directory, "dashboard-populated.png"))

            clickText(device, "Snooze 10 min")
            await("snoozed dose event") {
                repository.readProfile(profileId).doseEvents.any {
                    it.scheduleId == scheduleId && it.status == DoseEventStatus.SCHEDULED && it.replacesScheduledFor != null
                }
            }
            val snoozed = repository.readProfile(profileId).doseEvents.single {
                it.scheduleId == scheduleId && it.status == DoseEventStatus.SCHEDULED && it.replacesScheduledFor != null
            }
            assertTrue(snoozed.scheduledFor.epochSeconds - snoozed.recordedAt!!.epochSeconds == 600L)
            await("real snooze alarm") { findAlarm(context, medicationId, scheduleId, snoozed.scheduledFor.epochSeconds) != null }

            clickText(device, "Taken")
            await("taken snoozed dose") {
                repository.readProfile(profileId).doseEvents.any {
                    it.scheduleId == scheduleId && it.scheduledFor == snoozed.scheduledFor && it.status == DoseEventStatus.TAKEN
                }
            }
            await("snooze alarm cancellation") { findAlarm(context, medicationId, scheduleId, snoozed.scheduledFor.epochSeconds) == null }
            val nextDose = NotificationScheduleLogic.nextOccurrences(
                schedule,
                Instant.ofEpochSecond(snoozed.replacesScheduledFor!!.epochSeconds + 1),
                ZoneId.systemDefault(),
            ).single().toInstant()
            val nextDoseLabel = formatUiDateTime(
                kotlinx.datetime.Instant.fromEpochMilliseconds(nextDose.toEpochMilli()),
            )
            assertTrue("Home did not advance to the next dose", scrollTo(device, nextDoseLabel))
            assertTrue(device.wait(Until.hasObject(By.text(nextDoseLabel)), 5_000))

            clickText(device, "Skip")
            await("skipped next dose") {
                repository.readProfile(profileId).doseEvents.any { it.scheduleId == scheduleId && it.status == DoseEventStatus.SKIPPED }
            }
            scrollTo(device, "30-day adherence")
            assertTrue(device.wait(Until.hasObject(By.textContains("50%")), 5_000))
            scrollTo(device, "Recent lab results")
            repeat(3) {
                if (device.hasObject(By.text("Hemoglobin"))) return@repeat
                device.swipe(device.displayWidth / 2, (device.displayHeight * 0.62).toInt(), device.displayWidth / 2, (device.displayHeight * 0.42).toInt(), 100)
                device.waitForIdle()
            }
            device.takeScreenshot(java.io.File(directory, "dashboard-labs.png"))
            assertTrue(device.wait(Until.hasObject(By.text("Hemoglobin")), 3_000))
            assertTrue("Reference row not found", swipeTo(device, "Reference:"))
            assertTrue(device.wait(Until.hasObject(By.textContains("Reference:")), 3_000))
            assertTrue("Trend action not found", swipeTo(device, "Increase"))
            assertTrue(device.wait(Until.hasObject(By.textContains("Increase")), 3_000))
            assertTrue("Review notice not found", swipeTo(device, "1 candidate needs your confirmation"))
            assertTrue(device.wait(Until.hasObject(By.textContains("1 candidate needs your confirmation")), 3_000))
            device.takeScreenshot(java.io.File(directory, "dashboard-labs.png"))

            repository.createProfile(Profile("dashboard-empty-$suffix", emptyName, createdAt = nowKotlin))
            scenario.close()
            val emptyScenario = ActivityScenario.launch(AndroidMainActivity::class.java)
            try {
                assertTrue("Empty profile did not become active", device.wait(Until.hasObject(By.text(emptyName)), 10_000))
            } finally {
                emptyScenario.close()
            }
            val emptyData = repository.readProfile("dashboard-empty-$suffix")
            assertTrue(emptyData.medications.isEmpty())
            assertTrue(emptyData.observations.isEmpty())
            assertTrue(emptyData.ocrCandidates.isEmpty())
            println("DASHBOARD_POPULATED_ACTIONS_ALARMS_AND_PROFILE_ISOLATION_PASSED")
        } finally {
            scenario.close()
        }
    }

    /** Swipes down from the current position; UiScrollable can pick a non-page scrollable and miss lower rows. */
    private fun swipeTo(device: UiDevice, text: String): Boolean {
        repeat(8) {
            if (device.hasObject(By.textContains(text))) return true
            device.swipe(device.displayWidth / 2, (device.displayHeight * 0.7).toInt(), device.displayWidth / 2, (device.displayHeight * 0.4).toInt(), 100)
            device.waitForIdle()
        }
        return device.hasObject(By.textContains(text))
    }

    private fun scrollTo(device: UiDevice, text: String): Boolean {
        val target = By.textContains(text)
        if (device.hasObject(target)) return true
        val scroller = UiScrollable(UiSelector().scrollable(true))
        scroller.scrollToBeginning(30)
        if (device.wait(Until.hasObject(target), 500)) return true
        repeat(30) {
            if (!scroller.scrollForward()) return device.hasObject(target)
            if (device.wait(Until.hasObject(target), 250)) return true
        }
        return device.hasObject(target)
    }

    private fun clickText(device: UiDevice, text: String) {
        if (text == "Manage profiles" && !device.hasObject(By.text(text))) {
            repeat(12) {
                device.swipe(device.displayWidth / 2, (device.displayHeight * 0.25).toInt(), device.displayWidth / 2, (device.displayHeight * 0.82).toInt(), 12)
                device.waitForIdle()
            }
        }
        repeat(12) {
            val target = device.findObject(By.textContains(text))
            if (target != null) {
                device.click(target.visibleCenter.x, target.visibleCenter.y)
                return
            }
            device.drag(device.displayWidth / 2, (device.displayHeight * 0.65).toInt(), device.displayWidth / 2, (device.displayHeight * 0.55).toInt(), 50)
            device.waitForIdle()
        }
        repeat(12) {
            val target = device.findObject(By.textContains(text))
            if (target != null) {
                device.click(target.visibleCenter.x, target.visibleCenter.y)
                return
            }
            device.drag(device.displayWidth / 2, (device.displayHeight * 0.35).toInt(), device.displayWidth / 2, (device.displayHeight * 0.45).toInt(), 50)
            device.waitForIdle()
        }
        val target = device.findObject(By.textContains(text)) ?: run {
            device.executeShellCommand("screencap -p /sdcard/dashboard-action-failure.png")
            throw AssertionError("Control $text not found")
        }
        device.click(target.visibleCenter.x, target.visibleCenter.y)
    }

    private suspend fun await(message: String, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() >= deadline) throw AssertionError("Timed out waiting for $message")
            Thread.sleep(100)
        }
    }

    private fun findAlarm(context: android.content.Context, medicationId: String, scheduleId: String, epochSeconds: Long): PendingIntent? {
        val alarmId = "$scheduleId:$epochSeconds"
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = MedicationNotificationScheduler.ACTION_SHOW
            data = Uri.parse("vezuna://reminder/$medicationId/$alarmId")
        }
        return PendingIntent.getBroadcast(
            context,
            alarmId.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
