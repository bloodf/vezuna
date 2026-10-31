package com.bloodf.vitahearth.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSDataWritingFileProtectionComplete
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionComplete
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

interface IosAesGcm {
    fun encrypt(key: ByteArray, plainText: ByteArray, associatedData: ByteArray): CipherEnvelope
    fun decrypt(key: ByteArray, envelope: CipherEnvelope): ByteArray
}

object IosAesGcmBridge {
    private var implementation: IosAesGcm? = null
    fun install(implementation: IosAesGcm) { this.implementation = implementation }
    fun require(): IosAesGcm = implementation
        ?: throw IllegalStateException("Install the iOS CryptoKit AES-GCM bridge in the application shell")
}

actual fun createPlatformVault(storagePath: String): SecureVault = IosSecureVault(storagePath)

@OptIn(ExperimentalForeignApi::class)
class IosSecureVault(private val directory: String) : SecureVault {
    private val manager = NSFileManager.defaultManager

    override suspend fun encrypt(plainText: ByteArray, associatedData: ByteArray): CipherEnvelope =
        IosAesGcmBridge.require().encrypt(key(), plainText, associatedData).copy(algorithm = ALGORITHM, keyId = KEY_ID)

    override suspend fun decrypt(envelope: CipherEnvelope): ByteArray {
        require(envelope.algorithm == ALGORITHM && envelope.keyId == KEY_ID)
        return IosAesGcmBridge.require().decrypt(key(), envelope)
    }

    override suspend fun write(key: String, ciphertext: ByteArray) {
        prepareDirectory()
        writeProtected(fileFor(key), ciphertext)
    }

    override suspend fun read(key: String): ByteArray? = NSData.dataWithContentsOfFile(fileFor(key))?.toByteArray()

    override suspend fun delete(key: String) { manager.removeItemAtPath(fileFor(key), null) }

    private fun key(): ByteArray {
        prepareDirectory()
        val path = fileFor("master-key")
        NSData.dataWithContentsOfFile(path)?.let { return it.toByteArray() }
        val bytes = ByteArray(32)
        val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, bytes.size.toULong(), it.addressOf(0)) }
        check(status == 0) { "Secure random key generation failed" }
        writeProtected(path, bytes)
        return bytes
    }

    private fun writeProtected(path: String, bytes: ByteArray) {
        check(bytes.toNSData().writeToFile(path, true)) { "Protected file write failed" }
        check(manager.setAttributes(mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionComplete), path, null)) {
            "Could not enable complete file protection"
        }
    }

    private fun prepareDirectory() {
        manager.createDirectoryAtPath(
            directory,
            withIntermediateDirectories = true,
            attributes = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionComplete),
            error = null,
        )
    }

    private fun fileFor(key: String): String = "$directory/${sha256FileName(key)}"

    private fun sha256FileName(key: String): String = com.bloodf.vitahearth.platform.sha256(key.encodeToByteArray())
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun ByteArray.toNSData(): NSData = usePinned {
        NSData.create(bytes = it.addressOf(0), length = size.toULong())
    }

    private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).apply {
        usePinned { platform.posix.memcpy(it.addressOf(0), bytes, length) }
    }

    private companion object {
        const val KEY_ID = "ios-protected-key-v1"
        const val ALGORITHM = "AES-256-GCM-IOS-FILE-PROTECTION"
    }
}
