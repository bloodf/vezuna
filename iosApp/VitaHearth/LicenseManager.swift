import Foundation
import StoreKit
import VitaHearthShared

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
    static let offlineGrace: TimeInterval = 72 * 60 * 60

    @Published private(set) var state: LicenseState = .inactive
    @Published private(set) var refreshing = false

    private let cacheURL: URL?
    private let now: () -> Date

    override init() {
        self.cacheURL = try? AppFileStorage.applicationSupportDirectory().appendingPathComponent("license-cache-v1.json")
        self.now = Date.init
        super.init()
        loadCache()
    }

    init(cacheURL: URL, now: @escaping () -> Date) {
        self.cacheURL = cacheURL
        self.now = now
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

    @MainActor
    func refreshFromStore() async {
        guard !refreshing else { return }
        refreshing = true
        defer { refreshing = false }
        do {
            let result = try await AppTransaction.shared
            switch result {
            case .verified:
                let verifiedAt = now()
                try saveCache(CachedLicense(lastVerifiedAt: verifiedAt))
                state = .activeVerified
            case .unverified(_, let error):
                throw error
            }
        } catch {
            if let cache = readCache(), now().timeIntervalSince(cache.lastVerifiedAt) <= Self.offlineGrace {
                state = .activeOfflineUntil(cache.lastVerifiedAt.addingTimeInterval(Self.offlineGrace))
            } else {
                state = .verificationFailed(error.localizedDescription)
                IosRuntime.shared.lockPremiumFeatures()
            }
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
