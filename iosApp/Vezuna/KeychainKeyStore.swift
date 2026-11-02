import Foundation
import Security
import VezunaShared

/// Vault master key in the Keychain: this device only, readable after first unlock (lock-screen dose actions).
final class KeychainKeyStore: NSObject, VaultKeyStore {
    static let vaultService = "app.vezuna.vault"
    private static let account = "master-key"
    private let service: String

    init(service: String = KeychainKeyStore.vaultService) {
        self.service = service
    }

    private var query: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: Self.account,
            kSecAttrSynchronizable as String: false,
        ]
    }

    func read() -> KeyStoreRead {
        var request = query
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        switch SecItemCopyMatching(request as CFDictionary, &result) {
        case errSecSuccess:
            guard let data = result as? Data else { return KeyStoreRead(status: .unavailable, key: nil) }
            return KeyStoreRead(status: .found, key: data.kotlinBytes)
        case errSecItemNotFound:
            return KeyStoreRead(status: .missing, key: nil)
        default:
            // errSecInteractionNotAllowed (locked) and anything unexpected: never treat as missing.
            return KeyStoreRead(status: .unavailable, key: nil)
        }
    }

    /// Add-only: an existing item is never replaced, so a false "missing" can never destroy the real key.
    func write(key: KotlinByteArray) -> Bool {
        add(account: Self.account, key: key)
    }

    func archive(key: KotlinByteArray) -> Bool {
        add(account: "\(Self.account)-archived-\(Int(Date().timeIntervalSince1970 * 1000))", key: key)
    }

    private func add(account: String, key: KotlinByteArray) -> Bool {
        var attributes = query
        attributes[kSecAttrAccount as String] = account
        attributes[kSecValueData as String] = key.toData()
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(attributes as CFDictionary, nil) == errSecSuccess
    }

    func delete() -> Bool {
        let status = SecItemDelete(query as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
    }
}
