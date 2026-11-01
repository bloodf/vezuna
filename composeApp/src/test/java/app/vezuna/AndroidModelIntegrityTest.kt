package app.vezuna

import app.vezuna.llm.resolveModelFile
import app.vezuna.llm.verifyStreamingFileIntegrity
import app.vezuna.platform.sha256
import app.vezuna.platform.toHex
import org.junit.Test
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
}
