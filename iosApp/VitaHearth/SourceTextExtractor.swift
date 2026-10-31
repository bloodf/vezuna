import PDFKit
import UIKit
import Vision

struct ExtractedSourceText {
    let text: String
    let ocrConfidence: Float?
    let usedOfflineOCR: Bool
}

enum SourceTextExtractor {
    static func extract(data: Data, mediaType: String) async throws -> ExtractedSourceText {
        if mediaType == "application/pdf" {
            return try await extractPDF(data)
        }
        guard let image = normalizedImage(data: data) else { throw ImportError.unreadableImage }
        let result = try await recognize(image)
        return ExtractedSourceText(text: result.text, ocrConfidence: result.confidence, usedOfflineOCR: true)
    }

    private static func extractPDF(_ data: Data) async throws -> ExtractedSourceText {
        guard let document = PDFDocument(data: data) else { throw ImportError.unreadablePDF }
        let embedded = document.string?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !embedded.isEmpty {
            return ExtractedSourceText(text: embedded, ocrConfidence: nil, usedOfflineOCR: false)
        }
        var pages: [String] = []
        var confidences: [Float] = []
        for index in 0..<document.pageCount {
            guard let page = document.page(at: index) else { continue }
            let bounds = page.bounds(for: .mediaBox).integral
            guard bounds.width > 0, bounds.height > 0 else { continue }
            let scale = min(2.0, 2400.0 / max(bounds.width, bounds.height))
            let format = UIGraphicsImageRendererFormat.default()
            format.scale = 1
            let renderer = UIGraphicsImageRenderer(size: CGSize(width: bounds.width * scale, height: bounds.height * scale), format: format)
            let image = renderer.image { context in
                UIColor.white.setFill()
                context.fill(CGRect(origin: .zero, size: CGSize(width: bounds.width * scale, height: bounds.height * scale)))
                context.cgContext.scaleBy(x: scale, y: scale)
                page.draw(with: .mediaBox, to: context.cgContext)
            }
            guard let cgImage = image.cgImage else { continue }
            let result = try await recognize(cgImage)
            if !result.text.isEmpty { pages.append(result.text) }
            if let confidence = result.confidence { confidences.append(confidence) }
        }
        let combined = pages.joined(separator: "\n")
        guard !combined.isEmpty else { throw ImportError.noTextFound }
        let mean = confidences.isEmpty ? nil : confidences.reduce(0, +) / Float(confidences.count)
        return ExtractedSourceText(text: combined, ocrConfidence: mean, usedOfflineOCR: true)
    }

    private static func recognize(_ image: CGImage) async throws -> (text: String, confidence: Float?) {
        try await withCheckedThrowingContinuation { continuation in
            let request = VNRecognizeTextRequest { request, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                let observations = (request.results as? [VNRecognizedTextObservation]) ?? []
                var lines: [String] = []
                var confidenceSum: Float = 0
                var count = 0
                for observation in observations {
                    guard let candidate = observation.topCandidates(1).first else { continue }
                    lines.append(candidate.string)
                    confidenceSum += candidate.confidence
                    count += 1
                }
                continuation.resume(returning: (lines.joined(separator: "\n"), count == 0 ? nil : confidenceSum / Float(count)))
            }
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = true
            request.minimumTextHeight = 0.008
            let handler = VNImageRequestHandler(cgImage: image, options: [:])
            do {
                try handler.perform([request])
            } catch {
                continuation.resume(throwing: error)
            }
        }
    }

    private static func normalizedImage(data: Data) -> CGImage? {
        guard let source = UIImage(data: data) else { return nil }
        if source.imageOrientation == .up { return source.cgImage }
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = source.scale
        return UIGraphicsImageRenderer(size: source.size, format: format).image { _ in
            source.draw(in: CGRect(origin: .zero, size: source.size))
        }.cgImage
    }
}

enum ImportError: LocalizedError {
    case unreadablePDF
    case unreadableImage
    case noTextFound
    case noStructuredResults
    case cameraUnavailable
    case invalidSelection

    var errorDescription: String? {
        switch self {
        case .unreadablePDF: return L10n.text(.import_unreadable_pdf)
        case .unreadableImage: return L10n.text(.import_unreadable_image)
        case .noTextFound: return L10n.text(.no_text_found)
        case .noStructuredResults: return L10n.text(.no_structured_results)
        case .cameraUnavailable: return L10n.text(.camera_unavailable)
        case .invalidSelection: return L10n.text(.invalid_selection)
        }
    }
}
