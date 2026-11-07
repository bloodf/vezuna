import Foundation
import XCTest
@testable import Vezuna

final class HealthKitImporterLifecycleTests: XCTestCase {
    private let payload = HealthKitImporter.Payload(profileId: "synthetic-a", generation: 7, zoneId: "UTC",
                                                   replaceFromEpochSeconds: 0, steps: [], heartRate: [], weights: [])

    func testInvalidationDuringEncodingPreventsDispatch() async throws {
        var calls = 0
        var persistedGeneration = 7
        var storedBatches = 0
        var importer: HealthKitImporter!
        importer = HealthKitImporter(encode: { _ in
            importer.invalidate()
            return "{}"
        }, ingest: { _, completion in
            calls += 1
            persistedGeneration += 1
            storedBatches += 1
            completion(nil)
        })
        defer { importer = nil }
        do {
            try await importer.persist(payload)
            XCTFail("Invalidated request dispatched")
        } catch is CancellationError {} catch { XCTFail("Unexpected error: \(error)") }
        XCTAssertEqual(calls, 0)
        XCTAssertEqual(persistedGeneration, 7)
        XCTAssertEqual(storedBatches, 0)
    }

    func testCancellationBeforeDispatchPreventsBridgeCall() async throws {
        var calls = 0
        let encoded = DispatchSemaphore(value: 0)
        let resume = DispatchSemaphore(value: 0)
        let importer = HealthKitImporter(encode: { _ in
            encoded.signal()
            guard resume.wait(timeout: .now() + 5) == .success else { throw HealthImportError.unavailable }
            return "{}"
        }, ingest: { _, completion in calls += 1; completion(nil) })
        let batch = payload
        let task = Task.detached { try await importer.persist(batch) }
        XCTAssertEqual(encoded.wait(timeout: .now() + 5), .success)
        task.cancel()
        resume.signal()
        do {
            try await task.value
            XCTFail("Cancelled request dispatched")
        } catch is CancellationError {} catch { XCTFail("Unexpected error: \(error)") }
        XCTAssertEqual(calls, 0)
    }

    func testInvalidationAfterDispatchDoesNotUndoCommitOrHoldLockUntilCompletion() async throws {
        let dispatched = expectation(description: "bridge dispatched")
        var finish: ((String?) -> Void)?
        var durableBatches = 0
        let importer = HealthKitImporter(ingest: { _, completion in
            finish = { message in
                durableBatches += 1
                completion(message)
            }
            dispatched.fulfill()
        })
        let batch = payload
        let task = Task { try await importer.persist(batch) }
        await fulfillment(of: [dispatched], timeout: 5)
        importer.invalidate()
        XCTAssertEqual(durableBatches, 0)
        finish?(nil)
        try await task.value
        XCTAssertEqual(durableBatches, 1)
    }

    func testNormalImporterCanDispatchAgainAfterCompletion() async throws {
        var calls = 0
        let importer = HealthKitImporter(ingest: { _, completion in calls += 1; completion(nil) })
        try await importer.persist(payload)
        try await importer.persist(payload)
        XCTAssertEqual(calls, 2)
    }
}

extension HealthKitImporterLifecycleTests {
    func testWeightPagesReadThroughExactBoundaryAndEmptyFinalPage() async throws {
        let first = HealthKitImporter.Weight(recordId: "a", epochSeconds: 1, kg: 60)
        let second = HealthKitImporter.Weight(recordId: "b", epochSeconds: 2, kg: 61)
        var cursors: [Int?] = []
        let result = try await HealthKitImporter.collectWeightPages(check: {}) { (cursor: Int?) in
            cursors.append(cursor)
            switch cursor {
            case nil: return ([first], [], 1)
            case 1: return ([second], [], 2)
            default: return ([], [], 3)
            }
        }
        XCTAssertEqual(result.map(\.recordId), ["a", "b"])
        XCTAssertEqual(cursors.count, 3)
        XCTAssertNil(cursors[0])
        XCTAssertEqual(cursors[2], 2)
    }

    func testWeightPageCancellationStopsBeforeNextPage() async throws {
        var cancelled = false
        var reads = 0
        do {
            _ = try await HealthKitImporter.collectWeightPages(check: {
                if cancelled { throw CancellationError() }
            }) { (_: Int?) in
                reads += 1
                cancelled = true
                return ([HealthKitImporter.Weight(recordId: "a", epochSeconds: 1, kg: 60)], [], 1)
            }
            XCTFail("Cancellation must stop the read")
        } catch is CancellationError {} catch { XCTFail("Unexpected error: \(error)") }
        XCTAssertEqual(reads, 1)
    }

    func testWeightPageDeletionAndSaveFailureRetryFromBeginning() async throws {
        let weight = HealthKitImporter.Weight(recordId: "a", epochSeconds: 1, kg: 60)
        var starts = 0
        func read() async throws -> [HealthKitImporter.Weight] {
            try await HealthKitImporter.collectWeightPages(check: {}) { (cursor: Int?) in
                if cursor == nil { starts += 1; return ([weight], [], 1) }
                if cursor == 1 { return ([], ["a"], 2) }
                return ([], [], 3)
            }
        }
        let importer = HealthKitImporter(ingest: { _, completion in completion("save failed") })
        let values = try await read()
        XCTAssertTrue(values.isEmpty)
        do {
            try await importer.persist(HealthKitImporter.Payload(profileId: "a", generation: 1, zoneId: "UTC", replaceFromEpochSeconds: 0, steps: [], heartRate: [], weights: values))
            XCTFail("Save must fail")
        } catch HealthImportError.persistFailed {} catch { XCTFail("Unexpected error: \(error)") }
        let retry = try await read()
        XCTAssertTrue(retry.isEmpty)
        XCTAssertEqual(starts, 2)
    }
}
