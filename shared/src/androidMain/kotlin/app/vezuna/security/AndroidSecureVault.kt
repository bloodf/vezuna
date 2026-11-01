package app.vezuna.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

actual fun createPlatformVault(storagePath: String): SecureVault = AndroidSecureVault(File(storagePath))

class AndroidSecureVault(private val directory: File) : SecureVault {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val files = GenerationalFileStore(directory)

    override suspend fun encrypt(plainText: ByteArray, associatedData: ByteArray): CipherEnvelope {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associatedData)
        return CipherEnvelope(ALGORITHM, KEY_ALIAS, cipher.iv, cipher.doFinal(plainText), associatedData)
    }

    override suspend fun decrypt(envelope: CipherEnvelope): ByteArray {
        if (envelope.algorithm != ALGORITHM || envelope.keyId != KEY_ALIAS) throw VaultUnreadableException(VaultFailure.CORRUPT)
        // Never mint a fresh key while decrypting: a missing alias means the old key is gone for good.
        val secret = existingKey() ?: throw VaultUnreadableException(VaultFailure.KEY_INVALIDATED)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, envelope.iv))
            cipher.updateAAD(envelope.associatedData)
            return cipher.doFinal(envelope.cipherText)
        } catch (error: KeyPermanentlyInvalidatedException) {
            throw VaultUnreadableException(VaultFailure.KEY_INVALIDATED, error)
        } catch (error: GeneralSecurityException) {
            throw VaultUnreadableException(VaultFailure.CORRUPT, error)
        } catch (error: IllegalArgumentException) {
            throw VaultUnreadableException(VaultFailure.CORRUPT, error)
        }
    }

    override suspend fun write(key: String, ciphertext: ByteArray) = files.write(safeName(key), ciphertext)

    override suspend fun read(key: String): ByteArray? = files.read(safeName(key))

    override suspend fun readPrevious(key: String): ByteArray? = files.readPrevious(safeName(key))

    override suspend fun delete(key: String) = files.delete(safeName(key))

    override suspend fun quarantineAll(): Boolean {
        files.quarantineAll(System.currentTimeMillis().toString())
        return true
    }

    override suspend fun discardUnusableKey() {
        val usable = try {
            existingKey()?.let { Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.DECRYPT_MODE, it, GCMParameterSpec(128, ByteArray(12))) }
            true
        } catch (_: KeyPermanentlyInvalidatedException) {
            false
        } catch (_: VaultUnreadableException) {
            false
        }
        if (!usable) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun existingKey(): SecretKey? = try {
        keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    } catch (error: UnrecoverableKeyException) {
        throw VaultUnreadableException(VaultFailure.KEY_INVALIDATED, error)
    }

    private fun key(): SecretKey {
        existingKey()?.let { return it }
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
