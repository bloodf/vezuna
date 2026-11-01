package app.vezuna

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyPolicyInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun policyOpensFromDeviceMenuAndReturnsToRecords() {
        device.executeShellCommand("cmd locale set-app-locales ${context.packageName} --user 0 --locales en-US")
        ActivityScenario.launch(AndroidMainActivity::class.java).use {
            assertTrue(device.wait(Until.hasObject(By.textContains("Household")), 15_000))
            device.findObject(By.text("Device")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Privacy policy")), 5_000))
            device.findObject(By.text("Privacy policy")).click()
            assertPolicyVisible()
            device.findObject(By.text("Close")).click()
            assertTrue(device.wait(Until.hasObject(By.textContains("Household")), 5_000))
        }
    }

    @Test
    fun healthConnectRationaleResolvesAndShowsBundledPolicy() {
        val intent = Intent("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE").setPackage(context.packageName)
        val activities = context.packageManager.queryIntentActivities(intent, 0)
        assertEquals(listOf("app.vezuna.PermissionsRationaleActivity"), activities.map { it.activityInfo.name })
        assertTrue(activities.single().activityInfo.exported)
        intent.component = ComponentName(context.packageName, activities.single().activityInfo.name)
        ActivityScenario.launch<PermissionsRationaleActivity>(intent).use { assertPolicyVisible() }
    }

    @Test
    fun android14AliasResolvesWithRequiredPermissionAndShowsPolicy() {
        val intent = Intent("android.intent.action.VIEW_PERMISSION_USAGE")
            .addCategory("android.intent.category.HEALTH_PERMISSIONS").setPackage(context.packageName)
        val activities = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val alias = activities.single().activityInfo
        assertEquals("app.vezuna.ViewPermissionUsageActivity", alias.name)
        assertEquals("app.vezuna.PermissionsRationaleActivity", alias.targetActivity)
        assertEquals("android.permission.START_VIEW_PERMISSION_USAGE", alias.permission)
        assertTrue(alias.exported)
        intent.component = ComponentName(context.packageName, alias.name)
        ActivityScenario.launch<PermissionsRationaleActivity>(intent).use { assertPolicyVisible() }
    }

    private fun assertPolicyVisible() {
        assertTrue(device.wait(Until.hasObject(By.text("Vezuna privacy policy")), 10_000))
        assertTrue(device.hasObject(By.textContains("Vezuna is published by Heitor Ribeiro")))
        assertTrue(device.hasObject(By.textContains("heitor.ramon@gmail.com")))
        assertTrue(context.assets.open("PrivacyPolicy.txt").bufferedReader().use { it.readText() }
            .contains("Vezuna reads heart rate, steps, and weight"))
    }
}
