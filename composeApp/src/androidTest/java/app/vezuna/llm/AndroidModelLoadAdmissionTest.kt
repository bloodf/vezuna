package app.vezuna.llm

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.vezuna.localization.L10nKey
import app.vezuna.localization.VitaMessageException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidModelLoadAdmissionTest {
    private val pack = ModelPack("m", "M", "m.gguf", "a", "i", sizeBytes = 100, minimumRamBytes = 100, maximumContextLength = 4096, sha256 = "a".repeat(64))
    private val settings = LlmGenerationSettings()

    private class NoActivityService(base: Context) : ContextWrapper(base) {
        var activityServiceCalls = 0
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? {
            if (name == Context.ACTIVITY_SERVICE) {
                activityServiceCalls++
                return null
            }
            return super.getSystemService(name)
        }
    }

    @Test
    fun missingBudgetFailsBeforeActivityServiceAccess() {
        val context = NoActivityService(InstrumentationRegistry.getInstrumentation().targetContext)
        val admission = AndroidModelLoadAdmission(context, "build")
        assertDenial(L10nKey.MODEL_LOAD_POLICY_UNAVAILABLE) { admission.requireFits(pack, settings) }
        assertEquals(0, context.activityServiceCalls)
    }

    @Test
    fun approvedBudgetWithMissingActivityServiceFailsCapacity() {
        val context = NoActivityService(InstrumentationRegistry.getInstrumentation().targetContext)
        val key = ModelLoadPolicyKey(pack.sha256, pack.sizeBytes, settings, "build")
        val admission = AndroidModelLoadAdmission(context, "build", mapOf(key to 100L))
        assertDenial(L10nKey.MODEL_CAPACITY_UNAVAILABLE) { admission.requireFits(pack, settings) }
        assertEquals(1, context.activityServiceCalls)
    }

    private fun assertDenial(expected: L10nKey, block: () -> Unit) {
        try {
            block()
            fail("Expected admission denial")
        } catch (failure: VitaMessageException) {
            assertEquals(expected, failure.userText.key)
        }
    }
}
