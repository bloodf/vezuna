package app.vezuna.security

import app.vezuna.localization.L10nKey
import app.vezuna.localization.failMessage

/**
 * Portable backup envelope, all integers big-endian:
 *
 * ```
 * magic    4  "VZBK"
 * version  1  1
 * kdf      1  1 = PBKDF2-HMAC-SHA256
 * iters    4  KDF iterations (>= 600,000)
 * saltLen  1  16
 * salt     n
 * nonceLen 1  12
 * nonce    n
 * cipher   *  AES-256-GCM ciphertext + 16-byte tag
 * ```
 * The passphrase is NFC-normalized and UTF-8 encoded before the KDF.
 *
 * Magic through nonceLen is the GCM associated data and the nonce is part of the GCM computation, so changing
 * any header byte fails authentication.
 */
object PassphraseBackupCodec {
    const val VERSION = 1
    const val KDF_PBKDF2_SHA256 = 1
    const val MIN_ITERATIONS = 600_000
    const val DEFAULT_ITERATIONS = 600_000
    private const val MAX_ITERATIONS = 10_000_000
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val KEY_BYTES = 32
    private const val TAG_BYTES = 16
    private val MAGIC = "VZBK".encodeToByteArray()

    fun isPortable(file: ByteArray): Boolean =
        file.size >= MAGIC.size && MAGIC.indices.all { file[it] == MAGIC[it] }

    fun seal(plainText: ByteArray, passphrase: CharArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS)
        if (!PassphraseStrength.isAcceptable(passphrase)) failMessage(L10nKey.BACKUP_PASSPHRASE_TOO_SHORT)
        val salt = secureRandomBytes(SALT_BYTES)
        val key = deriveKey(passphrase, salt, iterations)
        try {
            val header = header(iterations, salt)
            val sealed = aesGcmEncrypt(key, plainText, header)
            check(sealed.nonce.size == NONCE_BYTES) { "Unexpected nonce size" }
            return header + sealed.nonce + sealed.cipherTextAndTag
        } finally {
            key.fill(0)
        }
    }

    @Throws(Exception::class)
    fun open(file: ByteArray, passphrase: CharArray): ByteArray {
        val parsed = parse(file)
        val key = deriveKey(passphrase, parsed.salt, parsed.iterations)
        try {
            return aesGcmDecrypt(key, parsed.nonce, parsed.cipherTextAndTag, parsed.header)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            failMessage(L10nKey.BACKUP_WRONG_PASSPHRASE)
        } finally {
            key.fill(0)
        }
    }

    /** NFC-normalized, UTF-8 encoded passphrase through byte-based PBKDF2. Shared by every platform. */
    internal fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val password = normalizeNfc(passphrase.concatToString()).encodeToByteArray()
        try {
            return pbkdf2HmacSha256(password, salt, iterations, KEY_BYTES)
        } finally {
            password.fill(0)
        }
    }

    private class Parsed(val iterations: Int, val salt: ByteArray, val nonce: ByteArray, val header: ByteArray, val cipherTextAndTag: ByteArray)

    private fun parse(file: ByteArray): Parsed {
        if (!isPortable(file)) failMessage(L10nKey.BACKUP_NOT_A_BACKUP)
        var pos = MAGIC.size
        fun need(count: Int) { if (pos + count > file.size) failMessage(L10nKey.BACKUP_NOT_A_BACKUP) }
        need(2)
        val version = file[pos++].toInt() and 0xff
        val kdf = file[pos++].toInt() and 0xff
        if (version != VERSION || kdf != KDF_PBKDF2_SHA256) failMessage(L10nKey.BACKUP_UNSUPPORTED_VERSION)
        need(5)
        val iterations = ((file[pos].toInt() and 0xff) shl 24) or ((file[pos + 1].toInt() and 0xff) shl 16) or
            ((file[pos + 2].toInt() and 0xff) shl 8) or (file[pos + 3].toInt() and 0xff)
        pos += 4
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) failMessage(L10nKey.BACKUP_UNSUPPORTED_VERSION)
        val saltLen = file[pos++].toInt() and 0xff
        if (saltLen != SALT_BYTES) failMessage(L10nKey.BACKUP_UNSUPPORTED_VERSION)
        need(saltLen)
        val salt = file.copyOfRange(pos, pos + saltLen)
        pos += saltLen
        need(1)
        val nonceLen = file[pos++].toInt() and 0xff
        if (nonceLen != NONCE_BYTES) failMessage(L10nKey.BACKUP_UNSUPPORTED_VERSION)
        val headerEnd = pos
        need(nonceLen + TAG_BYTES)
        val nonce = file.copyOfRange(pos, pos + nonceLen)
        pos += nonceLen
        return Parsed(iterations, salt, nonce, file.copyOfRange(0, headerEnd), file.copyOfRange(pos, file.size))
    }

    /** Magic through nonce length; the nonce itself is authenticated by GCM, the rest by the associated data. */
    private fun header(iterations: Int, salt: ByteArray): ByteArray = MAGIC + byteArrayOf(
        VERSION.toByte(), KDF_PBKDF2_SHA256.toByte(),
        (iterations ushr 24).toByte(), (iterations ushr 16).toByte(), (iterations ushr 8).toByte(), iterations.toByte(),
        salt.size.toByte(),
    ) + salt + byteArrayOf(NONCE_BYTES.toByte())
}
