package app.vezuna.security

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Crash-safe file writes: data goes to `name.tmp`, is fsynced, and only then renamed over `name`.
 * The file it replaces is kept as `name.prev`, so one previous generation always survives.
 */
class GenerationalFileStore(
    private val directory: File,
    private val beforeAcknowledgedStage: (WriteStage) -> Unit = {},
    private val acknowledgeDirectory: (File) -> Unit = { path -> FileChannel.open(path.toPath(), StandardOpenOption.READ).use { it.force(true) } },
) {
    enum class WriteStage { BEFORE_TEMP, BEFORE_ROTATION, AFTER_ROTATION, AFTER_INSTALL }

    /** The first rename already changes recovery state. Any later failure must be treated as uncertain. */
    fun writeAcknowledged(name: String, bytes: ByteArray): VaultWriteResult {
        var replacementStarted = false
        return try {
            beforeAcknowledgedStage(WriteStage.BEFORE_TEMP)
            check(directory.mkdirs() || directory.isDirectory) { "Could not create vault directory" }
            val current = File(directory, name)
            val temp = File(directory, "$name$TEMP")
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            beforeAcknowledgedStage(WriteStage.BEFORE_ROTATION)
            replacementStarted = true
            if (current.isFile) check(current.renameTo(File(directory, "$name$PREVIOUS"))) { "Could not keep previous generation" }
            beforeAcknowledgedStage(WriteStage.AFTER_ROTATION)
            check(temp.renameTo(current)) { "Could not commit vault write" }
            beforeAcknowledgedStage(WriteStage.AFTER_INSTALL)
            acknowledgeDirectory(directory)
            VaultWriteResult.Acknowledged
        } catch (error: Exception) {
            if (replacementStarted) VaultWriteResult.Indeterminate(error) else VaultWriteResult.Rejected(error)
        }
    }

    fun readGeneration(name: String): VaultGeneration? =
        File(directory, name).takeIf { it.isFile }?.readBytes()?.let { VaultGeneration(it, false) }
            ?: readPrevious(name)?.let { VaultGeneration(it, true) }

    fun write(name: String, bytes: ByteArray) {
        directory.mkdirs()
        val current = File(directory, name)
        val temp = File(directory, "$name$TEMP")
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (current.isFile) check(current.renameTo(File(directory, "$name$PREVIOUS"))) { "Could not keep previous generation" }
        check(temp.renameTo(current)) { "Could not commit vault write" }
        syncDirectory()
    }

    /** Persists the renames themselves; without it a power cut can undo them after the data was fsynced. */
    private fun syncDirectory() {
        // The renames already happened, so a failed directory sync must not report the write as failed.
        try {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (error: IOException) {
            logger.log(Level.WARNING, "Vault directory sync failed after commit", error)
        }
    }

    /** Moves only the current file into `unreadable-<stamp>/`; the previous generation stays readable. */
    fun quarantineCurrent(name: String, stamp: String): Boolean {
        val current = File(directory, name).takeIf { it.isFile } ?: return false
        val target = File(directory, "$QUARANTINE-$stamp").also { check(it.mkdirs() || it.isDirectory) }
        check(current.renameTo(File(target, name))) { "Could not quarantine $name" }
        syncDirectory()
        return true
    }

    /** Current generation; if a crash hit between the two renames, the previous one stands in. A zero-length file is returned as is so the caller sees corruption. */
    fun read(name: String): ByteArray? =
        File(directory, name).takeIf { it.isFile }?.readBytes() ?: readPrevious(name)

    fun readPrevious(name: String): ByteArray? = File(directory, "$name$PREVIOUS").takeIf { it.isFile }?.readBytes()

    /** Names with a committed current or previous generation; temp files of unfinished writes are not data. */
    fun names(): List<String> = directory.listFiles { file -> file.isFile }.orEmpty()
        .map { it.name }
        .filterNot { it.endsWith(TEMP) }
        .map { it.removeSuffix(PREVIOUS) }
        .distinct()

    fun delete(name: String) {
        listOf(name, "$name$PREVIOUS", "$name$TEMP").forEach { File(directory, it).delete() }
    }

    /** Moves every regular file into a fresh `unreadable-<stamp>` folder. Returns the folder, or null when empty. */
    fun quarantineAll(stamp: String): File? {
        val files = directory.listFiles { file -> file.isFile }.orEmpty()
        if (files.isEmpty()) return null
        val target = File(directory, "$QUARANTINE-$stamp").also { check(it.mkdirs() || it.isDirectory) }
        files.forEach { check(it.renameTo(File(target, it.name))) { "Could not quarantine ${it.name}" } }
        return target
    }

    companion object {
        private val logger: Logger = Logger.getLogger("GenerationalFileStore")
        const val PREVIOUS = ".prev"
        const val TEMP = ".tmp"
        const val QUARANTINE = "unreadable"
    }
}
