import Foundation
import VitaHearthShared

final class AppShellModel: ObservableObject {
    enum Sheet: Identifiable {
        case importSource, health, export, license
        var id: Self { self }
    }

    @Published var sheet: Sheet?
    @Published private(set) var ready = false
    @Published private(set) var startupError: String?
    @Published private(set) var catalogError: String?

    let licenseManager = LicenseManager()
    let notifications = MedicationNotificationCoordinator()
    lazy var modelLoader = StoreModelPackLoader(licenseManager: licenseManager)
    private let sourcePicker = ComposeSourcePicker()
    private(set) var healthImporter: HealthKitImporter?
    private var didConfigure = false

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
            IosRuntime.shared.configure(vaultPath: vault.path, modelPath: models.path) { error in
                DispatchQueue.main.async {
                    if let error {
                        self.startupError = error.localized()
                    } else {
                        self.ready = true
                        self.catalogError = IosRuntime.shared.catalogError?.localized()
                        Task { await self.notifications.reconcile() }
                    }
                }
            }
        } catch {
            startupError = L10n.text(.storage_open_failed)
        }
    }

    func sceneBecameActive() {
        guard ready else { return }
        licenseManager.reconcileCache()
        IosRuntime.shared.refresh()
        Task { await notifications.reconcile() }
    }
}
