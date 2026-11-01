package app.vezuna.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionComplete
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import app.vezuna.platform.currentInstant
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

interface IosAesGcm {
    @Throws(Exception::class)
    fun encrypt(key: ByteArray, plainText: ByteArray, associatedData: ByteArray): CipherEnvelope

    @Throws(Exception::class)
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
        IosAesGcmBridge.require().encrypt(key(createIfAbsent = true), plainText, associatedData)
            .copy(algorithm = ALGORITHM, keyId = KEY_ID)

    override suspend fun decrypt(envelope: CipherEnvelope): ByteArray {
        if (envelope.algorithm != ALGORITHM || envelope.keyId != KEY_ID) throw VaultUnreadableException(VaultFailure.CORRUPT)
        // Existing data with no usable key must never be answered with a freshly generated key.
        val key = key(createIfAbsent = false)
        return try {
            IosAesGcmBridge.require().decrypt(key, envelope)
        } catch (error: IllegalStateException) {
            throw error
        } catch (error: Exception) {
            throw VaultUnreadableException(VaultFailure.CORRUPT, error)
        }
    }

    override suspend fun write(key: String, ciphertext: ByteArray) {
        prepareDirectory()
        writeProtected(fileFor(key), ciphertext)
    }

    override suspend fun read(key: String): ByteArray? = NSData.dataWithContentsOfFile(fileFor(key))?.toByteArray()

    override suspend fun delete(key: String) { manager.removeItemAtPath(fileFor(key), null) }

    override suspend fun quarantineAll(): Boolean {
        prepareDirectory()
        val names = (manager.contentsOfDirectoryAtPath(directory, null) as? List<*>).orEmpty()
            .filterIsInstance<String>()
            .filterNot { it.startsWith(QUARANTINE) }
        if (names.isEmpty()) return true
        val target = "$directory/$QUARANTINE${currentInstant().toEpochMilliseconds()}"
        if (!manager.createDirectoryAtPath(target, withIntermediateDirectories = true, attributes = null, error = null)) return false
        return names.all { manager.moveItemAtPath("$directory/$it", "$target/$it", null) }
    }

    /** A key is created only when no key file exists; an existing but unreadable file is a recoverable failure. */
    private fun key(createIfAbsent: Boolean): ByteArray {
        prepareDirectory()
        val path = fileFor("master-key")
        if (manager.fileExistsAtPath(path)) {
            val stored = NSData.dataWithContentsOfFile(path)?.toByteArray()
            if (stored == null || stored.size != KEY_BYTES) throw VaultUnreadableException(VaultFailure.KEY_UNAVAILABLE)
            return stored
        }
        if (!createIfAbsent) throw VaultUnreadableException(VaultFailure.KEY_UNAVAILABLE)
        val bytes = ByteArray(KEY_BYTES)
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

    private fun sha256FileName(key: String): String = app.vezuna.platform.sha256(key.encodeToByteArray())
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun ByteArray.toNSData(): NSData = usePinned {
        NSData.create(bytes = it.addressOf(0), length = size.toULong())
    }

    private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).apply {
        usePinned { platform.posix.memcpy(it.addressOf(0), bytes, length) }
    }

    private companion object {
        const val KEY_BYTES = 32
        const val QUARANTINE = "unreadable-"
        const val KEY_ID = "ios-protected-key-v1"
        const val ALGORITHM = "AES-256-GCM-IOS-FILE-PROTECTION"
    }
}
