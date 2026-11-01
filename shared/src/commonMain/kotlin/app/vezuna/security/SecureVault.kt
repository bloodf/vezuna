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
}

expect fun createPlatformVault(storagePath: String): SecureVault
