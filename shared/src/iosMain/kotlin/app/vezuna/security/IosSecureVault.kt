package app.vezuna.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
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

/** Installed by the app shell; without it the vault keeps using the key file (unit tests). */
object IosKeyStoreBridge {
    var keyStore: VaultKeyStore? = null
        private set
    fun install(keyStore: VaultKeyStore) { this.keyStore = keyStore }
}

actual fun createPlatformVault(storagePath: String): SecureVault = IosSecureVault(storagePath)

@OptIn(ExperimentalForeignApi::class)
class IosSecureVault(private val directory: String) : SecureVault {
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
        keyStore = IosKeyStoreBridge.keyStore,
    )

    override suspend fun encrypt(plainText: ByteArray, associatedData: ByteArray): CipherEnvelope = lock.withLock {
        prepareDirectory()
        core.encrypt(plainText, associatedData)
    }

    override suspend fun decrypt(envelope: CipherEnvelope): ByteArray = lock.withLock {
        relaxExistingProtection()
        core.decrypt(envelope)
    }

    override suspend fun write(key: String, ciphertext: ByteArray) = lock.withLock {
        prepareDirectory()
        // Registered first: a crash between the two writes leaves a listed key without a file, never a file nobody can list.
        register(key)
        writeProtected(fileFor(key), ciphertext)
    }

    override suspend fun read(key: String): ByteArray? = lock.withLock {
        relaxExistingProtection()
        core.readData(fileFor(key))
    }

    override suspend fun delete(key: String) = lock.withLock {
        manager.removeItemAtPath(fileFor(key), null)
        unregister(key)
    }

    /** File names are hashes, so the keys are kept in a sealed registry; keys written before it existed are not listed. */
    override suspend fun listKeys(): List<String>? = lock.withLock {
        relaxExistingProtection()
        if (!manager.fileExistsAtPath(registryPath)) return@withLock emptyList()
        loadRegistry()?.toList()
    }

    private var registryCache: Set<String>? = null

    private fun loadRegistry(): Set<String>? {
        registryCache?.let { return it }
        val bytes = core.readData(registryPath) ?: return emptySet<String>().also { registryCache = it }
        val keys = runCatching {
            val envelope = CipherEnvelopeCodec.decode(bytes)
            require(envelope.associatedData.contentEquals(REGISTRY_AAD))
            Json.decodeFromString(SetSerializer(String.serializer()), core.decrypt(envelope).decodeToString())
        }.getOrNull()
        return keys?.also { registryCache = it }
    }

    private fun storeRegistry(keys: Set<String>) {
        val plain = Json.encodeToString(SetSerializer(String.serializer()), keys).encodeToByteArray()
        writeProtected(registryPath, CipherEnvelopeCodec.encode(core.encrypt(plain, REGISTRY_AAD)))
        registryCache = keys
    }

    private fun register(key: String) {
        // An unreadable registry only costs the recovery listing, never user data, so it is rebuilt from here.
        val known = loadRegistry().orEmpty()
        if (key !in known) storeRegistry(known + key)
    }

    private fun unregister(key: String) {
        val known = loadRegistry() ?: return
        if (key in known) storeRegistry(known - key)
    }

    private val registryPath: String get() = fileFor("key-registry")

    /** All-or-nothing: the Keychain key is archived first, files are moved only then, the key is deleted last. */
    override suspend fun quarantineAll(): Boolean = lock.withLock {
        prepareDirectory()
        if (!core.archiveKey()) return@withLock false
        val names = (manager.contentsOfDirectoryAtPath(directory, null) as? List<*>).orEmpty()
            .filterIsInstance<String>()
            .filterNot { it.startsWith(QUARANTINE) }
        if (names.isNotEmpty()) {
            val target = "$directory/$QUARANTINE${currentInstant().toEpochMilliseconds()}"
            if (!manager.createDirectoryAtPath(target, withIntermediateDirectories = true, attributes = null, error = null)) return@withLock false
            if (!names.all { manager.moveItemAtPath("$directory/$it", "$target/$it", null) }) return@withLock false
        }
        registryCache = null
        core.deleteKey()
    }

    /** Files written by older builds carry `.complete`; relax them so lock-screen dose actions can read them. Retried until every file succeeded. */
    private fun relaxExistingProtection() {
        if (protectionRelaxed) return
        val keyName = fileFor("master-key").substringAfterLast('/')
        protectionRelaxed = (manager.contentsOfDirectoryAtPath(directory, null) as? List<*>).orEmpty().filterIsInstance<String>()
            .filterNot { it.startsWith(QUARANTINE) || it == keyName }
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
                .filterNot { it.startsWith(QUARANTINE) || it == keyName }
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
        val REGISTRY_AAD = "Vezuna key registry v1".encodeToByteArray()
        const val QUARANTINE = "unreadable-"
        const val KEY_ID = "ios-protected-key-v1"
        const val ALGORITHM = "AES-256-GCM-IOS-FILE-PROTECTION"
    }
}
