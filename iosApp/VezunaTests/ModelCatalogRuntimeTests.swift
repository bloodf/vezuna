import XCTest
import VezunaShared
@testable import Vezuna

/// Opens the real shared runtime inside the app bundle, so the packaged `files/model-packs.json` must be found and parsed.
final class ModelCatalogRuntimeTests: XCTestCase {
    func testBundledCatalogLoadsEmptyWithoutError() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let vault = root.appendingPathComponent("Vault", isDirectory: true)
        let models = root.appendingPathComponent("Models", isDirectory: true)
        try AppFileStorage.preparePrivateDirectory(vault)
        try AppFileStorage.preparePrivateDirectory(models)
        defer { try? FileManager.default.removeItem(at: root) }
        IosAesGcmBridge.shared.install(implementation: CryptoKitAesGcm())

        let opened = expectation(description: "runtime opened")
        var openError: NativeMessage?
        IosRuntime.shared.configure(vaultPath: vault.path, modelPath: models.path) { error in
            openError = error
            opened.fulfill()
        }
        wait(for: [opened], timeout: 60)
        XCTAssertNil(openError)
        XCTAssertNil(IosRuntime.shared.catalogError, "bundled model-packs.json was not found or did not parse")
    }
}
