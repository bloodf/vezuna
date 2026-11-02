package app.vezuna.ui

import app.vezuna.domain.ReferenceRange
import app.vezuna.platform.sha256
import app.vezuna.platform.toHex

// Import identity helpers, pure functions moved out of AppController to keep it focused.

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
