package app.vezuna.security

import kotlinx.serialization.Serializable

@Serializable
data class CipherEnvelope(
    val algorithm: String,
    val keyId: String,
    val iv: ByteArray,
    val cipherText: ByteArray,
    val associatedData: ByteArray,
)

interface SecureVault {
    /** Only classified transactional writes may rely on this capability. Legacy callers use a separate no-cursor API. */
    val supportsAcknowledgedWrites: Boolean get() = false
    suspend fun encrypt(plainText: ByteArray, associatedData: ByteArray): CipherEnvelope
    suspend fun decrypt(envelope: CipherEnvelope): ByteArray

    /**
     * Decrypts only an envelope bound to [expectedAssociatedData]. The stored copy is never trusted: a mismatch is
     * rejected as [VaultFailure.CORRUPT] before any key or cipher is touched.
     */
    suspend fun decrypt(envelope: CipherEnvelope, expectedAssociatedData: ByteArray): ByteArray {
        if (!envelope.associatedData.contentEquals(expectedAssociatedData)) throw VaultUnreadableException(VaultFailure.CORRUPT)
        return decrypt(envelope)
    }
    suspend fun write(key: String, ciphertext: ByteArray)
    /** A normal legacy write return is not a durability acknowledgement. Unsupported backends refuse before writing. */
    suspend fun writeAcknowledged(key: String, ciphertext: ByteArray): VaultWriteResult =
        VaultWriteResult.Rejected(IllegalStateException("Acknowledged writes unsupported"))

    /** Re-establishes the visible whole generation's storage acknowledgement without writing new bytes. */
    suspend fun acknowledgeCurrent(key: String): VaultWriteResult =
        VaultWriteResult.Rejected(IllegalStateException("Recovery acknowledgement unsupported"))

    /** Unlike [read], this exposes missing-current recovery as one whole previous generation. */
    suspend fun readGeneration(key: String): VaultGeneration? = read(key)?.let { VaultGeneration(it, false) }
    suspend fun read(key: String): ByteArray?
    suspend fun delete(key: String)

    /**
     * Keys that have stored data (current or previous generation), or null when this vault cannot enumerate them.
     * A key can be listed whose data is unreadable; callers still read it through [read].
     */
    suspend fun listKeys(): List<String>? = null

    /**
     * Associated data of every stored envelope, for vaults whose file names hide the key (iOS hashes them).
     * Null when unsupported. Callers must re-verify anything they derive from it by reading the key normally.
     */
    suspend fun storedAssociatedData(): List<ByteArray>? = null

    /** The generation written before the current one, or null when the vault keeps none. */
    suspend fun readPrevious(key: String): ByteArray? = null

    /**
     * Moves every stored file aside so an unreadable vault can be replaced without destroying evidence.
     * Returns false when the vault cannot preserve its files, in which case nothing is touched.
     */
    suspend fun quarantineAll(): Boolean = false

    /**
     * Moves only the current generation of [key] into quarantine, leaving the previous generation in place.
     * Used right after falling back to the previous generation so a later write cannot rotate corrupt data over it.
     */
    suspend fun quarantineCurrent(key: String): Boolean = false

    /** Drops key material that can no longer decrypt (for example an invalidated Keystore key). No-op when the key works. */
    suspend fun discardUnusableKey() {}
}

data class VaultGeneration(val bytes: ByteArray, val previous: Boolean)

sealed interface VaultWriteResult {
    data object Acknowledged : VaultWriteResult
    data class Rejected(val cause: Throwable) : VaultWriteResult
    data class Indeterminate(val cause: Throwable) : VaultWriteResult
}

/**
 * [UNAVAILABLE] is transient (locked device, Keystore or IO hiccup): retry, never offer a reset for it.
 * [SCHEMA_NEWER]: the data was written by a newer build; only updating the app opens it, so no reset is offered.
 */
enum class VaultFailure { CORRUPT, KEY_INVALIDATED, KEY_UNAVAILABLE, UNAVAILABLE, SCHEMA_NEWER }

/** The vault cannot be opened. Recoverable: callers show the recovery screen instead of crashing. */
class VaultUnreadableException(val failure: VaultFailure, cause: Throwable? = null) :
    Exception("Vault unreadable: $failure", cause)

sealed interface VaultState {
    data object Loading : VaultState
    data object Ready : VaultState
    /** [restoreFailed]: a backup restore began after the reset and did not finish. */
    data class Recovery(val failure: VaultFailure, val restoreFailed: Boolean = false) : VaultState
}

expect fun createPlatformVault(storagePath: String): SecureVault
