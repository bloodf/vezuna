package app.vezuna

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.Medication
import app.vezuna.domain.Profile
import app.vezuna.domain.Recurrence
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppVisualInstrumentedTest {
    @Test
    fun capturesMainDestinationsDarkThemeAndLargeText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val directory = instrumentation.targetContext.getExternalFilesDir(null)!!
        val scenario = ActivityScenario.launch(AndroidMainActivity::class.java)
        try {
            assertTrue(device.wait(Until.hasObject(By.textContains("Household")), 15_000))
            assertTrue("Vezuna lockup is not accessible", device.hasObject(By.desc("Vezuna")))
            capture(device, directory, "ui-home.png", "Household")
            navigate(device, directory, "Labs", "Lab records", "ui-labs.png")
            navigate(device, directory, "Meds", "Medications", "ui-medications.png")
            navigate(device, directory, "AI", "On-device AI", "ui-ai.png")
            navigate(device, directory, "Settings", "Settings & export", "ui-settings.png")

            device.executeShellCommand("cmd uimode night yes")
            assertTrue(device.wait(Until.hasObject(By.textContains("Household")), 10_000))
            capture(device, directory, "ui-dark.png", "Household")
            device.executeShellCommand("cmd uimode night no")

            device.executeShellCommand("settings put system font_scale 1.3")
            assertTrue(device.wait(Until.hasObject(By.textContains("Household")), 10_000))
            capture(device, directory, "ui-large-text.png", "Household")

            device.executeShellCommand("settings put system font_scale 1.0")
            device.executeShellCommand("settings put global animator_duration_scale 0")
            scenario.recreate()
            navigate(device, directory, "Labs", "Lab records", "ui-reduced-motion.png")
            println("APP_VISUAL_STATES_CAPTURED")
        } finally {
            device.executeShellCommand("cmd uimode night no")
            device.executeShellCommand("settings put system font_scale 1.0")
            device.executeShellCommand("settings put global animator_duration_scale 1")
            scenario.close()
        }
    }

    @Test
    fun syntheticLongMedicationErrorOfflineAndRtlStatesAreUsable() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as VezunaApplication
        val device = UiDevice.getInstance(instrumentation)
        val directory = context.getExternalFilesDir(null)!!
        val suffix = UUID.randomUUID().toString()
        val profile = Profile(
            "visual-$suffix", "AAA Visual synthetic", createdAt = kotlinx.datetime.Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        ).also { app.repository.createProfile(it) }
        val now = Instant.now().plusSeconds(2 * 60 * 60).atZone(ZoneId.systemDefault())
        val longName = "Long-acting combination medication with a very long synthetic name for enlarged text review"
        app.repository.addMedication(
            Medication(
                profileId = profile.profileId,
                medicationId = "visual-medication-$suffix",
                displayName = longName,
                strength = "500 mg",
                schedules = listOf(
                    DoseSchedule(
                        profileId = profile.profileId,
                        scheduleId = "visual-schedule-$suffix",
                        medicationId = "visual-medication-$suffix",
                        recurrence = Recurrence.DAILY,
                        startDate = LocalDate(now.year, now.monthValue, now.dayOfMonth),
                        timesLocal = listOf("%02d:%02d".format(now.hour, now.minute)),
                    ),
                ),
            ),
        )
        val scenario = ActivityScenario.launch(AndroidMainActivity::class.java)
        try {
            selectVisualProfile(device, scenario, profile.profileId)
            assertTrue(device.wait(Until.hasObject(By.textContains("Long-acting combination")), 15_000))
            assertTrue(device.hasObject(By.desc("Vezuna")))
            capture(device, directory, "vezuna-light-long-medication.png", "Light long medication")
            device.executeShellCommand("cmd uimode night yes")
            device.waitForIdle()
            selectVisualProfile(device, scenario, profile.profileId)
            assertTrue(device.wait(Until.hasObject(By.textContains("Long-acting combination")), 10_000))
            capture(device, directory, "vezuna-dark-long-medication.png", "Dark long medication")
            device.executeShellCommand("cmd uimode night no")
            selectVisualProfile(device, scenario, profile.profileId)
            clickDescription(device, "Meds")
            device.executeShellCommand("settings put system font_scale 1.3")
            device.waitForIdle()
            selectVisualProfile(device, scenario, profile.profileId)
            clickDescription(device, "Meds")
            assertTrue(device.wait(Until.hasObject(By.text("Medications")), 10_000))
            capture(device, directory, "vezuna-large-text-medications.png", "Large text medications")
            device.executeShellCommand("settings put system font_scale 1.0")
            selectVisualProfile(device, scenario, profile.profileId)
            clickDescription(device, "Meds")
            assertTrue(device.wait(Until.hasObject(By.text("Medications")), 10_000))
            scenario.onActivity { activity ->
                runBlocking { activity.controller.addMedication("Error fixture", "", "", "99:99") }
            }
            assertTrue(device.wait(Until.hasObject(By.textContains("valid reminder time")), 5_000))
            capture(device, directory, "vezuna-error.png", "Invalid reminder time")

            clickDescription(device, "Settings")
            assertTrue(device.wait(Until.hasObject(By.text("No Vezuna app server")), 5_000))
            capture(device, directory, "vezuna-offline-settings.png", "Offline settings")

            device.executeShellCommand("cmd locale set-app-locales ${context.packageName} --user 0 --locales ar-EG")
            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue("Android did not enter RTL layout", activity.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL)
            }
            clickDescription(device, "Home")
            selectVisualProfile(device, scenario, profile.profileId)
            assertTrue(device.wait(Until.hasObject(By.textContains("Long-acting combination")), 10_000))
            assertTrue("Vezuna lockup missing in RTL", device.hasObject(By.desc("Vezuna")))
            capture(device, directory, "vezuna-rtl.png", "RTL")
            println("VEZUNA_VISUAL_STATES_CAPTURED")
        } finally {
            device.executeShellCommand("cmd uimode night no")
            device.executeShellCommand("settings put system font_scale 1.0")
            device.executeShellCommand("cmd locale set-app-locales ${context.packageName} --user 0 --locales en-US")
            scenario.close()
        }
    }

    private fun selectVisualProfile(device: UiDevice, scenario: ActivityScenario<AndroidMainActivity>, profileId: String) {
        scenario.onActivity { activity ->
            runBlocking { activity.controller.switchProfile(profileId) }
        }
        assertTrue(device.wait(Until.hasObject(By.textContains("AAA Visual synthetic")), 10_000))
        device.waitForIdle()
    }

    private fun clickDescription(device: UiDevice, description: String) {
        assertTrue("Control $description did not appear", device.wait(Until.hasObject(By.desc(description)), 10_000))
        device.findObject(By.desc(description)).click()
    }

    private fun navigate(device: UiDevice, directory: File, tab: String, title: String, fileName: String) {
        clickDescription(device, tab)
        assertTrue("Destination $title did not appear", device.wait(Until.hasObject(By.text(title)), 5_000))
        capture(device, directory, fileName, title)
    }

    private fun capture(device: UiDevice, directory: File, fileName: String, title: String) {
        Thread.sleep(450)
        device.executeShellCommand("screencap -p /sdcard/$fileName")
        println("VISUAL_CAPTURE $fileName $title")
    }
}
