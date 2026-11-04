package app.vezuna

import android.content.res.Configuration
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalizationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun nativeReminderAndMessageResourcesUseAllThreeLanguages() {
        data class Expectation(val tag: String, val reminder: String, val taken: String, val one: String, val other: String)
        val expectations = listOf(
            Expectation("en-US", "Recorded medication reminder", "Taken", "1 metric sample", "2 metric samples"),
            Expectation("pt-BR", "Lembrete de medicamento registrado", "Tomada", "1 amostra métrica", "2 amostras métricas"),
            Expectation("es-ES", "Recordatorio de medicación registrado", "Tomada", "1 muestra métrica", "2 muestras métricas"),
        )
        expectations.forEach { expected ->
            val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(expected.tag)) }
            val localized = context.createConfigurationContext(configuration)
            assertEquals(expected.reminder, nativeMessage(NativeKey.MEDICATION_REMINDER_TITLE).resolve(localized))
            assertEquals(expected.taken, nativeMessage(NativeKey.TAKEN).resolve(localized))
            assertTrue(nativePlural(NativeKey.HEALTH_CONNECT_IMPORT_COMPLETE, 1).resolve(localized).contains(expected.one))
            assertTrue(nativePlural(NativeKey.HEALTH_CONNECT_IMPORT_COMPLETE, 2).resolve(localized).contains(expected.other))
            assertTrue(nativeMessage(NativeKey.NOTIFICATION_PERMISSION_DENIED).resolve(localized).isNotBlank())
        }
        println("NATIVE_LOCALIZATION_RESOURCES_VERIFIED")
    }

    @Test
    fun portugueseSpanishEnglishFallbackRecreationAndLargeTextNavigate() {
        var scenario: ActivityScenario<AndroidMainActivity>? = null
        try {
            verifyLanguage("pt-BR", "Salvo neste dispositivo", "Mais", "Ajustes e preferências", "Cuidados", "Agenda de medicamentos", scenario = null) { scenario = it }
            verifyLanguage("es-ES", "Guardado en este dispositivo", "Más", "Ajustes y preferencias", "Cuidados", "Horario de medicamentos", scenario = null) { scenario = it }
            verifyLanguage("fr-FR", "Saved on this device", "More", "Settings and preferences", "Care", "Medication schedule", scenario = null) { scenario = it }
            println("LOCALE_NAVIGATION_RECREATION_LARGE_TEXT_PASSED")
        } finally {
            scenario?.close()
            device.executeShellCommand("cmd locale set-app-locales ${context.packageName} --user 0 --locales en-US")
            device.executeShellCommand("settings put system font_scale 1.0")
        }
    }

    private fun verifyLanguage(
        tag: String,
        savedOnDevice: String,
        moreTab: String,
        moreSubtitle: String,
        careTab: String,
        careSubtitle: String,
        scenario: ActivityScenario<AndroidMainActivity>?,
        assign: (ActivityScenario<AndroidMainActivity>) -> Unit,
    ) {
        device.executeShellCommand("cmd locale set-app-locales ${context.packageName} --user 0 --locales $tag")
        val current = scenario ?: ActivityScenario.launch(AndroidMainActivity::class.java).also(assign)
        assertTrue("Home did not render for $tag", device.awaitToday(savedOnDevice))
        capture("localization-$tag-home.png")
        device.tapText(moreTab)
        assertTrue(device.awaitText(moreSubtitle))
        current.recreate()
        // The session is retained across recreation, so the selected More page stays, in the same language.
        assertTrue("Recreation lost $tag", device.awaitText(moreSubtitle, 10_000))
        device.tapText(careTab)
        assertTrue(device.awaitText(careSubtitle, 5_000))
        device.executeShellCommand("settings put system font_scale 1.3")
        device.waitForIdle()
        assertTrue("Large text broke $tag", device.awaitText(careSubtitle, 10_000))
        device.tapText(careTab)
        assertTrue(device.awaitText(careSubtitle, 5_000))
        capture("localization-$tag-large-text.png")
        device.executeShellCommand("settings put system font_scale 1.0")
        current.close()
    }

    private fun capture(name: String) {
        device.executeShellCommand("screencap -p /sdcard/$name")
        println("LOCALIZATION_CAPTURE $name")
    }
}
