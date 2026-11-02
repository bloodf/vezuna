import PDFKit
import UIKit
import XCTest
@testable import Vezuna

final class SourceTextExtractorLimitTests: XCTestCase {
    private func pdf(pages: Int) -> Data {
        let bounds = CGRect(x: 0, y: 0, width: 300, height: 400)
        return UIGraphicsPDFRenderer(bounds: bounds).pdfData { context in
            for _ in 0..<pages {
                context.beginPage()
                UIColor.white.setFill()
                context.fill(bounds)
            }
        }
    }

    func testPdfOverThePageCapFailsBeforeAnyPageIsRead() async {
        var started = 0
        do {
            _ = try await SourceTextExtractor.extract(data: pdf(pages: SourceTextExtractor.maxPDFPages + 1), mediaType: "application/pdf") { _, _ in started += 1 }
            XCTFail("A PDF over the page cap must not be read")
        } catch ImportError.tooManyPages {
            XCTAssertEqual(started, 0)
        } catch {
            XCTFail("Unexpected error \(error)")
        }
    }

    func testProgressReportsEveryPageAndCancellingStopsReading() async {
        var seen: [Int] = []
        let task = Task {
            try await SourceTextExtractor.extract(data: pdf(pages: 5), mediaType: "application/pdf") { page, count in
                XCTAssertEqual(count, 5)
                seen.append(page)
            }
        }
        _ = try? await task.value
        XCTAssertEqual(seen, [1, 2, 3, 4, 5])

        var cancelledSeen = 0
        var running: Task<ExtractedSourceText, Error>?
        running = Task {
            try await SourceTextExtractor.extract(data: pdf(pages: 5), mediaType: "application/pdf") { _, _ in
                cancelledSeen += 1
                if cancelledSeen == 2 { running?.cancel() }
            }
        }
        do {
            _ = try await running?.value
            XCTFail("A cancelled read must not return a result")
        } catch is CancellationError {
            XCTAssertLessThan(cancelledSeen, 5)
        } catch {
            XCTFail("Unexpected error \(error)")
        }
    }
}
