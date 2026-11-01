package app.vezuna

import app.vezuna.localization.decimalPlacesOf
import app.vezuna.localization.decimalSeparatorFor
import app.vezuna.localization.formatConfidence
import app.vezuna.localization.formatLabValue
import app.vezuna.localization.localizeDecimalText
import app.vezuna.data.InMemoryVezunaRepository
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LabNumberFormatTest {
    @Test
    fun separatorFollowsTheLanguage() {
        assertEquals('.', decimalSeparatorFor("en-US"))
        assertEquals(',', decimalSeparatorFor("pt-BR"))
        assertEquals(',', decimalSeparatorFor("es"))
        assertEquals(',', decimalSeparatorFor("pt_PT"))
    }

    @Test
    fun wholeValuesHaveNoTrailingZeroAndDecimalsUseTheLocaleSeparator() {
        assertEquals("5", formatLabValue(5.0, null, "pt-BR"))
        assertEquals("5,7", formatLabValue(5.7, null, "pt-BR"))
        assertEquals("5.7", formatLabValue(5.7, null, "en-US"))
        assertEquals("-0,5", formatLabValue(-0.5, null, "es-ES"))
        assertEquals("250000", formatLabValue(250000.0, null, "en"))
        assertEquals("0,0001", formatLabValue(0.0001, null, "pt"))
    }

    @Test
    fun reportPrecisionIsKept() {
        assertEquals("5,70", formatLabValue(5.7, 2, "pt-BR"))
        assertEquals("5.00", formatLabValue(5.0, 2, "en"))
        assertEquals("13", formatLabValue(13.2, 0, "en"))
        assertEquals("1,020", formatLabValue(1.02, 3, "pt-BR"))
    }

    @Test
    fun normalizedFloatNoiseDoesNotLeak() {
        assertEquals("132", formatLabValue(13.2 * 10.0, null, "en"))
        assertEquals("0.3", formatLabValue(0.1 + 0.2, null, "en"))
    }

    @Test
    fun confidenceIsAPercentage() {
        assertEquals("91%", formatConfidence(0.91f))
        assertEquals("40%", formatConfidence(0.4f))
        assertEquals("100%", formatConfidence(1f))
    }

    @Test
    fun typedPrecisionAndLocalizedEditorText() {
        assertEquals(2, decimalPlacesOf("5,70"))
        assertEquals(0, decimalPlacesOf("13"))
        assertNull(decimalPlacesOf("Negativo"))
        assertEquals("5,70", localizeDecimalText("5.70", "pt-BR"))
        assertEquals("5.70", localizeDecimalText("5,70", "en"))
        assertEquals("Negativo", localizeDecimalText("Negativo", "pt"))
        assertEquals("12 a 15", localizeDecimalText("12 a 15", "pt"))
    }

    @Test
    fun confirmationStoresCanonicalValueAndTheTypedPrecision() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = labController(repository)
        controller.initialize()
        controller.addManualResult("HGB", "Hemoglobin", "5,70", "g/dL", "", "", null, Instant.parse("2026-03-10T12:00:00Z"))
        val observation = repository.readProfile(repository.profiles.value.single().profileId).observations.single()
        assertEquals(5.7, observation.originalValue)
        assertEquals(2, observation.valueDecimals)
        assertEquals("5,70", formatLabValue(observation.originalValue, observation.valueDecimals, "pt-BR"))
        assertEquals("5.70", formatLabValue(observation.originalValue, observation.valueDecimals, "en-US"))
    }
}
