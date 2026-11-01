package app.vezuna.security

/** AES-256-GCM output: a fresh 12-byte nonce and ciphertext with the 16-byte tag appended. */
internal class AesGcmSealed(val nonce: ByteArray, val cipherTextAndTag: ByteArray)

/**
 * Platform primitives behind passphrase backups. The same known-answer vectors run against every actual,
 * so a backup written on one platform opens on the other.
 */
internal expect fun pbkdf2HmacSha256(passphrase: CharArray, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray

/** [key] must be 32 bytes; the nonce is random. */
internal expect fun aesGcmEncrypt(key: ByteArray, plainText: ByteArray, associatedData: ByteArray): AesGcmSealed

/** Throws when the tag does not verify (wrong key, tampered data or header). */
internal expect fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, cipherTextAndTag: ByteArray, associatedData: ByteArray): ByteArray

internal expect fun secureRandomBytes(size: Int): ByteArray
