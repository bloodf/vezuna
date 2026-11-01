import XCTest
import VezunaShared
@testable import Vezuna

/// Exercises the real Keychain on the simulator under a throwaway service name.
final class KeychainKeyStoreTests: XCTestCase {
    private var store: KeychainKeyStore!

    override func setUp() {
        store = KeychainKeyStore(service: "app.vezuna.vault.tests.\(UUID().uuidString)")
    }

    override func tearDown() {
        _ = store.delete()
    }

    func testMissingThenWriteReadBack() {
        XCTAssertEqual(store.read().status, .missing)
        let key = Data((0..<32).map { UInt8($0) })
        XCTAssertTrue(store.write(key: key.kotlinBytes))
        let read = store.read()
        XCTAssertEqual(read.status, .found)
        XCTAssertEqual(read.key?.toData(), key)
    }

    func testWriteReplacesExistingItem() {
        XCTAssertTrue(store.write(key: Data(repeating: 1, count: 32).kotlinBytes))
        XCTAssertTrue(store.write(key: Data(repeating: 2, count: 32).kotlinBytes))
        XCTAssertEqual(store.read().key?.toData(), Data(repeating: 2, count: 32))
    }

    func testDeleteRemovesItemAndIsIdempotent() {
        XCTAssertTrue(store.write(key: Data(repeating: 3, count: 32).kotlinBytes))
        XCTAssertTrue(store.delete())
        XCTAssertEqual(store.read().status, .missing)
        XCTAssertTrue(store.delete())
    }
}
