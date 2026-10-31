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
class DashboardInstrumentedTest {
    @Test
    fun settingsGearOpensSettingsAndTodayIsVisible() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val scenario = ActivityScenario.launch(AndroidMainActivity::class.java)
        try {
            assertTrue(device.wait(Until.hasObject(By.textContains("Household")), 15_000))
            assertTrue(device.wait(Until.hasObject(By.text("Today")), 5_000))
            val settingsButtons = device.findObjects(By.desc("Settings"))
            assertTrue(settingsButtons.isNotEmpty())
            settingsButtons.first().click()
            assertTrue(device.wait(Until.hasObject(By.text("Settings & export")), 5_000))
            println("DASHBOARD_GEAR_NAVIGATION_VERIFIED")
        } finally {
            scenario.close()
        }
    }
}
