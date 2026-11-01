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
    suspend fun encrypt(plainText: ByteArray, associatedData: ByteArray): CipherEnvelope
    suspend fun decrypt(envelope: CipherEnvelope): ByteArray
    suspend fun write(key: String, ciphertext: ByteArray)
    suspend fun read(key: String): ByteArray?
    suspend fun delete(key: String)

    /** The generation written before the current one, or null when the vault keeps none. */
    suspend fun readPrevious(key: String): ByteArray? = null

    /**
     * Moves every stored file aside so an unreadable vault can be replaced without destroying evidence.
     * Returns false when the vault cannot preserve its files, in which case nothing is touched.
     */
    suspend fun quarantineAll(): Boolean = false

    /** Drops key material that can no longer decrypt (for example an invalidated Keystore key). No-op when the key works. */
    suspend fun discardUnusableKey() {}
}

enum class VaultFailure { CORRUPT, KEY_INVALIDATED, KEY_UNAVAILABLE }

/** The vault cannot be opened. Recoverable: callers show the recovery screen instead of crashing. */
class VaultUnreadableException(val failure: VaultFailure, cause: Throwable? = null) :
    Exception("Vault unreadable: $failure", cause)

sealed interface VaultState {
    data object Loading : VaultState
    data object Ready : VaultState
    data class Recovery(val failure: VaultFailure) : VaultState
}

expect fun createPlatformVault(storagePath: String): SecureVault
