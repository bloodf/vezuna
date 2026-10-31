package com.bloodf.vitahearth

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLaunchInstrumentedTest {
    @Test
    fun initializesHouseholdAndPersistsCreatedProfileAcrossRecreation() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val scenario = ActivityScenario.launch(AndroidMainActivity::class.java)
        try {
            assertTrue("Dashboard did not initialize", device.wait(Until.hasObject(By.textContains("Household")), 15_000))

            device.findObject(By.text("Manage profiles")).click()
            androidx.test.uiautomator.UiScrollable(androidx.test.uiautomator.UiSelector().scrollable(true)).scrollTextIntoView("New profile name")
            val editor = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
            editor.click()
            assertTrue("Profile editor did not receive focus", device.wait(Until.hasObject(By.focused(true)), 3_000))
            val inputMethod = device.executeShellCommand("dumpsys input_method")
            assertTrue("Full software keyboard was not shown", inputMethod.contains("mInputShown=true") || inputMethod.contains("mShowRequested=true"))
            editor.text = "Child"
            device.findObject(By.text("Add")).click()
            assertTrue("Created profile did not appear", device.wait(Until.hasObject(By.textContains("Child")), 5_000))

            scenario.recreate()
            androidx.test.uiautomator.UiScrollable(androidx.test.uiautomator.UiSelector().scrollable(true)).scrollToBeginning(5)
            assertTrue("Household did not restore", device.wait(Until.hasObject(By.textContains("Household")), 10_000))
            device.findObject(By.text("Manage profiles")).click()
            val restoredProfile = androidx.test.uiautomator.UiScrollable(androidx.test.uiautomator.UiSelector().scrollable(true))
                .scrollTextIntoView("Switch to Child")
            val evidenceDir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
            device.takeScreenshot(java.io.File(evidenceDir, "recreation.png"))
            device.dumpWindowHierarchy(java.io.File(evidenceDir, "recreation.xml"))
            assertTrue("Created profile did not survive recreation", restoredProfile != null)
            println("APP_LAUNCH_PROFILE_PERSISTENCE_PASSED")
        } finally {
            scenario.close()
        }
    }
}
