package app.vezuna.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private val random = SecureRandom()

internal actual fun pbkdf2HmacSha256(passphrase: CharArray, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray {
    val spec = PBEKeySpec(passphrase, salt, iterations, keyBytes * 8)
    try {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

internal actual fun aesGcmEncrypt(key: ByteArray, plainText: ByteArray, associatedData: ByteArray): AesGcmSealed {
    val nonce = secureRandomBytes(12)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    cipher.updateAAD(associatedData)
    return AesGcmSealed(nonce, cipher.doFinal(plainText))
}

internal actual fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, cipherTextAndTag: ByteArray, associatedData: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    cipher.updateAAD(associatedData)
    return cipher.doFinal(cipherTextAndTag)
}

internal actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
