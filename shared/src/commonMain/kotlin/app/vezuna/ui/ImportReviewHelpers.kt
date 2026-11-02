package app.vezuna.ui

import app.vezuna.domain.ReferenceRange
import app.vezuna.importer.OcrTextParser
import app.vezuna.localization.L10nKey
import app.vezuna.localization.failMessage
import app.vezuna.localization.parseDecimalInput
import app.vezuna.platform.sha256
import app.vezuna.platform.toHex

// Pure import and review helpers, kept out of AppController so it stays focused on state.

/** One page: the file hash. Several photos: the hash of the page hashes, so no page is ever concatenated in memory. */
internal fun sourceIdentity(source: PickedSource): String {
    val first = sha256(source.bytes).toHex()
    if (source.additionalPages.isEmpty()) return first
    return sha256((listOf(first) + source.additionalPages.map { sha256(it).toHex() }).joinToString("|").encodeToByteArray()).toHex()
}

internal fun candidateFingerprint(
    testCode: String,
    displayName: String,
    rawValue: String,
    rawUnit: String,
    range: ReferenceRange,
    collectedAt: kotlinx.datetime.Instant,
): String = listOf(
    testCode.trim().uppercase(),
    displayName.trim().lowercase(),
    rawValue.trim(),
    rawUnit.trim(),
    range.lower?.toString().orEmpty(),
    range.upper?.toString().orEmpty(),
    range.text.orEmpty(),
    range.unit.orEmpty(),
    collectedAt.toString(),
).joinToString("|")

/**
 * Bounds come from the reviewer's fields. Reference text and bounds must never disagree:
 * - edited bounds with unchanged text clear the stale text (the earlier text stays in the revision);
 * - edited text with untouched bounds recomputes the bounds from it, which only succeeds for exactly one range
 *   or one-sided `<`, `>`, `≤`, `≥` threshold, and otherwise clears them;
 * - both edited: the reviewer's bounds and text are kept as typed.
 * Nothing else is inferred, so a range is never invented.
 */
internal fun reviewedRange(
    original: ReferenceRange,
    editedText: String?,
    lowerInput: String,
    upperInput: String,
    unit: String,
    textResult: Boolean,
): ReferenceRange {
    var text = (editedText ?: original.text)?.trim()?.takeIf { it.isNotEmpty() }
    if (textResult) return ReferenceRange(text = text)
    var lower = optionalDouble(lowerInput)
    var upper = optionalDouble(upperInput)
    val textChanged = text != original.text
    val boundsChanged = lower != original.lower || upper != original.upper
    if (textChanged && !boundsChanged) {
        val derived = text?.let { OcrTextParser.referenceFromText(it, unit) }
        lower = derived?.lower
        upper = derived?.upper
    } else if (boundsChanged && !textChanged) {
        text = null
    }
    return ReferenceRange(lower, upper, text, unit)
}

internal fun optionalDouble(raw: String): Double? = raw.trim().takeIf { it.isNotEmpty() }?.let {
    parseDecimalInput(it) ?: failMessage(L10nKey.REFERENCE_BOUNDS_NUMERIC)
}
