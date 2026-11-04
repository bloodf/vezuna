import Security
import XCTest
import VezunaShared
@testable import Vezuna

/// Exercises the real Keychain on the simulator under a throwaway service name.
final class KeychainKeyStoreTests: XCTestCase {
    private var store: KeychainKeyStore!
    private let service = "app.vezuna.vault.tests.\(UUID().uuidString)"

    override func setUp() {
        store = KeychainKeyStore(service: service)
    }

    override func tearDown() {
        _ = store.delete()
        // Archive items use other accounts; remove everything under the throwaway service.
        SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service] as CFDictionary)
    }

    func testMissingThenWriteReadBack() {
        XCTAssertEqual(store.read().status, .missing)
        let key = Data((0..<32).map { UInt8($0) })
        XCTAssertTrue(store.write(key: key.kotlinBytes))
        let read = store.read()
        XCTAssertEqual(read.status, .found)
        XCTAssertEqual(read.key?.toData(), key)
    }

    func testSecondWriteFailsAndKeepsFirstKey() {
        XCTAssertTrue(store.write(key: Data(repeating: 1, count: 32).kotlinBytes))
        XCTAssertFalse(store.write(key: Data(repeating: 2, count: 32).kotlinBytes))
        XCTAssertEqual(store.read().key?.toData(), Data(repeating: 1, count: 32))
    }

    func testArchiveKeepsKeyAfterDelete() {
        let key = Data(repeating: 4, count: 32)
        XCTAssertTrue(store.write(key: key.kotlinBytes))
        XCTAssertTrue(store.archive(key: key.kotlinBytes))
        XCTAssertTrue(store.delete())
        XCTAssertEqual(store.read().status, .missing)
        var result: CFTypeRef?
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
        ]
        XCTAssertEqual(SecItemCopyMatching(query as CFDictionary, &result), errSecSuccess)
        XCTAssertEqual(result as? [Data], [key])
        XCTAssertTrue(store.write(key: Data(repeating: 5, count: 32).kotlinBytes))
    }

    func testDeleteRemovesItemAndIsIdempotent() {
        XCTAssertTrue(store.write(key: Data(repeating: 3, count: 32).kotlinBytes))
        XCTAssertTrue(store.delete())
        XCTAssertEqual(store.read().status, .missing)
        XCTAssertTrue(store.delete())
    }
}
