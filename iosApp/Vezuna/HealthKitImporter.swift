import Foundation
import HealthKit
import VezunaShared

struct HealthSample: Equatable {
    let recordId: String
    /// A shared `VitalMetric` name: HEART_RATE_BPM, STEPS or WEIGHT_KG.
    let metric: String
    let value: Double
    let start: Date
    let end: Date
}

struct HealthSampleBatch: Equatable {
    var heartRate = 0
    var steps = 0
    var weight = 0
    var deleted = 0
}

protocol HealthImporting {
    func authorizeReadOnly() async throws
    func importIncremental() async throws -> HealthSampleBatch
}

/// Persists samples (encrypted, in the linked profile) and returns only after they are durable.
protocol HealthSampleSink {
    func persist(samples: [HealthSample], deletedRecordIds: [String]) async throws
}

struct HealthFetchPage {
    let samples: [HealthSample]
    let deletedRecordIds: [String]
    let anchor: HKQueryAnchor?
}

protocol HealthSampleFetching {
    func fetch(_ identifier: HKQuantityTypeIdentifier, after anchor: HKQueryAnchor?, limit: Int) async throws -> HealthFetchPage
}

protocol HealthAnchorStoring {
    func anchor(typeIdentifier: String) -> HKQueryAnchor?
    func saveAnchor(_ anchor: HKQueryAnchor, typeIdentifier: String) throws
}

final class FileHealthAnchorStore: HealthAnchorStoring {
    private let directory: URL
    private let queue = DispatchQueue(label: "app.vezuna.health-anchors")

    init(directory: URL) throws {
        self.directory = directory
        try AppFileStorage.preparePrivateDirectory(directory)
    }

    func anchor(typeIdentifier: String) -> HKQueryAnchor? {
        queue.sync {
            guard let data = try? Data(contentsOf: file(typeIdentifier: typeIdentifier)) else { return nil }
            return try? NSKeyedUnarchiver.unarchivedObject(ofClass: HKQueryAnchor.self, from: data)
        }
    }

    func saveAnchor(_ anchor: HKQueryAnchor, typeIdentifier: String) throws {
        let data = try NSKeyedArchiver.archivedData(withRootObject: anchor, requiringSecureCoding: true)
        try queue.sync {
            try AppFileStorage.writeAtomically(data, to: file(typeIdentifier: typeIdentifier))
        }
    }

    // Device-wide, not per profile: samples always go to the linked profile. The name differs from the old
    // per-profile files, whose anchors advanced without the samples being stored, so those are never reused.
    private func file(typeIdentifier: String) -> URL {
        directory.appendingPathComponent("device-\(typeIdentifier).anchor")
    }
}

/// Drives one import. The anchor of a batch is saved only after that batch was persisted, so a failed
/// save is retried from the same anchor and nothing is lost.
final class HealthImportRunner {
    static let pageLimit = 2_000
    private static let identifiers: [HKQuantityTypeIdentifier] = [.heartRate, .stepCount, .bodyMass]

    private let fetcher: HealthSampleFetching
    private let anchors: HealthAnchorStoring
    private let sink: HealthSampleSink

    init(fetcher: HealthSampleFetching, anchors: HealthAnchorStoring, sink: HealthSampleSink) {
        self.fetcher = fetcher
        self.anchors = anchors
        self.sink = sink
    }

    func run() async throws -> HealthSampleBatch {
        var total = HealthSampleBatch()
        for identifier in Self.identifiers {
            var anchor = anchors.anchor(typeIdentifier: identifier.rawValue)
            while true {
                let page = try await fetcher.fetch(identifier, after: anchor, limit: Self.pageLimit)
                if !page.samples.isEmpty || !page.deletedRecordIds.isEmpty {
                    try await sink.persist(samples: page.samples, deletedRecordIds: page.deletedRecordIds)
                }
                if let next = page.anchor {
                    try anchors.saveAnchor(next, typeIdentifier: identifier.rawValue)
                    anchor = next
                }
                total.add(identifier, samples: page.samples.count, deleted: page.deletedRecordIds.count)
                if page.samples.count + page.deletedRecordIds.count < Self.pageLimit { break }
            }
        }
        return total
    }
}

extension HealthSampleBatch {
    fileprivate mutating func add(_ identifier: HKQuantityTypeIdentifier, samples: Int, deleted count: Int) {
        switch identifier {
        case .heartRate: heartRate += samples
        case .stepCount: steps += samples
        default: weight += samples
        }
        deleted += count
    }
}

final class HealthKitImporter: HealthImporting, HealthSampleFetching {
    private let store: HKHealthStore
    private let anchors: HealthAnchorStoring
    private let sink: HealthSampleSink

    init(store: HKHealthStore = .init(), anchors: HealthAnchorStoring, sink: HealthSampleSink = KotlinHealthSink()) {
        self.store = store
        self.anchors = anchors
        self.sink = sink
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

    func importIncremental() async throws -> HealthSampleBatch {
        try await HealthImportRunner(fetcher: self, anchors: anchors, sink: sink).run()
    }

    /// History is bounded: 30 days of heart rate and steps (older readings are condensed anyway), one year of weight.
    private static func window(_ identifier: HKQuantityTypeIdentifier) -> NSPredicate {
        let days = identifier == .bodyMass ? 365 : 30
        let start = Calendar.current.date(byAdding: .day, value: -days, to: Date()) ?? Date.distantPast
        return HKQuery.predicateForSamples(withStart: start, end: nil, options: [])
    }

    func fetch(_ identifier: HKQuantityTypeIdentifier, after anchor: HKQueryAnchor?, limit: Int) async throws -> HealthFetchPage {
        guard let type = HKObjectType.quantityType(forIdentifier: identifier) else { throw HealthImportError.unsupportedType }
        let unit: HKUnit
        let metric: String
        switch identifier {
        case .heartRate: unit = HKUnit.count().unitDivided(by: .minute()); metric = "HEART_RATE_BPM"
        case .stepCount: unit = .count(); metric = "STEPS"
        default: unit = .gramUnit(with: .kilo); metric = "WEIGHT_KG"
        }
        return try await withCheckedThrowingContinuation { continuation in
            let query = HKAnchoredObjectQuery(type: type, predicate: Self.window(identifier), anchor: anchor, limit: limit) { _, samples, deleted, newAnchor, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                let mapped = ((samples as? [HKQuantitySample]) ?? []).map {
                    HealthSample(recordId: $0.uuid.uuidString, metric: metric, value: $0.quantity.doubleValue(for: unit), start: $0.startDate, end: $0.endDate)
                }
                continuation.resume(returning: HealthFetchPage(samples: mapped, deletedRecordIds: (deleted ?? []).map { $0.uuid.uuidString }, anchor: newAnchor))
            }
            store.execute(query)
        }
    }
}

/// Hands samples to the shared repository (linked profile, encrypted vault) and resumes once they are saved.
struct KotlinHealthSink: HealthSampleSink {
    private struct Payload: Encodable {
        let recordId: String
        let metric: String
        let value: Double
        let startEpochSeconds: Int64
        let endEpochSeconds: Int64
    }

    func persist(samples: [HealthSample], deletedRecordIds: [String]) async throws {
        let payload = samples.map {
            Payload(recordId: $0.recordId, metric: $0.metric, value: $0.value,
                    startEpochSeconds: Int64($0.start.timeIntervalSince1970), endEpochSeconds: Int64($0.end.timeIntervalSince1970))
        }
        let json = String(decoding: try JSONEncoder().encode(payload), as: UTF8.self)
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            IosRuntime.shared.ingestDeviceVitals(samplesJson: json, deletedRecordIds: deletedRecordIds) { message in
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
