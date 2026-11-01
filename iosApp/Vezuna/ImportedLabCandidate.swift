import Foundation
import VezunaShared

/// Editable review row. Parsing lives in the shared Kotlin parser (`LabTextBridge`); this type only holds edits.
struct ImportedLabCandidate: Identifiable, Equatable {
    let id: UUID
    var testCode: String
    var displayName: String
    var rawValue: String
    var rawUnit: String
    var referenceLower: String
    var referenceUpper: String
    var referenceText: String
    var referenceUnit: String?
    var qualitative: Bool
    var confidence: Float?
    /// Chosen by the user during review (or read from the report row); nil until set. Never defaulted to the import time.
    var collectedAt: Date?
    /// Original OCR number token, kept only when its reading is ambiguous (e.g. "1.020").
    var rawToken: String?

    init(parsed: NativeParsedCandidate) {
        id = UUID()
        testCode = parsed.testCode
        displayName = parsed.displayName
        rawValue = parsed.rawValue
        rawUnit = parsed.rawUnit
        referenceLower = parsed.referenceLower
        referenceUpper = parsed.referenceUpper
        referenceText = parsed.referenceText ?? ""
        referenceUnit = parsed.referenceUnit
        qualitative = parsed.qualitative
        confidence = parsed.confidence?.floatValue
        collectedAt = parsed.collectedEpochSeconds.map { Date(timeIntervalSince1970: TimeInterval(truncating: $0)) }
        rawToken = parsed.rawToken
    }
}
