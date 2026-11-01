package app.vezuna.security

/** Minimal file seam so key-file rules can be tested without Foundation. */
interface VaultFileOps {
    fun exists(path: String): Boolean

    /** Null when the file cannot be read (for example a locked device). */
    fun read(path: String): ByteArray?
    fun write(path: String, bytes: ByteArray)
}

/**
 * Key-file rules for vaults whose master key lives in a file: a key is created only when no key file
 * exists, and an existing-but-unusable key is a recoverable failure that never triggers regeneration.
 */
class KeyFileVaultCore(
    private val files: VaultFileOps,
    private val keyPath: String,
    private val algorithm: String,
    private val keyId: String,
    private val randomKey: () -> ByteArray,
    private val seal: (key: ByteArray, plainText: ByteArray, associatedData: ByteArray) -> CipherEnvelope,
    private val open: (key: ByteArray, envelope: CipherEnvelope) -> ByteArray,
) {
    fun encrypt(plainText: ByteArray, associatedData: ByteArray): CipherEnvelope =
        seal(key(createIfAbsent = true), plainText, associatedData).copy(algorithm = algorithm, keyId = keyId)

    fun decrypt(envelope: CipherEnvelope): ByteArray {
        if (envelope.algorithm != algorithm || envelope.keyId != keyId) throw VaultUnreadableException(VaultFailure.CORRUPT)
        val key = key(createIfAbsent = false)
        return try {
            open(key, envelope)
        } catch (error: IllegalStateException) {
            throw error
        } catch (error: Exception) {
            throw VaultUnreadableException(VaultFailure.CORRUPT, error)
        }
    }

    /** A file that exists but cannot be read is a locked or unavailable device, never an empty vault. */
    fun readData(path: String): ByteArray? {
        if (!files.exists(path)) return null
        return files.read(path) ?: throw VaultUnreadableException(VaultFailure.UNAVAILABLE)
    }

    private fun key(createIfAbsent: Boolean): ByteArray {
        if (files.exists(keyPath)) {
            val stored = files.read(keyPath)
            if (stored == null || stored.size != KEY_BYTES) throw VaultUnreadableException(VaultFailure.KEY_UNAVAILABLE)
            return stored
        }
        if (!createIfAbsent) throw VaultUnreadableException(VaultFailure.KEY_UNAVAILABLE)
        return randomKey().also { files.write(keyPath, it) }
    }

    companion object {
        const val KEY_BYTES = 32
    }
}
