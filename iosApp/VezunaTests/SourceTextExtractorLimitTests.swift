import PDFKit
import UIKit
import XCTest
@testable import Vezuna

final class SourceTextExtractorLimitTests: XCTestCase {
    private func pdf(pages: Int, embedded: Bool = false) -> Data {
        let bounds = CGRect(x: 0, y: 0, width: 300, height: 400)
        return UIGraphicsPDFRenderer(bounds: bounds).pdfData { context in
            for index in 0..<pages {
                context.beginPage()
                UIColor.white.setFill()
                context.fill(bounds)
                if embedded {
                    ("Page \(index + 1)" as NSString).draw(at: CGPoint(x: 20, y: 20), withAttributes: [.font: UIFont.systemFont(ofSize: 14)])
                }
            }
        }
    }

    func testEmbeddedPagesReportProgressAndPreserveOrder() async throws {
        var seen: [Int] = []
        let result = try await SourceTextExtractor.extract(data: pdf(pages: 3, embedded: true), mediaType: "application/pdf") { page, count in
            XCTAssertEqual(count, 3)
            seen.append(page)
        }
        XCTAssertEqual(seen, [1, 2, 3])
        XCTAssertEqual(result.pages, ["Page 1", "Page 2", "Page 3"])
        XCTAssertFalse(result.usedOfflineOCR)
    }

    func testCancellingEmbeddedReadDoesNotReturn() async {
        var seen: [Int] = []
        var running: Task<ExtractedSourceText, Error>?
        running = Task { @MainActor in
            try await SourceTextExtractor.extract(data: pdf(pages: 5, embedded: true), mediaType: "application/pdf") { page, _ in
                seen.append(page)
                if page == 2 { running?.cancel() }
            }
        }
        do {
            _ = try await running?.value
            XCTFail("A cancelled read must not return a result")
        } catch is CancellationError {
            XCTAssertEqual(seen, [1, 2])
        } catch {
            XCTFail("Unexpected error \(error)")
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
