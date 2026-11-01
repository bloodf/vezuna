import Foundation
import HealthKit
import VezunaShared

struct HealthSampleBatch: Equatable {
    var heartRate = 0
    var steps = 0
    var weight = 0
}

protocol HealthImporting {
    func authorizeReadOnly() async throws
    func importRecent() async throws -> HealthSampleBatch
}

/// Reads HealthKit statistics, not raw samples: `cumulativeSum` de-duplicates overlapping sources for steps, and
/// hourly min/avg/max keeps a year of heart rate small. Each sync re-reads a trailing window chosen by the shared
/// code and replaces it, so there is no anchor to advance; the "synced up to" time is stored together with the data.
/// Weight is sparse and read as individual samples. Values are validated on the Kotlin side.
final class HealthKitImporter: HealthImporting {
    private let store: HKHealthStore

    init(store: HKHealthStore = .init()) {
        self.store = store
    }

    static var supported: Bool { HKHealthStore.isHealthDataAvailable() }

    static let readTypes: Set<HKObjectType> = Set([
        HKObjectType.quantityType(forIdentifier: .heartRate),
        HKObjectType.quantityType(forIdentifier: .stepCount),
        HKObjectType.quantityType(forIdentifier: .bodyMass)
    ].compactMap { $0 })

    func authorizeReadOnly() async throws {
        try await store.requestAuthorization(toShare: [], read: Self.readTypes)
    }

    func importRecent() async throws -> HealthSampleBatch {
        let window = try await syncWindow()
        let from = Date(timeIntervalSince1970: TimeInterval(window.replaceFrom))
        let weightsFrom = Date(timeIntervalSince1970: TimeInterval(window.weightsFrom))
        async let steps = dailySteps(from: from)
        async let heart = hourlyHeartRate(from: from)
        async let weights = weightSamples(from: weightsFrom)
        let batch = Payload(replaceFromEpochSeconds: window.replaceFrom, steps: try await steps, heartRate: try await heart, weights: try await weights)
        try await persist(batch)
        return HealthSampleBatch(heartRate: batch.heartRate.count, steps: batch.steps.count, weight: batch.weights.count)
    }

    private struct Payload: Encodable {
        let replaceFromEpochSeconds: Int64
        let steps: [Steps]
        let heartRate: [Heart]
        let weights: [Weight]
    }
    private struct Steps: Encodable { let dayStartEpochSeconds: Int64; let count: Double }
    private struct Heart: Encodable { let startEpochSeconds: Int64; let min: Double; let avg: Double; let max: Double }
    private struct Weight: Encodable { let recordId: String; let epochSeconds: Int64; let kg: Double }

    private func quantityType(_ identifier: HKQuantityTypeIdentifier) throws -> HKQuantityType {
        guard let type = HKObjectType.quantityType(forIdentifier: identifier) else { throw HealthImportError.unsupportedType }
        return type
    }

    private func collection(_ type: HKQuantityType, options: HKStatisticsOptions, from: Date, interval: DateComponents) async throws -> HKStatisticsCollection {
        try await withCheckedThrowingContinuation { continuation in
            let predicate = HKQuery.predicateForSamples(withStart: from, end: nil, options: [])
            let query = HKStatisticsCollectionQuery(quantityType: type, quantitySamplePredicate: predicate, options: options, anchorDate: from, intervalComponents: interval)
            query.initialResultsHandler = { _, result, error in
                if let result {
                    continuation.resume(returning: result)
                } else {
                    continuation.resume(throwing: error ?? HealthImportError.unsupportedType)
                }
            }
            store.execute(query)
        }
    }

    private func dailySteps(from: Date) async throws -> [Steps] {
        let result = try await collection(try quantityType(.stepCount), options: .cumulativeSum, from: from, interval: DateComponents(day: 1))
        var days: [Steps] = []
        result.enumerateStatistics(from: from, to: Date()) { statistics, _ in
            guard let sum = statistics.sumQuantity() else { return }
            days.append(Steps(dayStartEpochSeconds: Int64(statistics.startDate.timeIntervalSince1970), count: sum.doubleValue(for: .count())))
        }
        return days
    }

    private func hourlyHeartRate(from: Date) async throws -> [Heart] {
        let result = try await collection(try quantityType(.heartRate), options: [.discreteMin, .discreteAverage, .discreteMax], from: from, interval: DateComponents(hour: 1))
        let unit = HKUnit.count().unitDivided(by: .minute())
        var hours: [Heart] = []
        result.enumerateStatistics(from: from, to: Date()) { statistics, _ in
            guard let min = statistics.minimumQuantity(), let avg = statistics.averageQuantity(), let max = statistics.maximumQuantity() else { return }
            hours.append(Heart(
                startEpochSeconds: Int64(statistics.startDate.timeIntervalSince1970),
                min: min.doubleValue(for: unit), avg: avg.doubleValue(for: unit), max: max.doubleValue(for: unit)
            ))
        }
        return hours
    }

    private func weightSamples(from: Date) async throws -> [Weight] {
        let type = try quantityType(.bodyMass)
        let samples: [HKQuantitySample] = try await withCheckedThrowingContinuation { continuation in
            let predicate = HKQuery.predicateForSamples(withStart: from, end: nil, options: [])
            let query = HKSampleQuery(sampleType: type, predicate: predicate, limit: HKObjectQueryNoLimit, sortDescriptors: nil) { _, results, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: (results as? [HKQuantitySample]) ?? [])
                }
            }
            store.execute(query)
        }
        let kilograms = HKUnit.gramUnit(with: .kilo)
        return samples.map { Weight(recordId: $0.uuid.uuidString, epochSeconds: Int64($0.startDate.timeIntervalSince1970), kg: $0.quantity.doubleValue(for: kilograms)) }
    }

    private func syncWindow() async throws -> (replaceFrom: Int64, weightsFrom: Int64) {
        try await withCheckedThrowingContinuation { continuation in
            IosRuntime.shared.vitalsSyncWindow { replaceFrom, weightsFrom, message in
                if let message {
                    continuation.resume(throwing: HealthImportError.persistFailed(message.localized()))
                } else {
                    continuation.resume(returning: (replaceFrom.int64Value, weightsFrom.int64Value))
                }
            }
        }
    }

    /// Returns only after the data is durable in the linked profile's encrypted vitals.
    private func persist(_ payload: Payload) async throws {
        let json = String(decoding: try JSONEncoder().encode(payload), as: UTF8.self)
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            IosRuntime.shared.ingestHealthBatch(batchJson: json) { message in
                if let message {
                    continuation.resume(throwing: HealthImportError.persistFailed(message.localized()))
                } else {
                    continuation.resume()
                }
            }
        }
    }
}

enum HealthImportError: LocalizedError {
    case unavailable
    case unsupportedType
    case persistFailed(String)

    var errorDescription: String? {
        switch self {
        case .unavailable: return L10n.text(.healthkit_unavailable)
        case .unsupportedType: return L10n.text(.healthkit_type_unavailable)
        case .persistFailed(let message): return message
        }
    }
}
