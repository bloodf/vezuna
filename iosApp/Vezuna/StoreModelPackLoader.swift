import BackgroundAssets
import Foundation
import System
import VezunaShared

final class StoreModelPackLoader: NSObject, ObservableObject, NativeModelPackLoader {
    @Published private(set) var lastError: String?

    private let licenseManager: LicenseManager
    private var activeRequest: NSBundleResourceRequest?

    init(licenseManager: LicenseManager) {
        self.licenseManager = licenseManager
    }

    func loadModelPack(pack: ModelPack, completion: @escaping (String?, NativeMessage?) -> Void) {
        guard licenseManager.isPremiumActive() else {
            finish(completion, result: .failure(ModelPackError.premiumRequired))
            return
        }
        Task {
            do {
                let path: String
                if #available(iOS 26.0, *) {
                    path = try await loadManagedAsset(pack)
                } else {
                    path = try await loadOnDemandResource(pack)
                }
                let attributes = try FileManager.default.attributesOfItem(atPath: path)
                guard (attributes[.size] as? NSNumber)?.int64Value == pack.sizeBytes else { throw ModelPackError.sizeMismatch }
                finish(completion, result: .success(path))
            } catch {
                finish(completion, result: .failure(error))
            }
        }
    }

    @available(iOS 26.0, *)
    private func loadManagedAsset(_ pack: ModelPack) async throws -> String {
        let asset = try await AssetPackManager.shared.assetPack(withID: pack.iosAssetPackId)
        try await AssetPackManager.shared.ensureLocalAvailability(of: asset)
        return try AssetPackManager.shared.url(for: FilePath(pack.fileName)).path
    }

    private func loadOnDemandResource(_ pack: ModelPack) async throws -> String {
        activeRequest?.endAccessingResources()
        activeRequest = nil
        let request = NSBundleResourceRequest(tags: [pack.iosAssetPackId])
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
            throw ModelPackError.assetUnavailable(pack.iosAssetPackId)
        }
        // Kept alive so the resource stays on disk while Kotlin streams it; released on the next load.
        activeRequest = request
        return url.path
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

    var bridgeMessage: NativeMessage {
        switch self {
        case .premiumRequired:
            return NativeMessage(key: "model_premium_required", args: [])
        case .sizeMismatch:
            return NativeMessage(key: "model_asset_size_mismatch", args: [])
        case .assetUnavailable(let identifier):
            return NativeMessage(key: "model_asset_unavailable", args: [identifier])
        }
    }

    var errorDescription: String? { bridgeMessage.localized() }
}
