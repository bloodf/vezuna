package app.vezuna

import app.vezuna.llm.installVerifiedStream
import app.vezuna.llm.resolveModelFile
import app.vezuna.llm.verifyStreamingFileIntegrity
import app.vezuna.platform.sha256
import app.vezuna.platform.toHex
import org.junit.Test
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidModelIntegrityTest {
    @Test
    fun streamingDigestHandlesMultipleBuffersAndRejectsSizeOrHashMismatch() {
        val bytes = ByteArray(2 * 1024 * 1024 + 37) { index -> (index % 251).toByte() }
        val file = Files.createTempFile("synthetic-model", ".gguf").toFile()
        try {
            file.writeBytes(bytes)
            val digest = sha256(bytes).toHex()
            assertTrue(verifyStreamingFileIntegrity(file, bytes.size.toLong(), digest))
            assertFalse(verifyStreamingFileIntegrity(file, bytes.size.toLong() - 1, digest))
            assertFalse(verifyStreamingFileIntegrity(file, bytes.size.toLong(), "0".repeat(64)))
        } finally {
            file.delete()
        }
    }

    @Test
    fun modelPathsAreCanonicalAndCannotEscapePrivateStorage() {
        val root = Files.createTempDirectory("model-root").toFile()
        try {
            val resolved = resolveModelFile(root, "local.gguf")
            assertEquals(root.canonicalFile, resolved.parentFile)
            assertTrue(resolved.isAbsolute)
            assertFailsWith<IllegalArgumentException> { resolveModelFile(root, "../escape.gguf") }
            assertFailsWith<IllegalArgumentException> { resolveModelFile(root, "nested/model.gguf") }
            assertFailsWith<IllegalArgumentException> { resolveModelFile(root, "model.txt") }
        } finally {
            root.deleteRecursively()
        }
    }

    /** Serves a deterministic pattern in small chunks and fails on any bulk read, proving incremental consumption. */
    private class IncrementalOnlyStream(private val size: Long) : InputStream() {
        private var position = 0L
        var maxChunk = 0
        override fun read(): Int = if (position >= size) -1 else (position++ % 251).toInt()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= size) return -1
            val n = minOf(len.toLong(), 8192L, size - position).toInt()
            for (i in 0 until n) b[off + i] = (position++ % 251).toByte()
            maxChunk = maxOf(maxChunk, n)
            return n
        }
        override fun readAllBytes(): ByteArray = error("full-buffer read is forbidden")
        override fun readNBytes(len: Int): ByteArray = error("full-buffer read is forbidden")
    }

    private fun expectedDigest(size: Long): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val chunk = ByteArray(8192)
        var pos = 0L
        while (pos < size) {
            val n = minOf(chunk.size.toLong(), size - pos).toInt()
            for (i in 0 until n) chunk[i] = (pos++ % 251).toByte()
            digest.update(chunk, 0, n)
        }
        return digest.digest().toHex()
    }

    @Test
    fun streamingInstallMatchesKnownHashWithoutFullBuffer() {
        val dir = Files.createTempDirectory("install").toFile()
        try {
            val size = 3L * 1024 * 1024 + 11
            val target = java.io.File(dir, "m.gguf")
            installVerifiedStream(IncrementalOnlyStream(size), target, size, expectedDigest(size))
            assertEquals(size, target.length())
            assertEquals(listOf("m.gguf"), dir.list()!!.toList())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun streamingInstallRejectsMismatchRemovesPartialAndKeepsExisting() {
        val dir = Files.createTempDirectory("install").toFile()
        try {
            val target = java.io.File(dir, "m.gguf").apply { writeText("previous") }
            val size = 100_000L
            assertFailsWith<Exception> { installVerifiedStream(IncrementalOnlyStream(size), target, size, "0".repeat(64)) }
            assertFailsWith<Exception> { installVerifiedStream(IncrementalOnlyStream(size), target, size - 1, expectedDigest(size)) }
            assertFailsWith<Exception> { installVerifiedStream(IncrementalOnlyStream(size), target, size + 1, expectedDigest(size)) }
            assertEquals("previous", target.readText())
            assertEquals(listOf("m.gguf"), dir.list()!!.toList())
        } finally {
            dir.deleteRecursively()
        }
    }
}
