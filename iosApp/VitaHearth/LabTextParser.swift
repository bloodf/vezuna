import Foundation

struct ImportedLabCandidate: Identifiable, Equatable {
    let id: UUID
    var testCode: String
    var displayName: String
    var rawValue: String
    var rawUnit: String
    var referenceLower: String
    var referenceUpper: String
    var confidence: Float?

    init(id: UUID = UUID(), testCode: String, displayName: String, rawValue: String, rawUnit: String, referenceLower: String = "", referenceUpper: String = "", confidence: Float? = nil) {
        self.id = id
        self.testCode = testCode
        self.displayName = displayName
        self.rawValue = rawValue
        self.rawUnit = rawUnit
        self.referenceLower = referenceLower
        self.referenceUpper = referenceUpper
        self.confidence = confidence
    }
}

enum LabTextParser {
    private static let aliases: [String: (code: String, name: String)] = [
        "HGB": ("HGB", "Hemoglobin"),
        "HAEMOGLOBIN": ("HGB", "Hemoglobin"),
        "HEMOGLOBIN": ("HGB", "Hemoglobin"),
        "WBC": ("WBC", "White blood cell count"),
        "RBC": ("RBC", "Red blood cell count"),
        "PLT": ("PLT", "Platelet count"),
        "PLATELETS": ("PLT", "Platelet count"),
        "GLUCOSE": ("GLU", "Glucose"),
        "CREATININE": ("CREA", "Creatinine"),
        "HBA1C": ("HBA1C", "Hemoglobin A1c"),
        "TSH": ("TSH", "Thyroid-stimulating hormone"),
        "LDL": ("LDL", "LDL cholesterol"),
        "HDL": ("HDL", "HDL cholesterol"),
        "CHOLESTEROL": ("CHOL", "Cholesterol")
    ]

    private static let resultPattern = #"^([A-Za-z][A-Za-z0-9 .()\/-]{0,48}?)\s+(-?\d+(?:[.,]\d+)?)\s+([^\s]+)(.*)$"#
    private static let rangePattern = #"(-?\d+(?:[.,]\d+)?)\s*(?:-|–|to)\s*(-?\d+(?:[.,]\d+)?)"#

    static func parse(_ text: String, confidence: Float? = nil) -> [ImportedLabCandidate] {
        guard let resultRegex = try? NSRegularExpression(pattern: resultPattern),
              let rangeRegex = try? NSRegularExpression(pattern: rangePattern, options: [.caseInsensitive]) else { return [] }
        return text.components(separatedBy: .newlines).compactMap { rawLine in
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !line.isEmpty else { return nil }
            let range = NSRange(line.startIndex..., in: line)
            guard let match = resultRegex.firstMatch(in: line, range: range),
                  let nameRange = Range(match.range(at: 1), in: line),
                  let valueRange = Range(match.range(at: 2), in: line),
                  let unitRange = Range(match.range(at: 3), in: line),
                  let suffixRange = Range(match.range(at: 4), in: line) else { return nil }

            let rawName = String(line[nameRange]).trimmingCharacters(in: CharacterSet(charactersIn: ":- "))
            let normalizedName = rawName.uppercased()
            guard !normalizedName.contains("DATE"), !normalizedName.contains("PATIENT"), !normalizedName.contains("REFERENCE") else { return nil }
            let value = String(line[valueRange]).replacingOccurrences(of: ",", with: ".")
            let unit = String(line[unitRange]).trimmingCharacters(in: CharacterSet(charactersIn: ",;"))
            guard Double(value) != nil, unit.count <= 32, !unit.contains(":") else { return nil }
            let alias = aliases[normalizedName]
            let suffix = String(line[suffixRange])
            var lower = ""
            var upper = ""
            let suffixNSRange = NSRange(suffix.startIndex..., in: suffix)
            if let rangeMatch = rangeRegex.firstMatch(in: suffix, range: suffixNSRange),
               let lowerRange = Range(rangeMatch.range(at: 1), in: suffix),
               let upperRange = Range(rangeMatch.range(at: 2), in: suffix) {
                lower = String(suffix[lowerRange]).replacingOccurrences(of: ",", with: ".")
                upper = String(suffix[upperRange]).replacingOccurrences(of: ",", with: ".")
            }
            return ImportedLabCandidate(
                testCode: alias?.code ?? inferredCode(rawName),
                displayName: alias?.name ?? rawName,
                rawValue: value,
                rawUnit: unit,
                referenceLower: lower,
                referenceUpper: upper,
                confidence: confidence
            )
        }
    }

    private static func inferredCode(_ name: String) -> String {
        let words = name.split(separator: " ")
        if words.count == 1 { return String(name.prefix(8)).uppercased() }
        let initials = words.compactMap(\.first).map(String.init).joined()
        return String(initials.prefix(8)).uppercased()
    }
}
