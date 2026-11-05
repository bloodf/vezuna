import BackgroundAssets
import Foundation
import System
import VezunaShared

final class StoreModelPackLoader: NSObject, ObservableObject, NativeModelPackLoader {
    @Published private(set) var lastError: String?

    private let licenseManager: LicenseManager
    // Guards activeRequest and the single in-flight load; Kotlin calls in from arbitrary threads.
    private let lock = NSLock()
    private var activeRequest: NSBundleResourceRequest?
    private var loadInProgress = false

    init(licenseManager: LicenseManager) {
        self.licenseManager = licenseManager
    }

    func loadModelPack(pack: ModelPack, completion: @escaping (String?, NativeMessage?) -> Void) {
        guard licenseManager.isPremiumActive() else {
            finish(completion, result: .failure(ModelPackError.premiumRequired))
            return
        }
        // A pack without an iOS store id cannot be delivered here; reject it before any provider work.
        guard let packId = pack.iosAssetPackId else {
            finish(completion, result: .failure(ModelPackError.packUnavailable))
            return
        }
        guard beginLoad() else {
            finish(completion, result: .failure(ModelPackError.busy))
            return
        }
        Task {
            do {
                let path: String
                if #available(iOS 26.0, *) {
                    path = try await loadManagedAsset(pack, packId: packId)
                } else {
                    path = try await loadOnDemandResource(pack, packId: packId)
                }
                let attributes = try FileManager.default.attributesOfItem(atPath: path)
                guard (attributes[.size] as? NSNumber)?.int64Value == pack.sizeBytes else { throw ModelPackError.sizeMismatch }
                finish(completion, result: .success(path))
            } catch {
                releaseModelPack()
                finish(completion, result: .failure(error))
            }
        }
    }

    @available(iOS 26.0, *)
    private func loadManagedAsset(_ pack: ModelPack, packId: String) async throws -> String {
        let asset = try await AssetPackManager.shared.assetPack(withID: packId)
        try await AssetPackManager.shared.ensureLocalAvailability(of: asset)
        return try AssetPackManager.shared.url(for: FilePath(pack.fileName)).path
    }

    private func loadOnDemandResource(_ pack: ModelPack, packId: String) async throws -> String {
        let request = NSBundleResourceRequest(tags: [packId])
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            request.beginAccessingResources { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            }
        }
        guard let url = Bundle.main.url(forResource: pack.fileName, withExtension: nil) else {
            request.endAccessingResources()
            throw ModelPackError.assetUnavailable(packId)
        }
        // Kept alive so the resource stays on disk while Kotlin streams it; see releaseModelPack().
        lock.lock()
        activeRequest = request
        lock.unlock()
        return url.path
    }

    /// Called by Kotlin after installation finished or failed; ends the resource access and allows the next load.
    func releaseModelPack() {
        lock.lock()
        let request = activeRequest
        activeRequest = nil
        loadInProgress = false
        lock.unlock()
        request?.endAccessingResources()
    }

    private func beginLoad() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if loadInProgress { return false }
        loadInProgress = true
        return true
    }

    private func finish(_ completion: @escaping (String?, NativeMessage?) -> Void, result: Result<String, Error>) {
        DispatchQueue.main.async {
            switch result {
            case .success(let path):
                self.lastError = nil
                completion(path, nil)
            case .failure(let error):
                let message = (error as? ModelPackError)?.bridgeMessage ?? NativeMessage(key: "action_failed", args: [])
                self.lastError = message.localized()
                completion(nil, message)
            }
        }
    }
}

enum ModelPackError: LocalizedError {
    case premiumRequired
    case sizeMismatch
    case assetUnavailable(String)
    /// The catalog entry names no store pack for this platform, so nothing can be delivered.
    case packUnavailable
    case busy

    var bridgeMessage: NativeMessage {
        switch self {
        case .premiumRequired:
            return NativeMessage(key: "model_premium_required", args: [])
        case .sizeMismatch:
            return NativeMessage(key: "model_asset_size_mismatch", args: [])
        case .packUnavailable:
            return NativeMessage(key: "action_failed", args: [])
        case .busy:
            return NativeMessage(key: "action_failed", args: [])
        case .assetUnavailable(let identifier):
            return NativeMessage(key: "model_asset_unavailable", args: [identifier])
        }
    }

    var errorDescription: String? { bridgeMessage.localized() }
}
