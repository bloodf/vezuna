package app.vezuna.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.Foundation.NSUUID
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

/** Installed by the app shell; without it the vault keeps using the key file (unit tests). */
object IosKeyStoreBridge {
    var keyStore: VaultKeyStore? = null
        private set
    fun install(keyStore: VaultKeyStore) { this.keyStore = keyStore }
}

actual fun createPlatformVault(storagePath: String): SecureVault = IosSecureVault(storagePath)

@OptIn(ExperimentalForeignApi::class)
class IosSecureVault internal constructor(
    private val directory: String,
    keyStore: VaultKeyStore?,
    private val move: (String, String) -> Boolean = { from, to -> NSFileManager.defaultManager.moveItemAtPath(from, to, null) },
) : SecureVault {
    constructor(directory: String) : this(directory, IosKeyStoreBridge.keyStore)
    private val manager = NSFileManager.defaultManager
    private val lock = Mutex()
    private var protectionRelaxed = false

    private val core = KeyFileVaultCore(
        files = IosFileOps(),
        keyPath = fileFor("master-key"),
        algorithm = ALGORITHM,
        keyId = KEY_ID,
        randomKey = ::randomKey,
        seal = { key, plain, aad -> IosAesGcmBridge.require().encrypt(key, plain, aad) },
        open = { key, envelope -> IosAesGcmBridge.require().decrypt(key, envelope) },
        keyStore = keyStore,
    )

    override suspend fun encrypt(plainText: ByteArray, associatedData: ByteArray): CipherEnvelope = lock.withLock {
        requireNoPendingQuarantine()
        prepareDirectory()
        core.encrypt(plainText, associatedData)
    }

    override suspend fun decrypt(envelope: CipherEnvelope): ByteArray = lock.withLock {
        requireNoPendingQuarantine()
        relaxExistingProtection()
        core.decrypt(envelope)
    }

    override suspend fun write(key: String, ciphertext: ByteArray) = lock.withLock {
        requireNoPendingQuarantine()
        prepareDirectory()
        writeProtected(fileFor(key), ciphertext)
    }

    override suspend fun read(key: String): ByteArray? = lock.withLock {
        requireNoPendingQuarantine()
        relaxExistingProtection()
        core.readData(fileFor(key))
    }

    override suspend fun delete(key: String) = lock.withLock {
        requireNoPendingQuarantine()
        manager.removeItemAtPath(fileFor(key), null)
        Unit
    }

    /** File names are hashes, so recovery learns what a file holds from the cleartext associated data of its envelope. */
    override suspend fun storedAssociatedData(): List<ByteArray>? = lock.withLock {
        requireNoPendingQuarantine()
        relaxExistingProtection()
        IosFileOps().dataFiles().mapNotNull { path ->
            // A locked or unreadable file throws instead of silently shortening the list.
            val bytes = core.readData(path) ?: return@mapNotNull null
            runCatching { CipherEnvelopeCodec.decode(bytes).associatedData }.getOrNull()
        }
    }

    /** A pending directory blocks normal access until every file and the key have been preserved. */
    override suspend fun quarantineAll(): Boolean = lock.withLock {
        prepareDirectory()
        if (!core.archiveKey()) return@withLock false
        val target = "$directory/$PENDING"
        if (!manager.fileExistsAtPath(target) && !manager.createDirectoryAtPath(target, withIntermediateDirectories = false, attributes = null, error = null)) return@withLock false
        val names = (manager.contentsOfDirectoryAtPath(directory, null) as? List<*>)
            ?: return@withLock false
        val live = names
            .filterIsInstance<String>()
            .filterNot { it.startsWith(QUARANTINE) || it == PENDING }
        if (!live.all { move("$directory/$it", "$target/$it") }) return@withLock false
        if (!core.deleteKey()) return@withLock false
        move(target, "$directory/$QUARANTINE${NSUUID().UUIDString}")
    }

    private fun requireNoPendingQuarantine() {
        if (manager.fileExistsAtPath("$directory/$PENDING")) throw VaultUnreadableException(VaultFailure.UNAVAILABLE)
    }

    /** Files written by older builds carry `.complete`; relax them so lock-screen dose actions can read them. Retried until every file succeeded. */
    private fun relaxExistingProtection() {
        if (protectionRelaxed) return
        val keyName = fileFor("master-key").substringAfterLast('/')
        protectionRelaxed = (manager.contentsOfDirectoryAtPath(directory, null) as? List<*>).orEmpty().filterIsInstance<String>()
            .filterNot { it.startsWith(QUARANTINE) || it == PENDING || it == keyName }
            .map { manager.setAttributes(mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication), "$directory/$it", null) }
            .all { it }
    }

    private fun randomKey(): ByteArray {
        val bytes = ByteArray(KeyFileVaultCore.KEY_BYTES)
        val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, bytes.size.toULong(), it.addressOf(0)) }
        check(status == 0) { "Secure random key generation failed" }
        return bytes
    }

    private inner class IosFileOps : VaultFileOps {
        override fun exists(path: String): Boolean = manager.fileExistsAtPath(path)
        override fun read(path: String): ByteArray? = NSData.dataWithContentsOfFile(path)?.toByteArray()
        override fun write(path: String, bytes: ByteArray) = writeProtected(path, bytes)
        override fun delete(path: String): Boolean = manager.removeItemAtPath(path, null)
        override fun dataFiles(): List<String> {
            val keyName = fileFor("master-key").substringAfterLast('/')
            return (manager.contentsOfDirectoryAtPath(directory, null) as? List<*>).orEmpty().filterIsInstance<String>()
                .filterNot { it.startsWith(QUARANTINE) || it == PENDING || it == keyName }
                .map { "$directory/$it" }
        }
    }

    private fun writeProtected(path: String, bytes: ByteArray) {
        check(bytes.toNSData().writeToFile(path, true)) { "Protected file write failed" }
        check(manager.setAttributes(mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication), path, null)) {
            "Could not enable file protection"
        }
    }

    private fun prepareDirectory() {
        relaxExistingProtection()
        manager.createDirectoryAtPath(
            directory,
            withIntermediateDirectories = true,
            attributes = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication),
            error = null,
        )
    }

    private fun fileFor(key: String): String = "$directory/${sha256FileName(key)}"

    private fun sha256FileName(key: String): String = app.vezuna.platform.sha256(key.encodeToByteArray())
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    // addressOf(0) throws on an empty array, and a zero-length file is a valid (truncated) file, not a crash.
    private fun ByteArray.toNSData(): NSData =
        if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

    private fun NSData.toByteArray(): ByteArray =
        if (length == 0uL) ByteArray(0) else ByteArray(length.toInt()).apply { usePinned { platform.posix.memcpy(it.addressOf(0), bytes, length) } }

    private companion object {
        const val QUARANTINE = "unreadable-"
        const val PENDING = ".quarantine-pending"
        const val KEY_ID = "ios-protected-key-v1"
        const val ALGORITHM = "AES-256-GCM-IOS-FILE-PROTECTION"
    }
}
