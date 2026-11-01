import BackgroundAssets
import Foundation
import System
import VezunaShared

final class StoreModelPackLoader: NSObject, ObservableObject, NativeModelPackLoader {
    @Published private(set) var lastError: String?

    private let licenseManager: LicenseManager

    init(licenseManager: LicenseManager) {
        self.licenseManager = licenseManager
    }

    func loadModelPack(pack: ModelPack, completion: @escaping (KotlinByteArray?, NativeMessage?) -> Void) {
        guard licenseManager.isPremiumActive() else {
            finish(completion, result: .failure(ModelPackError.premiumRequired))
            return
        }
        Task {
            do {
                let data: Data
                if #available(iOS 26.0, *) {
                    data = try await loadManagedAsset(pack)
                } else {
                    data = try await loadOnDemandResource(pack)
                }
                guard data.count == Int(pack.sizeBytes) else { throw ModelPackError.sizeMismatch }
                finish(completion, result: .success(data))
            } catch {
                finish(completion, result: .failure(error))
            }
        }
    }

    @available(iOS 26.0, *)
    private func loadManagedAsset(_ pack: ModelPack) async throws -> Data {
        let asset = try await AssetPackManager.shared.assetPack(withID: pack.iosAssetPackId)
        try await AssetPackManager.shared.ensureLocalAvailability(of: asset)
        return try AssetPackManager.shared.contents(at: FilePath(pack.fileName))
    }

    private func loadOnDemandResource(_ pack: ModelPack) async throws -> Data {
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
        defer { request.endAccessingResources() }
        guard let url = Bundle.main.url(forResource: pack.fileName, withExtension: nil) else {
            throw ModelPackError.assetUnavailable(pack.iosAssetPackId)
        }
        return try Data(contentsOf: url, options: .mappedIfSafe)
    }

    private func finish(_ completion: @escaping (KotlinByteArray?, NativeMessage?) -> Void, result: Result<Data, Error>) {
        DispatchQueue.main.async {
            switch result {
            case .success(let data):
                self.lastError = nil
                completion(data.kotlinBytes, nil)
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
