package app.vezuna.importer

import app.vezuna.domain.NumberAmbiguity
import app.vezuna.ui.ExtractedCandidate

/** Parses OCR/embedded text into review candidates; the one entry point for Android and iOS. */
fun parseExtractedCandidates(text: String, ocrConfidence: Float? = null): List<ExtractedCandidate> =
    OcrTextParser.parseMeasurements(text).map { parsed ->
        ExtractedCandidate(
            testCode = OcrTextParser.canonicalTestCode(parsed.displayName),
            displayName = parsed.displayName,
            rawValue = parsed.value,
            rawUnit = parsed.unit,
            referenceRange = parsed.referenceRange,
            collectedAt = parsed.collectedAt,
            // An ambiguous token ("1.020") is never more trustworthy than the low-confidence marker.
            confidence = if (parsed.rawToken != null) minOf(ocrConfidence ?: NumberAmbiguity.LOW_CONFIDENCE, NumberAmbiguity.LOW_CONFIDENCE) else ocrConfidence,
            valueKind = parsed.valueKind,
            rawToken = parsed.rawToken,
        )
    }
