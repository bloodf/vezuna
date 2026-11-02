package app.vezuna.security

import kotlinx.serialization.json.Json

/** Minimal file seam so key-file rules can be tested without Foundation. */
interface VaultFileOps {
    fun exists(path: String): Boolean

    /** Null when the file cannot be read (for example a locked device). */
    fun read(path: String): ByteArray?
    fun write(path: String, bytes: ByteArray)

    /** Deletes a file. Only used for the legacy key file once its Keychain copy is proven to work. */
    fun delete(path: String): Boolean = false

    /** Vault data files: everything in the vault directory except the key file and quarantine folders. */
    fun dataFiles(): List<String> = emptyList()
}

enum class KeyStoreStatus { FOUND, MISSING, UNAVAILABLE }

/** [UNAVAILABLE] means the item may exist but cannot be read now (for example a locked device). */
class KeyStoreRead(val status: KeyStoreStatus, val key: ByteArray? = null)

/** Secure key slot (the iOS Keychain). Implemented natively and faked in tests. */
interface VaultKeyStore {
    fun read(): KeyStoreRead

    /** Add-only: false when an item already exists or the write failed. Never replaces a stored key. */
    fun write(key: ByteArray): Boolean

    /** Keeps a copy of [key] under a separate slot so a reset never destroys the only key to quarantined data. */
    fun archive(key: ByteArray): Boolean
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
     * First half of a confirmed reset: copies the stored key into the archive slot. False (change nothing) when
     * the key cannot be read right now or archived. True when archived or when there is no stored key.
     */
    fun archiveKey(): Boolean {
        val store = keyStore ?: return true
        val read = store.read()
        return when (read.status) {
            KeyStoreStatus.UNAVAILABLE -> false
            KeyStoreStatus.MISSING -> true
            KeyStoreStatus.FOUND -> read.key?.let(store::archive) ?: false
        }
    }

    /** Second half of a reset, after the data was moved aside. Re-reads first so a key that appeared is archived, never lost. */
    fun deleteKey(): Boolean {
        cachedKey = null
        val store = keyStore ?: return true
        val read = store.read()
        return when (read.status) {
            KeyStoreStatus.UNAVAILABLE -> false
            KeyStoreStatus.MISSING -> true
            KeyStoreStatus.FOUND -> (read.key?.let(store::archive) ?: false) && store.delete()
        }
    }

    /**
     * Not thread safe by itself: callers serialize access. Cross-instance races are still safe because the
     * store write is add-only and the loser adopts the stored key.
     */
    private fun key(createIfAbsent: Boolean): ByteArray {
        val store = keyStore ?: return fileKey(createIfAbsent)
        cachedKey?.let { return it }
        val read = store.read()
        val key = when (read.status) {
            KeyStoreStatus.UNAVAILABLE -> throw VaultUnreadableException(VaultFailure.KEY_UNAVAILABLE)
            KeyStoreStatus.FOUND -> reconcile(validKey(read.key))
            KeyStoreStatus.MISSING -> when {
                files.exists(keyPath) -> migrate(store)
                createIfAbsent -> create(store)
                else -> throw VaultUnreadableException(VaultFailure.KEY_UNAVAILABLE)
            }
        }
        cachedKey = key
        return key
    }

    /** The store holds [stored]. A leftover legacy file is removed only once the stored key provably decrypts data. */
    private fun reconcile(stored: ByteArray): ByteArray {
        if (!files.exists(keyPath)) return stored
        val legacy = files.read(keyPath)?.takeIf { it.size == KEY_BYTES } ?: return stored
        if (legacy.contentEquals(stored)) {
            if (decrypts(stored) == true) files.delete(keyPath)
            return stored
        }
        // Two different keys: keep whichever decrypts the data, leave both in place.
        return if (decrypts(stored) == false && decrypts(legacy) == true) legacy else stored
    }

    /** Copies the legacy file key into the store; the file goes only after read-back matches and data decrypts. */
    private fun migrate(store: VaultKeyStore): ByteArray {
        val legacy = validKey(files.read(keyPath))
        if (store.write(legacy)) {
            if (readsBack(store, legacy) && decrypts(legacy) == true) files.delete(keyPath)
            return legacy
        }
        return adoptStored(store)?.let(::reconcile) ?: legacy
    }

    /** Refuses to invent a key when encrypted data already exists; a store write failure falls back to the file. */
    private fun create(store: VaultKeyStore): ByteArray {
        if (files.dataFiles().any { files.read(it) == null || envelopeOf(it) != null }) throw VaultUnreadableException(VaultFailure.KEY_UNAVAILABLE)
        val key = randomKey()
        if (store.write(key) && readsBack(store, key)) return key
        adoptStored(store)?.let { return it }
        if (files.exists(keyPath)) return validKey(files.read(keyPath))
        files.write(keyPath, key)
        return key
    }

    /** After a failed add: a stored key (written by a concurrent creator) wins; locked means unknown, never overwrite. */
    private fun adoptStored(store: VaultKeyStore): ByteArray? {
        val read = store.read()
        return when (read.status) {
            KeyStoreStatus.FOUND -> validKey(read.key)
            KeyStoreStatus.UNAVAILABLE -> throw VaultUnreadableException(VaultFailure.KEY_UNAVAILABLE)
            KeyStoreStatus.MISSING -> null
        }
    }

    /** The vault envelope stored at [path], or null for unreadable files and other plaintext files. */
    private fun envelopeOf(path: String): CipherEnvelope? = files.read(path)
        ?.let { runCatching { envelopeJson.decodeFromString(CipherEnvelope.serializer(), it.decodeToString()) }.getOrNull() }
        ?.takeIf { it.algorithm == algorithm && it.keyId == keyId }

    private fun readsBack(store: VaultKeyStore, key: ByteArray): Boolean {
        val back = store.read()
        return back.status == KeyStoreStatus.FOUND && back.key?.contentEquals(key) == true
    }

    /** True when a stored vault envelope decrypts with [key], false when envelopes exist but none do, null when none exist. */
    private fun decrypts(key: ByteArray): Boolean? {
        var seen = false
        for (path in files.dataFiles()) {
            val envelope = envelopeOf(path) ?: continue
            seen = true
            if (runCatching { open(key, envelope) }.isSuccess) return true
        }
        return if (seen) false else null
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

    private val envelopeJson = Json { ignoreUnknownKeys = true }

    companion object {
        const val KEY_BYTES = 32
    }
}
