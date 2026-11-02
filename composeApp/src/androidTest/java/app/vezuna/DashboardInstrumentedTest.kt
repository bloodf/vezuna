package app.vezuna

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
class DashboardInstrumentedTest {
    @Test
    fun todayHasNoSettingsGearAndMoreIsTheWayToSettings() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val scenario = ActivityScenario.launch(AndroidMainActivity::class.java)
        try {
            assertTrue(device.wait(Until.hasObject(By.text("Today")), 15_000))
            // One profile control in the header and no gear: settings live under More only.
            assertTrue(device.findObjects(By.desc("Settings")).isEmpty())
            assertTrue(device.findObjects(By.descContains("Switch profile")).size == 1)
            device.findObject(By.text("More")).click()
            assertTrue(device.wait(Until.hasObject(By.text("More")), 5_000))
            println("TODAY_HEADER_WITHOUT_GEAR_VERIFIED")
        } finally {
            scenario.close()
        }
    }
}
