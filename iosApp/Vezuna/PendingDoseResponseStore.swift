import Foundation

/// A notification action tapped while the runtime was not ready (cold background launch, locked vault).
struct PendingDoseResponse: Codable, Equatable {
    let notificationIdentifier: String
    let action: String
    let profileId: String
    let medicationId: String
    let medicationName: String
    let scheduleId: String
    let scheduledEpochSeconds: Int
    let receivedAt: Date
}

/// Persists responses so a "Taken" tap survives process suspension or termination before the vault opens.
/// The file uses `completeUntilFirstUserAuthentication` so it is writable from a background launch while the device is locked.
final class PendingDoseResponseStore {
    private let url: URL
    private let lock = NSLock()

    init(url: URL) {
        self.url = url
    }

    static func standard() throws -> PendingDoseResponseStore {
        let root = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let directory = root.appendingPathComponent("VezunaPending", isDirectory: true)
        try FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        return PendingDoseResponseStore(url: directory.appendingPathComponent("responses.json"))
    }

    func load() throws -> [PendingDoseResponse] {
        lock.lock(); defer { lock.unlock() }
        return try read()
    }

    /// The same action on the same notification is stored once, so a repeated delegate callback cannot double-record.
    func append(_ response: PendingDoseResponse) throws {
        lock.lock(); defer { lock.unlock() }
        var all = try read()
        guard !all.contains(where: { $0.notificationIdentifier == response.notificationIdentifier && $0.action == response.action }) else { return }
        all.append(response)
        try write(all)
    }

    func remove(_ response: PendingDoseResponse) throws {
        lock.lock(); defer { lock.unlock() }
        try write(try read().filter { $0 != response })
    }

    private func read() throws -> [PendingDoseResponse] {
        guard FileManager.default.fileExists(atPath: url.path) else { return [] }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try decoder.decode([PendingDoseResponse].self, from: Data(contentsOf: url))
    }

    private func write(_ responses: [PendingDoseResponse]) throws {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        try encoder.encode(responses).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        var excluded = URLResourceValues()
        excluded.isExcludedFromBackup = true
        var mutable = url
        try mutable.setResourceValues(excluded)
    }
}
