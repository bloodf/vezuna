package app.vezuna.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

actual fun createPlatformVault(storagePath: String): SecureVault = AndroidSecureVault(File(storagePath))

class AndroidSecureVault(private val directory: File) : SecureVault {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override suspend fun encrypt(plainText: ByteArray, associatedData: ByteArray): CipherEnvelope {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associatedData)
        return CipherEnvelope(ALGORITHM, KEY_ALIAS, cipher.iv, cipher.doFinal(plainText), associatedData)
    }

    override suspend fun decrypt(envelope: CipherEnvelope): ByteArray {
        require(envelope.algorithm == ALGORITHM && envelope.keyId == KEY_ALIAS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, envelope.iv))
        cipher.updateAAD(envelope.associatedData)
        return cipher.doFinal(envelope.cipherText)
    }

    override suspend fun write(key: String, ciphertext: ByteArray) {
        directory.mkdirs()
        File(directory, safeName(key)).writeBytes(ciphertext)
    }

    override suspend fun read(key: String): ByteArray? = File(directory, safeName(key)).takeIf { it.isFile }?.readBytes()

    override suspend fun delete(key: String) { File(directory, safeName(key)).delete() }

    private fun key(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun safeName(key: String) = Base64.encodeToString(key.encodeToByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)

    private companion object {
        const val KEY_ALIAS = "app.vezuna.vault.v1"
        const val ALGORITHM = "AES-256-GCM"
    }
}
