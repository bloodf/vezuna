package app.vezuna

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabObservation
import app.vezuna.domain.LabReport
import app.vezuna.domain.Profile
import app.vezuna.domain.ReferenceRange
import app.vezuna.domain.SourceDocument
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppExportInstrumentedTest {
    @Test
    fun preparesPdfAndCsvThroughUiWithoutModelOrLicense() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = (instrumentation.targetContext.applicationContext as VezunaApplication).repository
        val device = UiDevice.getInstance(instrumentation)
        val suffix = UUID.randomUUID().toString()
        val now = kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val profile = Profile("export-$suffix", "AAA Export QA", createdAt = now).also { repository.createProfile(it) }
        repository.addDocument(SourceDocument(profile.profileId, "export-doc-$suffix", "Export QA.pdf", "application/pdf", suffix, now))
        repository.addReport(LabReport(profile.profileId, "export-report-$suffix", "export-doc-$suffix", importedAt = now))
        repository.addObservation(
            LabObservation(
                profileId = profile.profileId,
                observationId = "export-obs-$suffix",
                reportId = "export-report-$suffix",
                testCode = "HGB",
                displayName = "Hemoglobin",
                originalValue = 13.2,
                originalUnit = "g/dL",
                normalizedValue = 13.2,
                normalizedUnit = "g/dL",
                referenceRange = ReferenceRange(12.0, 16.0, unit = "g/dL"),
                collectedAt = now,
                confirmation = ConfirmationStatus.CONFIRMED,
            ),
        )
        val scenario = ActivityScenario.launch(AndroidMainActivity::class.java)
        try {
            assertTrue(device.awaitToday())
            scenario.onActivity { activity -> runBlocking { activity.controller.switchProfile(profile.profileId) } }

            openExportOptions(device, "Lab history")
            device.scrollAndTapText("Preview export")
            assertTrue("PDF export did not become ready", device.awaitText("Export ready"))
            assertTrue(device.wait(Until.hasObject(By.textContains("-lab-history-")), 3_000))
            backToHub(device)

            openExportOptions(device, CSV_ROW)
            device.scrollAndTapText("Preview CSV")
            assertTrue("CSV export did not become ready", device.awaitText("CSV preview"))
            assertTrue(device.wait(Until.hasObject(By.textContains("-lab-results-")), 3_000))
            println("APP_UI_EXPORT_WITHOUT_LICENSE_PASSED")
        } finally {
            scenario.close()
        }
    }

    private companion object {
        const val CSV_ROW = "Export labs as CSV"
    }

    private fun backToHub(device: UiDevice) {
        repeat(5) {
            if (device.hasObject(By.text("Create export"))) return
            device.pressBack()
            device.waitForIdle()
        }
        assertTrue("Export hub not reachable", device.hasObject(By.text("Create export")))
    }

    private fun openExportOptions(device: UiDevice, layout: String) {
        if (!device.hasObject(By.text("Create export"))) {
            device.tapText("More")
            device.tapText("Exports")
        }
        device.scrollAndTapText(layout)
        // The CSV row opens its options directly; layout rows are radios confirmed with Continue.
        if (layout != CSV_ROW) device.scrollAndTapText("Continue")
    }
}
