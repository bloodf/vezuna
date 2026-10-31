package com.bloodf.vitahearth.llm

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AndroidModelFileStore(context: Context) : ModelFileStore {
    private val directory = File(context.noBackupFilesDir, "models")
    private val metadataFile: File get() = File(directory, "installed-models.json")
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true }

    @Serializable
    private data class Metadata(
        val models: List<InstalledModel> = emptyList(),
        val selectedModelId: String? = null,
    )

    override suspend fun installed(): List<InstalledModel> = mutex.withLock {
        readMetadata().models.filter { modelFile(it.pack).isFile }
    }

    override suspend fun write(model: InstalledModel, bytes: ByteArray) = mutex.withLock {
        directory.mkdirs()
        val target = modelFile(model.pack)
        atomicWrite(target, bytes)
        val stored = model.copy(localPath = target.absolutePath)
        val metadata = readMetadata()
        writeMetadata(metadata.copy(models = metadata.models.filterNot { it.pack.modelId == model.pack.modelId } + stored))
    }

    override suspend fun readBytes(model: InstalledModel): ByteArray = mutex.withLock {
        val file = modelFile(model.pack)
        require(file.isFile) { "Installed model file is missing" }
        file.readBytes()
    }

    override suspend fun verifyIntegrity(model: InstalledModel): Boolean = mutex.withLock {
        verifyStreamingFileIntegrity(modelFile(model.pack), model.pack.sizeBytes, model.pack.sha256)
    }

    override suspend fun delete(model: InstalledModel) = mutex.withLock {
        val file = modelFile(model.pack)
        if (file.exists() && !file.delete()) throw IOException("Could not delete model ${model.pack.displayName}")
        val metadata = readMetadata()
        writeMetadata(metadata.copy(
            models = metadata.models.filterNot { it.pack.modelId == model.pack.modelId },
            selectedModelId = metadata.selectedModelId.takeUnless { it == model.pack.modelId },
        ))
    }

    override suspend fun saveSelection(modelId: String?) = mutex.withLock {
        if (modelId != null) require(readMetadata().models.any { it.pack.modelId == modelId }) { "Cannot select an uninstalled model" }
        writeMetadata(readMetadata().copy(selectedModelId = modelId))
    }

    override suspend fun savedSelection(): String? = mutex.withLock { readMetadata().selectedModelId }

    private fun modelFile(pack: ModelPack): File = resolveModelFile(directory, pack.fileName)

    private fun readMetadata(): Metadata {
        if (!metadataFile.isFile) return Metadata()
        return json.decodeFromString(Metadata.serializer(), metadataFile.readText())
    }

    private fun writeMetadata(metadata: Metadata) {
        atomicWrite(metadataFile, json.encodeToString(Metadata.serializer(), metadata).encodeToByteArray())
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        directory.mkdirs()
        val temporary = File(directory, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}

internal fun resolveModelFile(directory: File, fileName: String): File {
    require(fileName.endsWith(".gguf") && !fileName.contains('/') && !fileName.contains('\\')) { "Unsafe model file name" }
    directory.mkdirs()
    val root = directory.canonicalFile
    val file = File(root, fileName).canonicalFile
    require(file.parentFile == root) { "Model path escapes private storage" }
    return file
}

internal fun verifyStreamingFileIntegrity(file: File, expectedSize: Long, expectedSha256: String): Boolean {
    if (!file.isFile || file.length() != expectedSize) return false
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered(DEFAULT_BUFFER_SIZE * 128).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 128)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    val actual = digest.digest().joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    return actual == expectedSha256
}
