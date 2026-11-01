import Foundation
import HealthKit

struct HealthSampleBatch {
    let heartRate: [Double]
    let steps: [Double]
    let weightKilograms: [Double]
    let deletedSampleIDs: [UUID]

    var insertedCount: Int { heartRate.count + steps.count + weightKilograms.count }
}

protocol HealthImporting {
    func authorizeReadOnly() async throws
    func importIncremental(profileId: String) async throws -> HealthSampleBatch
}

protocol HealthAnchorStoring {
    func anchor(profileId: String, typeIdentifier: String) -> HKQueryAnchor?
    func saveAnchor(_ anchor: HKQueryAnchor, profileId: String, typeIdentifier: String) throws
}

final class FileHealthAnchorStore: HealthAnchorStoring {
    private let directory: URL
    private let queue = DispatchQueue(label: "app.vezuna.health-anchors")

    init(directory: URL) throws {
        self.directory = directory
        try AppFileStorage.preparePrivateDirectory(directory)
    }

    func anchor(profileId: String, typeIdentifier: String) -> HKQueryAnchor? {
        queue.sync {
            guard let data = try? Data(contentsOf: file(profileId: profileId, typeIdentifier: typeIdentifier)) else { return nil }
            return try? NSKeyedUnarchiver.unarchivedObject(ofClass: HKQueryAnchor.self, from: data)
        }
    }

    func saveAnchor(_ anchor: HKQueryAnchor, profileId: String, typeIdentifier: String) throws {
        let data = try NSKeyedArchiver.archivedData(withRootObject: anchor, requiringSecureCoding: true)
        try queue.sync {
            try AppFileStorage.writeAtomically(data, to: file(profileId: profileId, typeIdentifier: typeIdentifier))
        }
    }

    private func file(profileId: String, typeIdentifier: String) -> URL {
        let safeProfile = Data(profileId.utf8).base64EncodedString().replacingOccurrences(of: "/", with: "_")
        return directory.appendingPathComponent("\(safeProfile)-\(typeIdentifier).anchor")
    }
}

final class HealthKitImporter: HealthImporting {
    private let store: HKHealthStore
    private let anchors: HealthAnchorStoring

    init(store: HKHealthStore = .init(), anchors: HealthAnchorStoring) {
        self.store = store
        self.anchors = anchors
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

    func importIncremental(profileId: String) async throws -> HealthSampleBatch {
        async let heart = query(.heartRate, profileId: profileId)
        async let steps = query(.stepCount, profileId: profileId)
        async let weight = query(.bodyMass, profileId: profileId)
        let results = try await (heart: heart, steps: steps, weight: weight)
        let unitHeart = HKUnit.count().unitDivided(by: .minute())
        let kilograms = HKUnit.gramUnit(with: .kilo)
        return HealthSampleBatch(
            heartRate: results.heart.samples.map { $0.quantity.doubleValue(for: unitHeart) },
            steps: results.steps.samples.map { $0.quantity.doubleValue(for: .count()) },
            weightKilograms: results.weight.samples.map { $0.quantity.doubleValue(for: kilograms) },
            deletedSampleIDs: results.heart.deleted + results.steps.deleted + results.weight.deleted
        )
    }

    private func query(_ identifier: HKQuantityTypeIdentifier, profileId: String) async throws -> (samples: [HKQuantitySample], deleted: [UUID]) {
        guard let type = HKObjectType.quantityType(forIdentifier: identifier) else { throw HealthImportError.unsupportedType }
        let previous = anchors.anchor(profileId: profileId, typeIdentifier: identifier.rawValue)
        let result: (samples: [HKQuantitySample], deleted: [UUID], anchor: HKQueryAnchor?) = try await withCheckedThrowingContinuation { continuation in
            let query = HKAnchoredObjectQuery(type: type, predicate: nil, anchor: previous, limit: HKObjectQueryNoLimit) { _, samples, deleted, anchor, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: (
                        (samples as? [HKQuantitySample]) ?? [],
                        (deleted ?? []).map(\.uuid),
                        anchor
                    ))
                }
            }
            store.execute(query)
        }
        if let anchor = result.anchor {
            try anchors.saveAnchor(anchor, profileId: profileId, typeIdentifier: identifier.rawValue)
        }
        return (result.samples, result.deleted)
    }
}

enum HealthImportError: LocalizedError {
    case unavailable
    case unsupportedType

    var errorDescription: String? {
        switch self {
        case .unavailable: return L10n.text(.healthkit_unavailable)
        case .unsupportedType: return L10n.text(.healthkit_type_unavailable)
        }
    }
}
