import Foundation
import StoreKit
import VezunaShared

struct CachedLicense: Codable {
    let lastVerifiedAt: Date
}

enum LicenseState: Equatable {
    case inactive
    case activeOfflineUntil(Date)
    case activeVerified
    case verificationFailed(String)
}

struct FeaturePolicy {
    static let recordsAccessible = true
    static let exportAccessible = true

    static func canActivateModel(premiumActive: Bool) -> Bool { premiumActive }
    static func canUseAI(premiumActive: Bool, modelLoaded: Bool) -> Bool { premiumActive && modelLoaded }
}

final class LicenseManager: NSObject, ObservableObject, NativeLicenseProvider {
    /// Offline-first app: a verified license stays valid for 30 days without store contact.
    static let offlineGrace: TimeInterval = 30 * 24 * 60 * 60
    /// Minimum gap between automatic store checks (launch / becoming active).
    static let autoRefreshInterval: TimeInterval = 15 * 60

    @Published private(set) var state: LicenseState = .inactive
    @Published private(set) var refreshing = false

    private let cacheURL: URL?
    private let now: () -> Date
    /// Store verification; replaced in tests so no StoreKit call happens.
    private let verify: () async throws -> Void
    private var lastAutoRefreshAt: Date?

    override init() {
        self.cacheURL = try? AppFileStorage.applicationSupportDirectory().appendingPathComponent("license-cache-v1.json")
        self.now = Date.init
        self.verify = LicenseManager.verifyWithStore
        super.init()
        loadCache()
    }

    init(cacheURL: URL, now: @escaping () -> Date, verify: @escaping () async throws -> Void = LicenseManager.verifyWithStore) {
        self.cacheURL = cacheURL
        self.now = now
        self.verify = verify
        super.init()
        loadCache()
    }

    func isPremiumActive() -> Bool {
        switch state {
        case .activeVerified, .activeOfflineUntil:
            return true
        case .inactive, .verificationFailed:
            return false
        }
    }

    /// Debounced refresh for launch and foreground. Never gates records or exports.
    /// Failures stay quiet: without a cache the state remains `.inactive`, with one the offline grace applies.
    @MainActor
    func refreshIfDue() async {
        guard shouldAutoRefresh(), !refreshing else { return }
        await refresh(manual: false)
        lastAutoRefreshAt = now()
    }

    func shouldAutoRefresh() -> Bool {
        guard let last = lastAutoRefreshAt else { return true }
        let elapsed = now().timeIntervalSince(last)
        return elapsed < 0 || elapsed >= Self.autoRefreshInterval
    }

    /// Explicit user request (License screen): failures are reported as `.verificationFailed`.
    @MainActor
    func refreshFromStore() async {
        guard !refreshing else { return }
        await refresh(manual: true)
    }

    @MainActor
    private func refresh(manual: Bool) async {
        refreshing = true
        defer { refreshing = false }
        do {
            try await verify()
            try saveCache(CachedLicense(lastVerifiedAt: now()))
            state = .activeVerified
        } catch {
            if let cache = readCache(), now().timeIntervalSince(cache.lastVerifiedAt) <= Self.offlineGrace {
                state = .activeOfflineUntil(cache.lastVerifiedAt.addingTimeInterval(Self.offlineGrace))
            } else if manual {
                state = .verificationFailed(error.localizedDescription)
                IosRuntime.shared.lockPremiumFeatures()
            } else {
                state = .inactive
                IosRuntime.shared.lockPremiumFeatures()
            }
        }
    }

    private static func verifyWithStore() async throws {
        switch try await AppTransaction.shared {
        case .verified: return
        case .unverified(_, let error): throw error
        }
    }

    func reconcileCache() {
        guard let cache = readCache() else {
            state = .inactive
            return
        }
        let elapsed = now().timeIntervalSince(cache.lastVerifiedAt)
        if elapsed >= 0 && elapsed <= Self.offlineGrace {
            state = .activeOfflineUntil(cache.lastVerifiedAt.addingTimeInterval(Self.offlineGrace))
        } else {
            state = .inactive
            IosRuntime.shared.lockPremiumFeatures()
        }
    }

    private func loadCache() { reconcileCache() }

    private func readCache() -> CachedLicense? {
        guard let cacheURL, let data = try? Data(contentsOf: cacheURL) else { return nil }
        return try? JSONDecoder().decode(CachedLicense.self, from: data)
    }

    private func saveCache(_ cache: CachedLicense) throws {
        guard let cacheURL else { throw CocoaError(.fileNoSuchFile) }
        try AppFileStorage.writeAtomically(try JSONEncoder().encode(cache), to: cacheURL)
    }
}
