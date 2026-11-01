package app.vezuna.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
internal actual fun pbkdf2HmacSha256(passphrase: CharArray, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray {
    val password = passphrase.concatToString().encodeToByteArray()
    val derived = ByteArray(keyBytes)
    try {
        require(password.isNotEmpty() && salt.isNotEmpty())
        val status = password.usePinned { pass ->
            salt.asUByteArray().usePinned { saltPin ->
                derived.asUByteArray().usePinned { out ->
                    CCKeyDerivationPBKDF(
                        kCCPBKDF2, pass.addressOf(0), password.size.convert(),
                        saltPin.addressOf(0), salt.size.convert(),
                        kCCPRFHmacAlgSHA256, iterations.convert(),
                        out.addressOf(0), derived.size.convert(),
                    )
                }
            }
        }
        check(status == 0) { "PBKDF2 failed" }
        return derived
    } finally {
        password.fill(0)
    }
}

/** CryptoKit (installed Swift bridge) generates the nonce; the same primitive protects the local vault. */
internal actual fun aesGcmEncrypt(key: ByteArray, plainText: ByteArray, associatedData: ByteArray): AesGcmSealed {
    val sealed = IosAesGcmBridge.require().encrypt(key, plainText, associatedData)
    return AesGcmSealed(sealed.iv, sealed.cipherText)
}

internal actual fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, cipherTextAndTag: ByteArray, associatedData: ByteArray): ByteArray =
    IosAesGcmBridge.require().decrypt(key, CipherEnvelope("AES-256-GCM", "backup", nonce, cipherTextAndTag, associatedData))

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0)) }
    check(status == 0) { "Secure random generation failed" }
    return bytes
}
