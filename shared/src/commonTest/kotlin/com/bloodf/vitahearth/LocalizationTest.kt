package com.bloodf.vitahearth

import com.bloodf.vitahearth.localization.L10nKey
import com.bloodf.vitahearth.localization.UserText
import com.bloodf.vitahearth.localization.VitaMessageException
import com.bloodf.vitahearth.localization.formatUiDate
import com.bloodf.vitahearth.localization.formatUiDateTime
import com.bloodf.vitahearth.localization.message
import com.bloodf.vitahearth.localization.parseDecimalInput
import com.bloodf.vitahearth.localization.pluralMessage
import com.bloodf.vitahearth.localization.toUserText
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalizationTest {
    @Test
    fun decimalCommaAndDotProduceTheSameCanonicalValue() {
        assertEquals(13.4, parseDecimalInput("13.4"))
        assertEquals(13.4, parseDecimalInput("13,4"))
        assertEquals(-0.75, parseDecimalInput(" -0,75 "))
    }

    @Test
    fun decimalInputRejectsAmbiguousAndNonFiniteValues() {
        listOf("", "1,234.5", "1.234,5", "NaN", "Infinity", "-Infinity", "abc").forEach {
            assertNull(parseDecimalInput(it), it)
        }
    }

    @Test
    fun controllerMessagesKeepKeysArgumentsAndQuantities() {
        val keyed = message(L10nKey.SWITCH_TO_PROFILE, "Synthetic")
        assertEquals(UserText.Key(L10nKey.SWITCH_TO_PROFILE, listOf("Synthetic")), keyed)
        assertEquals(keyed, VitaMessageException(keyed).toUserText())
        assertEquals(UserText.Key(L10nKey.ACTION_FAILED), IllegalStateException("technical").toUserText())
        assertEquals(
            UserText.Plural(L10nKey.PENDING_CANDIDATES, 2, listOf("unit")),
            pluralMessage(L10nKey.PENDING_CANDIDATES, 2, "unit"),
        )
    }

    @Test
    fun uiDatesUseRequestedLocaleWithoutChangingTheInstant() {
        val instant = Instant.parse("2026-10-31T15:04:05Z")
        assertTrue(formatUiDate(instant, "en-US").contains("Oct"))
        assertTrue(formatUiDate(instant, "pt-BR").contains("out", ignoreCase = true))
        assertTrue(formatUiDate(instant, "es-ES").contains("oct", ignoreCase = true))
        listOf("en-US", "pt-BR", "es-ES").forEach { tag ->
            val dateTime = formatUiDateTime(instant, tag)
            assertTrue(dateTime.contains("2026"), dateTime)
            assertTrue(dateTime.contains(":"), dateTime)
        }
    }
}
