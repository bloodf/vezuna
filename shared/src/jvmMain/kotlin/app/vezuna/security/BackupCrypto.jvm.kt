package app.vezuna.security

import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private val random = SecureRandom()

/** PBKDF2-HMAC-SHA256 over raw bytes (RFC 8018), so no provider-specific password encoding is involved. */
internal actual fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    val macKey = SecretKeySpec(password, "HmacSHA256")
    mac.init(macKey)
    val hashLength = mac.macLength
    val blocks = (keyBytes + hashLength - 1) / hashLength
    val output = ByteArray(keyBytes)
    for (block in 1..blocks) {
        mac.update(salt)
        mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
        var u = mac.doFinal()
        val t = u.copyOf()
        for (round in 1 until iterations) {
            u = mac.doFinal(u)
            for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
        }
        val offset = (block - 1) * hashLength
        t.copyInto(output, offset, 0, minOf(hashLength, keyBytes - offset))
        t.fill(0)
        u.fill(0)
    }
    return output
}

internal actual fun normalizeNfc(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)

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
