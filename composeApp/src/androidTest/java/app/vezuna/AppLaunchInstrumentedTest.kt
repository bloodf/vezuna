package app.vezuna

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.waitForStableInActiveWindow
import app.vezuna.ui.AppController
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLaunchInstrumentedTest {
    @Test
    fun controllerSurvivesConfigurationChange() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val scenario = ActivityScenario.launch(AndroidMainActivity::class.java)
        try {
            assertTrue("Dashboard did not initialize", device.wait(Until.hasObject(By.text("Today")), 15_000))
            lateinit var first: AppController
            scenario.onActivity { first = it.controller }

            scenario.recreate()

            lateinit var second: AppController
            scenario.onActivity { second = it.controller }
            assertSame("Controller was recreated during configuration change", first, second)
            println("ANDROID_SESSION_CONTROLLER_RETAINED_PASSED")
        } finally {
            scenario.close()
        }
    }

    @Test
    fun initializesHouseholdAndPersistsCreatedProfileAcrossRecreation() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val scenario = ActivityScenario.launch(AndroidMainActivity::class.java)
        try {
            assertTrue("Dashboard did not initialize", device.wait(Until.hasObject(By.text("Today")), 15_000))

            openManageProfiles(device)
            val editor = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
            editor.click()
            assertTrue("Profile editor did not receive focus", device.wait(Until.hasObject(By.focused(true)), 3_000))
            val inputMethod = device.executeShellCommand("dumpsys input_method")
            assertTrue("Full software keyboard was not shown", inputMethod.contains("mInputShown=true") || inputMethod.contains("mShowRequested=true"))
            val profileName = "Child ${System.currentTimeMillis()}"
            editor.text = profileName
            // Keep the keyboard open while bringing the submit button into the visible app viewport.
            device.findObject(By.pkg("app.vezuna").scrollable(true)).scroll(Direction.DOWN, 0.8f)
            device.tapText("Add profile")
            // Adding switches to the new profile and returns to Today, whose profile chip names it.
            assertTrue("Created profile did not become active", device.wait(Until.hasObject(By.descContains(profileName)), 5_000))

            scenario.recreate()
            assertTrue("Today did not restore", device.wait(Until.hasObject(By.text("Today")), 10_000))
            val restoredProfile = device.wait(Until.findObject(By.descContains(profileName)), 5_000)
            val evidenceDir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
            device.takeScreenshot(java.io.File(evidenceDir, "recreation.png"))
            device.dumpWindowHierarchy(java.io.File(evidenceDir, "recreation.xml"))
            assertTrue("Created profile did not survive recreation", restoredProfile != null)
            println("APP_LAUNCH_PROFILE_PERSISTENCE_PASSED")
        } finally {
            scenario.close()
        }
    }

    /** Today's profile chip opens the switcher sheet; its last row leads to More > Profiles. */
    private fun openManageProfiles(device: UiDevice) {
        device.wait(Until.findObject(By.descContains("Switch profile")), 5_000).click()
        // With many profiles the sheet scrolls, so the last row is below the fold.
        assertTrue("'Manage profiles' not reachable by scrolling", device.swipeToText("Manage profiles"))
        assertTrue("Profile switcher did not settle", !device.waitForStableInActiveWindow().isTimeout)
        device.tapText("Manage profiles")
        assertTrue("Profiles page did not open", device.wait(Until.hasObject(By.text("New profile name")), 5_000))
    }
}
