import PDFKit
import XCTest
import VezunaShared
@testable import Vezuna

/// Renders the shared laboratory-history layout through the real iOS canvas (UIGraphicsPDFRenderer). Synthetic data only.
final class PdfExportTests: XCTestCase {
    private func instant(_ text: String) -> KotlinInstant { KotlinInstant.companion.parse(input: text) }

    private func row(_ date: String, _ value: Double, _ decimals: Int32, source: String) -> DoctorResultRow {
        DoctorResultRow(
            collectedAt: instant(date), testName: "Hemoglobin", testCode: "HGB",
            originalValue: KotlinDouble(value: value), originalUnit: "g/dL",
            normalizedValue: KotlinDouble(value: value * 10), normalizedUnit: "g/L",
            referenceRange: ReferenceRange(lower: KotlinDouble(value: 12), upper: KotlinDouble(value: 16), text: "12.0–16.0 g/dL", unit: "g/dL"),
            assessment: .within, qualitativeValue: nil, valueDecimals: KotlinInt(int: decimals),
            sourceName: source, manualEntry: false
        )
    }

    private func document(paper: PaperSize, grayscale: Bool, language: ContentLanguage) -> DoctorPdfDocument {
        DoctorPdfDocument(
            title: language == .pt ? "Histórico laboratorial" : "Laboratory history",
            lines: [], trends: [], generatedAt: instant("2026-11-01T12:41:00Z"), profileDisplayName: "Ana Silva", ageYears: nil,
            resultRows: [
                row("2026-04-12T11:00:00Z", 13.0, 1, source: "April blood work"),
                row("2026-07-15T11:00:00Z", 12.8, 1, source: "July blood work"),
                row("2026-10-28T11:00:00Z", 13.2, 1, source: "Blood count"),
            ],
            medicationRows: [DoctorMedicationRow(displayName: "Vitamin D", strength: "1,000 IU", instructions: "1 capsule", active: true, times: ["08:00"], startDate: nil)],
            language: language, birthDate: nil, paper: paper, grayscale: grayscale,
            scope: DoctorExportScope(selectedFrom: nil, selectedTo: nil, availableFirst: nil, availableLast: nil, excludedUnreviewed: 3, medicationsIncluded: true)
        )
    }

    func testLetterAndA4PagesCarrySelectableLocalizedText() throws {
        for (paper, size) in [(PaperSize.a4, CGSize(width: 595.28, height: 841.89)), (PaperSize.letter, CGSize(width: 612, height: 792))] {
            let exported = NativePdfExporter().export(document: document(paper: paper, grayscale: paper == .letter, language: .en))
            XCTAssertEqual(exported.fileName, "vezuna-ana-silva-lab-history-2026-11-01.pdf")
            let data = exported.bytes.toData()
            let pdf = try XCTUnwrap(PDFDocument(data: data))
            let bounds = try XCTUnwrap(pdf.page(at: 0)).bounds(for: .mediaBox)
            XCTAssertEqual(bounds.width, size.width, accuracy: 0.5)
            XCTAssertEqual(bounds.height, size.height, accuracy: 0.5)
            let text = try XCTUnwrap(pdf.string)
            for expected in ["Laboratory history", "Ana Silva", "13.2 g/dL", "+0.4 g/dL", "Within report range", "Page 1 of \(pdf.pageCount)"] {
                XCTAssertTrue(text.contains(expected), "missing \(expected)")
            }
            try data.write(to: FileManager.default.temporaryDirectory.appendingPathComponent("vezuna-ios-lab-history-\(paper.name.lowercased()).pdf"))
        }
        let portuguese = NativePdfExporter().export(document: document(paper: .a4, grayscale: false, language: .pt))
        let text = try XCTUnwrap(PDFDocument(data: portuguese.bytes.toData())?.string)
        XCTAssertTrue(text.contains("13,2 g/dL") && text.contains("Dentro da faixa do laudo"))
    }
}
