package app.vezuna.security

/** AES-256-GCM output: a fresh 12-byte nonce and ciphertext with the 16-byte tag appended. */
internal class AesGcmSealed(val nonce: ByteArray, val cipherTextAndTag: ByteArray)

/**
 * Platform primitives behind passphrase backups. The same known-answer vectors run against every actual,
 * so a backup written on one platform opens on the other.
 */
internal expect fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray

/** Unicode NFC, so the same passphrase typed on any platform or keyboard derives the same key. */
internal expect fun normalizeNfc(value: String): String

/** [key] must be 32 bytes; the nonce is random. */
internal expect fun aesGcmEncrypt(key: ByteArray, plainText: ByteArray, associatedData: ByteArray): AesGcmSealed

/** Throws when the tag does not verify (wrong key, tampered data or header). */
internal expect fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, cipherTextAndTag: ByteArray, associatedData: ByteArray): ByteArray

internal expect fun secureRandomBytes(size: Int): ByteArray
