package app.vezuna.ios

import app.vezuna.importer.parseExtractedCandidates
import app.vezuna.ui.ExtractedValueKind

/** One parsed row for the native review list; bounds are strings so the reviewer can edit them. */
data class NativeParsedCandidate(
    val testCode: String,
    val displayName: String,
    val rawValue: String,
    val rawUnit: String,
    val referenceLower: String,
    val referenceUpper: String,
    val referenceText: String?,
    val referenceUnit: String?,
    val qualitative: Boolean,
    /** Noon-UTC epoch seconds of a date found in the report row, or null when none was confident. */
    val collectedEpochSeconds: Long?,
    val confidence: Float?,
    val rawToken: String?,
)

/** The shared commonMain parser, exposed to Swift: iOS has no parser of its own. */
object LabTextBridge {
    fun parse(text: String, ocrConfidence: Float?): List<NativeParsedCandidate> =
        parseExtractedCandidates(text, ocrConfidence).map { candidate ->
            NativeParsedCandidate(
                testCode = candidate.testCode,
                displayName = candidate.displayName,
                rawValue = candidate.rawValue,
                rawUnit = candidate.rawUnit,
                referenceLower = candidate.referenceRange.lower?.toString().orEmpty(),
                referenceUpper = candidate.referenceRange.upper?.toString().orEmpty(),
                referenceText = candidate.referenceRange.text,
                referenceUnit = candidate.referenceRange.unit,
                qualitative = candidate.valueKind == ExtractedValueKind.QUALITATIVE,
                collectedEpochSeconds = candidate.collectedAt?.epochSeconds,
                confidence = candidate.confidence,
                rawToken = candidate.rawToken,
            )
        }
}
