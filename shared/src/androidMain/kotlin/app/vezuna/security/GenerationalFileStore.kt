package app.vezuna.security

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Crash-safe file writes: data goes to `name.tmp`, is fsynced, and only then renamed over `name`.
 * The file it replaces is kept as `name.prev`, so one previous generation always survives.
 */
class GenerationalFileStore(private val directory: File) {
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
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    /** Moves only the current file into `unreadable-<stamp>/`; the previous generation stays readable. */
    fun quarantineCurrent(name: String, stamp: String): Boolean {
        val current = File(directory, name).takeIf { it.isFile } ?: return false
        val target = File(directory, "$QUARANTINE-$stamp").also { check(it.mkdirs() || it.isDirectory) }
        check(current.renameTo(File(target, name))) { "Could not quarantine $name" }
        syncDirectory()
        return true
    }

    /** Current generation; if a crash hit between the two renames, the previous one stands in. */
    fun read(name: String): ByteArray? =
        File(directory, name).takeIf { it.isFile }?.readBytes() ?: readPrevious(name)

    fun readPrevious(name: String): ByteArray? = File(directory, "$name$PREVIOUS").takeIf { it.isFile }?.readBytes()

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
        const val PREVIOUS = ".prev"
        const val TEMP = ".tmp"
        const val QUARANTINE = "unreadable"
    }
}
