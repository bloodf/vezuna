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
import org.junit.Assert.fail
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
            assertTrue(device.awaitToday())
            assertTrue("Profile switcher is not accessible", device.hasObject(By.descContains("Switch profile")))
            capture(device, directory, "ui-home.png", "Today")
            navigate(device, directory, "Records", "ui-labs.png")
            navigate(device, directory, "Care", "ui-medications.png", marker = "Medication schedule")
            navigate(device, directory, "More", "ui-more.png", marker = "Settings and preferences")
            clickText(device, "Assistant and local models")
            assertTrue(device.wait(Until.hasObject(By.text("On-device AI")), 5_000))
            capture(device, directory, "ui-ai.png", "On-device AI")
            device.pressBack()
            clickText(device, "Exports")
            assertTrue(device.wait(Until.hasObject(By.text("Create export")), 5_000))
            capture(device, directory, "ui-settings.png", "Create export")

            device.executeShellCommand("cmd uimode night yes")
            // The session is retained across the recreation, so the export page is still selected.
            device.pressBack()
            clickText(device, "Today")
            assertTrue(device.awaitToday())
            capture(device, directory, "ui-dark.png", "Today")
            device.executeShellCommand("cmd uimode night no")

            device.executeShellCommand("settings put system font_scale 1.3")
            assertTrue(device.awaitToday())
            capture(device, directory, "ui-large-text.png", "Today")

            device.executeShellCommand("settings put system font_scale 1.0")
            device.executeShellCommand("settings put global animator_duration_scale 0")
            // The font-scale change recreates the activity on its own; let that finish before recreating by hand.
            assertTrue(device.awaitToday())
            device.waitForIdle()
            scenario.recreate()
            navigate(device, directory, "Records", "ui-reduced-motion.png")
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
            "visual-$suffix", "AAA Visual synthetic", createdAt = kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis()),
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
            assertTrue(device.hasObject(By.descContains("Switch profile")))
            capture(device, directory, "vezuna-light-long-medication.png", "Light long medication")
            device.executeShellCommand("cmd uimode night yes")
            device.waitForIdle()
            selectVisualProfile(device, scenario, profile.profileId)
            assertTrue(device.wait(Until.hasObject(By.textContains("Long-acting combination")), 10_000))
            capture(device, directory, "vezuna-dark-long-medication.png", "Dark long medication")
            device.executeShellCommand("cmd uimode night no")
            selectVisualProfile(device, scenario, profile.profileId)
            clickText(device, "Care")
            device.executeShellCommand("settings put system font_scale 1.3")
            device.waitForIdle()
            selectVisualProfile(device, scenario, profile.profileId)
            clickText(device, "Care")
            assertTrue(device.wait(Until.hasObject(By.text("Medication schedule")), 10_000))
            capture(device, directory, "vezuna-large-text-medications.png", "Large text medications")
            device.executeShellCommand("settings put system font_scale 1.0")
            selectVisualProfile(device, scenario, profile.profileId)
            clickText(device, "Care")
            assertTrue(device.wait(Until.hasObject(By.text("Medication schedule")), 10_000))
            scenario.onActivity { activity ->
                runBlocking { activity.controller.addMedication("Error fixture", "", "", "99:99") }
            }
            assertTrue(device.wait(Until.hasObject(By.textContains("valid reminder time")), 5_000))
            capture(device, directory, "vezuna-error.png", "Invalid reminder time")

            clickText(device, "More")
            clickText(device, "Privacy and app lock")
            assertTrue(device.wait(Until.hasObject(By.text("Your data")), 5_000))
            capture(device, directory, "vezuna-offline-settings.png", "Offline privacy")

            device.executeShellCommand("cmd locale set-app-locales ${context.packageName} --user 0 --locales ar-EG")
            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue("Android did not enter RTL layout", activity.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL)
            }
            selectVisualProfile(device, scenario, profile.profileId)
            assertTrue(device.wait(Until.hasObject(By.textContains("Long-acting combination")), 10_000))
            assertTrue("Profile switcher missing in RTL", device.hasObject(By.descContains("Switch profile")))
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
        // The session survives recreation, so return to the dashboard where the profile name is shown.
        scenario.onActivity { activity -> activity.controller.navigate(app.vezuna.ui.AppScreen.Dashboard) }
        scenario.onActivity { activity ->
            runBlocking { activity.controller.switchProfile(profileId) }
        }
        assertTrue(device.wait(Until.hasObject(By.descContains("AAA Visual synthetic")), 10_000))
        device.waitForIdle()
    }

    /** Navigation items and list rows are found by their visible label; the lowest match is the control, not a heading. */
    private fun clickText(device: UiDevice, text: String) {
        val selector = By.text(text)
        assertTrue("Control $text did not appear", device.wait(Until.hasObject(selector), 10_000))
        repeat(5) {
            device.findObjects(selector).lastOrNull()?.let { control -> control.click(); return }
            device.waitForIdle()
            device.wait(Until.hasObject(selector), 2_000)
        }
        fail("Control $text disappeared before it could be clicked")
    }

    /** The tab label is also the page heading, so a page without its own marker shows the label twice. */
    private fun navigate(device: UiDevice, directory: File, tab: String, fileName: String, marker: String = tab) {
        clickText(device, tab)
        val needed = if (marker == tab) 2 else 1
        val deadline = System.currentTimeMillis() + 5_000
        while (device.findObjects(By.text(marker)).size < needed && System.currentTimeMillis() < deadline) device.waitForIdle()
        assertTrue("Destination $marker did not appear", device.findObjects(By.text(marker)).size >= needed)
        capture(device, directory, fileName, marker)
    }

    private fun capture(device: UiDevice, directory: File, fileName: String, title: String) {
        Thread.sleep(450)
        device.executeShellCommand("screencap -p /sdcard/$fileName")
        println("VISUAL_CAPTURE $fileName $title")
    }
}
