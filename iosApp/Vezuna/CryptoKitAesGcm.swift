import CryptoKit
import Foundation
import VezunaShared

struct AESGCMValue {
    let nonce: Data
    let ciphertextAndTag: Data
    let associatedData: Data
}

enum VaultCryptoError: Error {
    case invalidEnvelope
}

enum CryptoKitAESGCM {
    private static let tagLength = 16

    static func encrypt(key: Data, plaintext: Data, associatedData: Data) throws -> AESGCMValue {
        guard key.count == 32 else { throw CryptoKitError.incorrectKeySize }
        let box = try AES.GCM.seal(plaintext, using: SymmetricKey(data: key), authenticating: associatedData)
        return AESGCMValue(nonce: Data(box.nonce), ciphertextAndTag: box.ciphertext + box.tag, associatedData: associatedData)
    }

    static func decrypt(key: Data, value: AESGCMValue) throws -> Data {
        guard key.count == 32 else { throw CryptoKitError.incorrectKeySize }
        guard value.ciphertextAndTag.count >= tagLength else { throw VaultCryptoError.invalidEnvelope }
        let split = value.ciphertextAndTag.count - tagLength
        let box = try AES.GCM.SealedBox(
            nonce: AES.GCM.Nonce(data: value.nonce),
            ciphertext: value.ciphertextAndTag.prefix(split),
            tag: value.ciphertextAndTag.suffix(tagLength)
        )
        return try AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: value.associatedData)
    }
}

/** Failures are thrown to Shared (`@Throws` on `IosAesGcm`), which turns them into the vault recovery state. */
final class CryptoKitAesGcm: NSObject, IosAesGcm {
    func encrypt(key: KotlinByteArray, plainText: KotlinByteArray, associatedData: KotlinByteArray) throws -> CipherEnvelope {
        let encrypted = try CryptoKitAESGCM.encrypt(key: key.toData(), plaintext: plainText.toData(), associatedData: associatedData.toData())
        return CipherEnvelope(
            algorithm: "AES-256-GCM-IOS-FILE-PROTECTION",
            keyId: "ios-protected-key-v1",
            iv: encrypted.nonce.kotlinBytes,
            cipherText: encrypted.ciphertextAndTag.kotlinBytes,
            associatedData: associatedData
        )
    }

    func decrypt(key: KotlinByteArray, envelope: CipherEnvelope) throws -> KotlinByteArray {
        let value = AESGCMValue(
            nonce: envelope.iv.toData(),
            ciphertextAndTag: envelope.cipherText.toData(),
            associatedData: envelope.associatedData.toData()
        )
        return try CryptoKitAESGCM.decrypt(key: key.toData(), value: value).kotlinBytes
    }
}
