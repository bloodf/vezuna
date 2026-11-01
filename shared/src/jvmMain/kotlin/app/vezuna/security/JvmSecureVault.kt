package app.vezuna.security

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

actual fun createPlatformVault(storagePath: String): SecureVault = JvmSecureVault(File(storagePath))

class JvmSecureVault(private val directory: File) : SecureVault {
    private val secretFile = File(directory, ".vault-key")

    override suspend fun encrypt(plainText: ByteArray, associatedData: ByteArray): CipherEnvelope {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        cipher.updateAAD(associatedData)
        return CipherEnvelope(ALGORITHM, KEY_ID, iv, cipher.doFinal(plainText), associatedData)
    }

    override suspend fun decrypt(envelope: CipherEnvelope): ByteArray {
        require(envelope.algorithm == ALGORITHM && envelope.keyId == KEY_ID)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, envelope.iv))
        cipher.updateAAD(envelope.associatedData)
        return cipher.doFinal(envelope.cipherText)
    }

    override suspend fun write(key: String, ciphertext: ByteArray) {
        directory.mkdirs()
        File(directory, safeName(key)).writeBytes(ciphertext)
    }

    override suspend fun read(key: String): ByteArray? = File(directory, safeName(key)).takeIf(File::isFile)?.readBytes()
    override suspend fun delete(key: String) { File(directory, safeName(key)).delete() }

    private fun key(): SecretKeySpec {
        directory.mkdirs()
        if (!secretFile.exists()) {
            val generator = KeyGenerator.getInstance("AES")
            generator.init(256)
            secretFile.writeBytes(generator.generateKey().encoded)
        }
        return SecretKeySpec(secretFile.readBytes(), "AES")
    }

    private fun safeName(key: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(key.encodeToByteArray())

    private companion object {
        const val KEY_ID = "jvm-local-test-v1"
        const val ALGORITHM = "AES-256-GCM"
    }
}
