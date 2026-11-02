package app.vezuna.ui.entry

import app.vezuna.domain.LabObservation
import app.vezuna.domain.NumberAmbiguity
import app.vezuna.domain.OcrCandidate
import app.vezuna.localization.formatLabValue
import app.vezuna.localization.localizeDecimalText
import app.vezuna.localization.parseDecimalInput
import kotlinx.datetime.Instant

/**
 * What the person is typing for one result: the same fields for manual entry, OCR review and correction.
 * Values stay text until the domain resolves them, so a text result never becomes a number.
 */
data class ResultDraft(
    val name: String = "",
    val code: String = "",
    val asText: Boolean = false,
    val value: String = "",
    val unit: String = "",
    val lower: String = "",
    val upper: String = "",
    val rangeText: String = "",
    val date: Instant? = null,
)

enum class FieldError { REQUIRED, NOT_A_NUMBER, AMBIGUOUS, UNIT_REQUIRED, RANGE_REVERSED, DATE_REQUIRED }

data class ResultErrors(
    val name: FieldError? = null,
    val code: FieldError? = null,
    val value: FieldError? = null,
    val unit: FieldError? = null,
    val lower: FieldError? = null,
    val upper: FieldError? = null,
    val date: FieldError? = null,
) {
    val count: Int get() = listOfNotNull(name, code, value, unit, lower, upper, date).size
    val isEmpty: Boolean get() = count == 0
}

/**
 * Mirrors the domain rules so errors appear beside their fields before saving; the domain still validates.
 * [prefills] are values the app put in the field itself; an untouched ambiguous prefill is never flagged here.
 */
fun ResultDraft.validate(requireIdentity: Boolean, prefills: List<String> = emptyList()): ResultErrors {
    val name = if (requireIdentity && name.isBlank()) FieldError.REQUIRED else null
    val code = if (requireIdentity && code.isBlank()) FieldError.REQUIRED else null
    val date = if (date == null) FieldError.DATE_REQUIRED else null
    if (asText) return ResultErrors(name, code, if (value.isBlank()) FieldError.REQUIRED else null, date = date)
    val valueError = when {
        value.isBlank() -> FieldError.REQUIRED
        value.trim() !in prefills && NumberAmbiguity.isAmbiguousThousands(value) -> FieldError.AMBIGUOUS
        parseDecimalInput(value) == null -> FieldError.NOT_A_NUMBER
        else -> null
    }
    val unitError = if (unit.isBlank()) FieldError.UNIT_REQUIRED else null
    val low = lower.takeIf { it.isNotBlank() }?.let(::parseDecimalInput)
    val high = upper.takeIf { it.isNotBlank() }?.let(::parseDecimalInput)
    val lowerError = if (lower.isNotBlank() && low == null) FieldError.NOT_A_NUMBER else null
    val upperError = if (upper.isNotBlank() && high == null) FieldError.NOT_A_NUMBER else null
    val reversed = low != null && high != null && low > high
    return ResultErrors(
        name, code, valueError, unitError,
        lowerError ?: if (reversed) FieldError.RANGE_REVERSED else null,
        upperError ?: if (reversed) FieldError.RANGE_REVERSED else null,
        date,
    )
}

/** A candidate's draft, prefilled from the extracted values. The date stays empty unless the report or the person set it. */
fun OcrCandidate.toDraft(chosenDate: Instant?): ResultDraft = ResultDraft(
    name = displayName,
    code = testCode,
    asText = looksQualitative,
    value = if (looksQualitative) rawValue else localizeDecimalText(rawValue),
    unit = rawUnit,
    lower = referenceRange.lower?.let { formatLabValue(it) }.orEmpty(),
    upper = referenceRange.upper?.let { formatLabValue(it) }.orEmpty(),
    rangeText = referenceRange.text.orEmpty(),
    date = chosenDate ?: collectedAt.takeIf { collectedAtKnown },
)

/** A confirmed result's draft for correction: the original value at its recorded precision, never the normalized one. */
fun LabObservation.toDraft(): ResultDraft = ResultDraft(
    name = displayName,
    code = testCode,
    asText = isQualitative,
    value = qualitativeValue ?: formatLabValue(originalValue, valueDecimals),
    unit = originalUnit,
    lower = referenceRange.lower?.let { formatLabValue(it) }.orEmpty(),
    upper = referenceRange.upper?.let { formatLabValue(it) }.orEmpty(),
    rangeText = referenceRange.text.orEmpty(),
    date = collectedAt,
)

/** The two readings of an ambiguous "d.ddd" token (image 042). Each maps to an input the domain reads one way only. */
enum class NumberReading { WHOLE, DECIMAL }

/** Unambiguous text for a reading of [token]: "1.020" whole is "1020", decimal is "1,020" (the comma is always decimal). */
fun readingInput(token: String, reading: NumberReading): String {
    val trimmed = token.trim()
    return when (reading) {
        NumberReading.WHOLE -> trimmed.replace(".", "")
        NumberReading.DECIMAL -> trimmed.replace('.', ',')
    }
}

/** What the person sees for a reading, in their language's decimal style (the decimal keeps the printed digits). */
fun readingLabel(token: String, reading: NumberReading): String {
    val input = readingInput(token, reading)
    val value = parseDecimalInput(input) ?: return input
    return when (reading) {
        NumberReading.WHOLE -> formatLabValue(value, 0)
        NumberReading.DECIMAL -> formatLabValue(value, input.substringAfter(',').length)
    }
}
