package app.vezuna

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThirdPartyNoticesInstrumentedTest {
    @Test
    fun opensPackagedNoticesFromDeviceMenuAndReturnsToRecords() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("cmd locale set-app-locales ${context.packageName} --user 0 --locales en-US")
        ActivityScenario.launch(AndroidMainActivity::class.java).use {
            assertTrue(device.wait(Until.hasObject(By.textContains("Household")), 15_000))
            device.findObject(By.text("Device")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Third-party notices")), 5_000))
            device.findObject(By.text("Third-party notices")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Vezuna third-party notices")), 10_000))
            assertTrue(device.hasObject(By.textContains("Vezuna source and original artwork")))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "third-party-notices.png"))
            device.findObject(By.text("Close")).click()
            assertTrue(device.wait(Until.hasObject(By.textContains("Household")), 5_000))
        }
        println("THIRD_PARTY_NOTICES_NAVIGATION_PASSED")
    }
}
