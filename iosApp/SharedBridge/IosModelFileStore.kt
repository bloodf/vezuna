package app.vezuna.ios

import app.vezuna.llm.InstalledModel
import app.vezuna.llm.ModelFileStore
import app.vezuna.platform.toHex
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionComplete
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.fclose
import platform.posix.feof
import platform.posix.ferror
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.fread
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosModelFileStore(
    private val directory: String,
    private val premiumActive: () -> Boolean,
    private val json: Json = Json { encodeDefaults = true },
) : ModelFileStore {
    private val manager = NSFileManager.defaultManager
    private val mutex = Mutex()

    init {
        preparePrivateDirectory(directory)
    }

    override suspend fun installed(): List<InstalledModel> = mutex.withLock {
        readIndex().filter { model ->
            val path = modelPath(model)
            manager.fileExistsAtPath(path) && model.localPath == path
        }
    }

    override suspend fun install(model: InstalledModel, sourcePath: String) = mutex.withLock {
        require(model.pack.fileName == model.localPath || model.pack.fileName == model.localPath.substringAfterLast('/'))
        val destination = "$directory/${model.pack.fileName}"
        preparePrivateDirectory(directory)
        val temporary = "$directory/.${model.pack.fileName}.${NSUUID().UUIDString}.tmp"
        try {
            streamVerifiedCopy(sourcePath, temporary, model.pack.sizeBytes, model.pack.sha256)
            protectAndExclude(temporary)
            manager.removeItemAtPath(destination, null)
            check(manager.moveItemAtPath(temporary, destination, null)) { "Could not move verified model pack into place" }
            protectAndExclude(destination)
        } finally {
            manager.removeItemAtPath(temporary, null)
        }
        val stored = model.copy(localPath = destination)
        val models = readIndex().filterNot { it.pack.modelId == model.pack.modelId } + stored
        writePrivateText(indexPath, json.encodeToString(ListSerializer(InstalledModel.serializer()), models))
    }

    /** Copies in 1 MiB chunks while hashing; throws on size, GGUF magic or SHA-256 mismatch. */
    private fun streamVerifiedCopy(source: String, destination: String, expectedSize: Long, expectedSha256: String) {
        val input = fopen(source, "rb") ?: error("Model pack source is unavailable")
        try {
            val output = fopen(destination, "wb") ?: error("Could not create model pack temporary file")
            try {
                memScoped {
                    val context = alloc<CC_SHA256_CTX>()
                    check(CC_SHA256_Init(context.ptr) == 1) { "SHA-256 init failed" }
                    val buffer = ByteArray(1024 * 1024)
                    val header = ByteArray(4)
                    var total = 0L
                    while (true) {
                        val count = buffer.usePinned { pinned ->
                            fread(pinned.addressOf(0), 1uL, buffer.size.toULong(), input)
                        }.toInt()
                        if (count > 0) {
                            total += count
                            check(total <= expectedSize) { "Model pack is larger than expected" }
                            check(buffer.usePinned { CC_SHA256_Update(context.ptr, it.addressOf(0), count.toUInt()) } == 1) { "SHA-256 update failed" }
                            val written = buffer.usePinned { fwrite(it.addressOf(0), 1uL, count.toULong(), output) }.toInt()
                            check(written == count) { "Could not write model pack" }
                            val offset = (total - count).toInt()
                            if (offset < header.size) buffer.copyInto(header, offset, 0, minOf(count, header.size - offset))
                        }
                        if (count < buffer.size) {
                            check(ferror(input) == 0) { "Could not read model pack" }
                            if (feof(input) != 0) break
                        }
                    }
                    check(fflush(output) == 0) { "Could not flush model pack" }
                    check(total == expectedSize) { "Model pack size mismatch" }
                    check(header.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))) { "Model pack is not a GGUF file" }
                    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
                    check(CC_SHA256_Final(digest.usePinned { it.addressOf(0) }, context.ptr) == 1) { "SHA-256 final failed" }
                    check(ByteArray(digest.size) { digest[it].toByte() }.toHex() == expectedSha256) { "Model pack SHA-256 mismatch" }
                }
            } finally {
                fclose(output)
            }
        } finally {
            fclose(input)
        }
    }

    override suspend fun verifyIntegrity(model: InstalledModel): Boolean = mutex.withLock {
        check(premiumActive()) { "Premium AI activation is required to verify a model" }
        val path = modelPath(model)
        val attributes = manager.attributesOfItemAtPath(path, null)
        val fileSize = (attributes?.get(NSFileSize) as? NSNumber)?.longLongValue ?: return@withLock false
        if (fileSize != model.pack.sizeBytes) return@withLock false
        val file = fopen(path, "rb") ?: return@withLock false
        try {
            memScoped {
                val context = alloc<CC_SHA256_CTX>()
                if (CC_SHA256_Init(context.ptr) != 1) return@memScoped false
                val buffer = ByteArray(1024 * 1024)
                val header = ByteArray(4)
                var totalRead = 0L
                while (true) {
                    val count = buffer.usePinned { pinned ->
                        fread(pinned.addressOf(0), 1uL, buffer.size.toULong(), file)
                    }
                    if (count > 0uL) {
                        if (CC_SHA256_Update(context.ptr, buffer.usePinned { it.addressOf(0) }, count.toUInt()) != 1) {
                            return@memScoped false
                        }
                        if (totalRead < header.size) {
                            val headerOffset = totalRead.toInt()
                            val copyCount = minOf(count.toInt(), header.size - headerOffset)
                            buffer.copyInto(header, headerOffset, 0, copyCount)
                        }
                        totalRead += count.toLong()
                    }
                    when {
                        count == buffer.size.toULong() -> continue
                        ferror(file) != 0 -> return@memScoped false
                        feof(file) != 0 -> break
                        else -> return@memScoped false
                    }
                }
                if (totalRead != model.pack.sizeBytes) return@memScoped false
                if (header[0] != 0x47.toByte() || header[1] != 0x47.toByte() || header[2] != 0x55.toByte() || header[3] != 0x46.toByte()) {
                    return@memScoped false
                }
                val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
                if (CC_SHA256_Final(digest.usePinned { it.addressOf(0) }, context.ptr) != 1) return@memScoped false
                ByteArray(digest.size) { digest[it].toByte() }.toHex() == model.pack.sha256
            }
        } finally {
            fclose(file)
        }
    }

    override suspend fun delete(model: InstalledModel) = mutex.withLock {
        manager.removeItemAtPath(modelPath(model), null)
        writePrivateText(indexPath, json.encodeToString(ListSerializer(InstalledModel.serializer()), readIndex().filterNot {
            it.pack.modelId == model.pack.modelId
        }))
    }

    override suspend fun saveSelection(modelId: String?) = mutex.withLock {
        writePrivateText(selectionPath, modelId.orEmpty())
    }

    override suspend fun savedSelection(): String? = mutex.withLock {
        readPrivateText(selectionPath)?.takeIf { it.isNotBlank() }
    }

    private fun readIndex(): List<InstalledModel> {
        val raw = readPrivateText(indexPath) ?: return emptyList()
        return try {
            json.decodeFromString(ListSerializer(InstalledModel.serializer()), raw)
        } catch (error: Throwable) {
            // Invalid metadata must not manufacture installed models or make their bytes usable.
            emptyList()
        }
    }

    private fun modelPath(model: InstalledModel): String {
        val stored = model.localPath
        return if (stored.startsWith("$directory/")) stored else "$directory/${model.pack.fileName}"
    }

    private val indexPath get() = "$directory/models-v1.json"
    private val selectionPath get() = "$directory/selection-v1.txt"

    private fun readPrivateText(path: String): String? =
        NSData.dataWithContentsOfFile(path)?.toByteArray()?.decodeToString()

    private fun writePrivateText(path: String, value: String) {
        preparePrivateDirectory(directory)
        check(value.encodeToByteArray().toNSData().writeToFile(path, true)) { "Protected model metadata write failed" }
        protectAndExclude(path)
    }

    private fun preparePrivateDirectory(path: String) {
        manager.createDirectoryAtPath(
            path,
            withIntermediateDirectories = true,
            attributes = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionComplete),
            error = null,
        )
        protectAndExclude(path)
    }

    private fun protectAndExclude(path: String) {
        check(manager.setAttributes(mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionComplete), path, null)) {
            "Could not protect model storage"
        }
        NSURL.fileURLWithPath(path).setResourceValue(true, NSURLIsExcludedFromBackupKey, null)
    }

    private fun ByteArray.toNSData(): NSData = usePinned {
        NSData.create(bytes = it.addressOf(0), length = size.toULong())
    }

    private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).apply {
        if (isNotEmpty()) usePinned { memcpy(it.addressOf(0), bytes, length) }
    }
}
