package app.vezuna.security

/** Minimal file seam so key-file rules can be tested without Foundation. */
interface VaultFileOps {
    fun exists(path: String): Boolean

    /** Null when the file cannot be read (for example a locked device). */
    fun read(path: String): ByteArray?
    fun write(path: String, bytes: ByteArray)

    /** Moves a file into the unreadable/quarantine area instead of deleting it. False when not possible. */
    fun quarantine(path: String): Boolean = false
}

enum class KeyStoreStatus { FOUND, MISSING, UNAVAILABLE }

/** [UNAVAILABLE] means the item may exist but cannot be read now (for example a locked device). */
class KeyStoreRead(val status: KeyStoreStatus, val key: ByteArray? = null)

/** Secure key slot (the iOS Keychain). Implemented natively and faked in tests. */
interface VaultKeyStore {
    fun read(): KeyStoreRead
    fun write(key: ByteArray): Boolean
    fun delete(): Boolean
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
    private val keyStore: VaultKeyStore? = null,
) {
    private var cachedKey: ByteArray? = null

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

    /**
     * Removes the secure-store key after the caller confirmed a reset. The key is handed to [archive] first so
     * quarantined data stays recoverable. False (nothing deleted) when the key cannot be read or deleted now.
     */
    fun discardKey(archive: (ByteArray) -> Unit): Boolean {
        val store = keyStore ?: return true
        val read = store.read()
        when (read.status) {
            KeyStoreStatus.UNAVAILABLE -> return false
            KeyStoreStatus.FOUND -> {
                archive(read.key ?: return false)
                if (!store.delete()) return false
            }
            KeyStoreStatus.MISSING -> Unit
        }
        cachedKey = null
        return true
    }

    private fun key(createIfAbsent: Boolean): ByteArray {
        val store = keyStore ?: return fileKey(createIfAbsent)
        cachedKey?.let { return it }
        val read = store.read()
        val key = when (read.status) {
            KeyStoreStatus.UNAVAILABLE -> throw VaultUnreadableException(VaultFailure.KEY_UNAVAILABLE)
            KeyStoreStatus.FOUND -> validKey(read.key).also { retireLegacyFile(it) }
            KeyStoreStatus.MISSING -> when {
                files.exists(keyPath) -> migrate(store)
                createIfAbsent -> create(store)
                else -> throw VaultUnreadableException(VaultFailure.KEY_UNAVAILABLE)
            }
        }
        cachedKey = key
        return key
    }

    /** Copies the legacy file key into the store; the file is quarantined only after a verified read-back. */
    private fun migrate(store: VaultKeyStore): ByteArray {
        val key = validKey(files.read(keyPath))
        if (stored(store, key)) files.quarantine(keyPath)
        return key
    }

    /** A store write failure falls back to the key file so the key is never lost; migration retries next launch. */
    private fun create(store: VaultKeyStore): ByteArray {
        val key = randomKey()
        if (!stored(store, key)) files.write(keyPath, key)
        return key
    }

    private fun stored(store: VaultKeyStore, key: ByteArray): Boolean {
        if (!store.write(key)) return false
        val back = store.read()
        return back.status == KeyStoreStatus.FOUND && back.key?.contentEquals(key) == true
    }

    private fun retireLegacyFile(key: ByteArray) {
        if (files.exists(keyPath) && files.read(keyPath)?.contentEquals(key) == true) files.quarantine(keyPath)
    }

    private fun validKey(bytes: ByteArray?): ByteArray {
        if (bytes == null || bytes.size != KEY_BYTES) throw VaultUnreadableException(VaultFailure.KEY_UNAVAILABLE)
        return bytes
    }

    private fun fileKey(createIfAbsent: Boolean): ByteArray {
        if (files.exists(keyPath)) return validKey(files.read(keyPath))
        if (!createIfAbsent) throw VaultUnreadableException(VaultFailure.KEY_UNAVAILABLE)
        return randomKey().also { files.write(keyPath, it) }
    }

    companion object {
        const val KEY_BYTES = 32
    }
}
