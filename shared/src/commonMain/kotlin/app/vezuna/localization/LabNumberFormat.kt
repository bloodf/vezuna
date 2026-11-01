package app.vezuna.localization

import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * UI number formatting for lab values. It only changes presentation: stored Doubles are never rounded or rewritten.
 * Doctor exports (PDF/CSV) can adopt [formatLabValue] later; they are formatted elsewhere today.
 */
private val COMMA_DECIMAL_LANGUAGES = setOf("pt", "es")
private const val MAX_DECIMALS = 9
private val PLAIN_NUMBER = Regex("[+-]?\\d+(?:[.,](\\d+))?")

fun decimalSeparatorFor(languageTag: String = deviceLanguageTag()): Char =
    if (languageTag.substringBefore('-').substringBefore('_').lowercase() in COMMA_DECIMAL_LANGUAGES) ',' else '.'

/**
 * Formats [value] with the language's decimal separator. [decimals] is the precision the report printed
 * (5.70 -> 2); when unknown the shortest exact form is used, so 5.0 reads "5" and 13.2 reads "13.2".
 */
fun formatLabValue(value: Double, decimals: Int? = null, languageTag: String = deviceLanguageTag()): String {
    if (!value.isFinite()) return value.toString()
    val places = decimals?.coerceIn(0, MAX_DECIMALS) ?: shortestPlaces(value)
    return fixedPlaces(value, places).replace('.', decimalSeparatorFor(languageTag))
}

/** Confidence as a whole-number percentage ("91%"); the stored fraction is untouched. */
fun formatConfidence(confidence: Float): String = "${(confidence * 100).roundToInt()}%"

/** Fraction digits of a plain typed number ("5,70" -> 2); null when [token] is not a plain decimal. */
fun decimalPlacesOf(token: String): Int? =
    PLAIN_NUMBER.matchEntire(token.trim())?.let { (it.groupValues[1].length).coerceAtMost(MAX_DECIMALS) }

/** Swaps the separator of a plain decimal for the language's; anything else (text, ranges, units) is returned as is. */
fun localizeDecimalText(token: String, languageTag: String = deviceLanguageTag()): String =
    if (PLAIN_NUMBER.matches(token.trim())) token.trim().replace('.', decimalSeparatorFor(languageTag)).replace(',', decimalSeparatorFor(languageTag)) else token

private fun shortestPlaces(value: Double): Int {
    val clean = round(value * 1_000_000.0) / 1_000_000.0
    return (0..MAX_DECIMALS).firstOrNull { fixedPlaces(clean, it).toDouble() == clean } ?: MAX_DECIMALS
}

/** Plain positional notation with exactly [places] digits after the point; never scientific. */
private fun fixedPlaces(value: Double, places: Int): String {
    val factor = (1..places).fold(1.0) { acc, _ -> acc * 10.0 }
    val scaled = round(abs(value) * factor)
    if (scaled >= 9.0e15) return value.toString()
    val whole = scaled.toLong()
    val divisor = factor.toLong()
    val integer = (whole / divisor).toString()
    val fraction = if (places == 0) "" else "." + (whole % divisor).toString().padStart(places, '0')
    val negative = value < 0 && whole != 0L
    return (if (negative) "-" else "") + integer + fraction
}
