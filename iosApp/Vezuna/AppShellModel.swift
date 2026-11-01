import Foundation
import VezunaShared

final class AppShellModel: ObservableObject {
    enum Sheet: Identifiable {
        case importSource, health, export, license, notices, privacy
        var id: Self { self }
    }

    @Published var sheet: Sheet?
    @Published private(set) var ready = false
    @Published private(set) var startupError: String?
    @Published private(set) var catalogError: String?
    @Published private(set) var needsVaultRecovery = false

    let licenseManager = LicenseManager()
    let notifications = MedicationNotificationCoordinator()
    lazy var modelLoader = StoreModelPackLoader(licenseManager: licenseManager)
    private let sourcePicker = ComposeSourcePicker()
    private(set) var healthImporter: HealthKitImporter?
    private var didConfigure = false
    private var vaultPath: String?
    private var modelPath: String?

    func configure() {
        guard !didConfigure else { return }
        didConfigure = true
        do {
            let support = try AppFileStorage.applicationSupportDirectory()
            let vault = support.appendingPathComponent("Vault", isDirectory: true)
            let models = support.appendingPathComponent("Models", isDirectory: true)
            try AppFileStorage.preparePrivateDirectory(vault)
            try AppFileStorage.preparePrivateDirectory(models)
            IosAesGcmBridge.shared.install(implementation: CryptoKitAesGcm())
            IosRuntime.shared.licenseProvider = licenseManager
            IosRuntime.shared.sourcePicker = sourcePicker
            IosRuntime.shared.modelPackLoader = modelLoader
            IosRuntime.shared.repositoryObserver = notifications
            if HealthKitImporter.supported {
                let anchors = try FileHealthAnchorStore(directory: support.appendingPathComponent("HealthAnchors", isDirectory: true))
                healthImporter = HealthKitImporter(anchors: anchors)
            }
            vaultPath = vault.path
            modelPath = models.path
            IosRuntime.shared.configure(vaultPath: vault.path, modelPath: models.path, completion: finishOpening)
        } catch {
            startupError = L10n.text(.storage_open_failed)
        }
    }

    /// Recovery screen actions. `reset` moves the unreadable vault aside (never deletes it) and starts empty.
    func recoverVault(reset: Bool) {
        guard let vaultPath, let modelPath else { return }
        IosRuntime.shared.recoverVault(vaultPath: vaultPath, modelPath: modelPath, reset: reset, completion: finishOpening)
    }

    private func finishOpening(_ error: NativeMessage?) {
        DispatchQueue.main.async {
            if let error {
                self.startupError = error.localized()
                self.needsVaultRecovery = IosRuntime.shared.isVaultRecoveryNeeded()
            } else {
                self.needsVaultRecovery = false
                self.startupError = nil
                self.ready = true
                self.catalogError = IosRuntime.shared.catalogError?.localized()
                Task { await self.notifications.reconcile() }
            }
        }
    }

    func sceneBecameActive() {
        guard ready else { return }
        licenseManager.reconcileCache()
        IosRuntime.shared.refresh()
        Task { await notifications.reconcile() }
    }
}
