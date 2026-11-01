package app.vezuna.ui

import app.vezuna.domain.NormalizedQuantity
import app.vezuna.domain.UnitNormalizer
import app.vezuna.localization.L10nKey
import app.vezuna.localization.decimalPlacesOf
import app.vezuna.localization.failMessage
import app.vezuna.localization.parseDecimalInput

/** A reviewer-entered result value, validated: text results carry no number or unit, numeric ones always have a unit. */
internal data class ResolvedResultValue(
    val value: Double,
    val unit: String,
    val normalized: NormalizedQuantity,
    val qualitativeValue: String?,
    /** Fraction digits as typed (5,70 -> 2), kept so display matches the report. */
    val decimals: Int?,
)

internal fun resolveResultValue(asText: Boolean, rawValue: String, rawUnit: String): ResolvedResultValue {
    if (asText) {
        val text = rawValue.trim().ifBlank { failMessage(L10nKey.QUALITATIVE_RESULT_REQUIRED) }
        return ResolvedResultValue(0.0, "", NormalizedQuantity(0.0, ""), text, null)
    }
    val value = parseDecimalInput(rawValue) ?: failMessage(L10nKey.ENTER_NUMERIC_RESULT)
    val unit = rawUnit.trim()
    if (unit.isBlank()) failMessage(L10nKey.NUMERIC_CANDIDATE_UNIT_REQUIRED)
    return ResolvedResultValue(value, unit, UnitNormalizer.normalize(value, unit), null, decimalPlacesOf(rawValue))
}
