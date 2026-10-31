package com.bloodf.vitahearth

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppExportInstrumentedTest {
    @Test
    fun preparesPdfAndCsvThroughUiWithoutModelOrLicense() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val scenario = ActivityScenario.launch(AndroidMainActivity::class.java)
        try {
            assertTrue(device.wait(Until.hasObject(By.textContains("Household")), 15_000))
            device.findObject(By.desc("Settings")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Settings & export")), 5_000))

            device.findObject(By.text("Prepare PDF")).click()
            assertTrue("PDF export dialog did not appear", device.wait(Until.hasObject(By.text("Export ready")), 5_000))
            assertTrue(device.wait(Until.hasObject(By.textContains("vitahearth-doctor-export.pdf")), 3_000))
            device.findObject(By.text("Close")).click()

            device.findObject(By.text("Prepare CSV")).click()
            assertTrue("CSV export dialog did not appear", device.wait(Until.hasObject(By.text("Export ready")), 5_000))
            assertTrue(device.wait(Until.hasObject(By.textContains("vitahearth-doctor-export.csv")), 3_000))
            println("APP_UI_EXPORT_WITHOUT_LICENSE_PASSED")
        } finally {
            scenario.close()
        }
    }
}
