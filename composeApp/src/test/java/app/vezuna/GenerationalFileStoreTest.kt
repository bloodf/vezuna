package app.vezuna

import app.vezuna.security.GenerationalFileStore
import java.io.File
import java.nio.file.Files
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GenerationalFileStoreTest {
    private val directory: File = Files.createTempDirectory("generational").toFile()
    private val store = GenerationalFileStore(directory)

    @Test
    fun interruptedWriteLeavesCurrentSnapshotReadable() {
        store.write("repo", "one".encodeToByteArray())
        // Process died halfway through the next write: only a truncated temp file exists.
        File(directory, "repo.tmp").writeBytes("tw".encodeToByteArray())

        assertContentEquals("one".encodeToByteArray(), store.read("repo"))
    }

    @Test
    fun crashBetweenRenamesFallsBackToPreviousGeneration() {
        store.write("repo", "one".encodeToByteArray())
        store.write("repo", "two".encodeToByteArray())
        File(directory, "repo").delete()

        assertContentEquals("one".encodeToByteArray(), store.read("repo"))
    }

    @Test
    fun writeKeepsExactlyOnePreviousGeneration() {
        listOf("one", "two", "three").forEach { store.write("repo", it.encodeToByteArray()) }

        assertContentEquals("three".encodeToByteArray(), store.read("repo"))
        assertContentEquals("two".encodeToByteArray(), store.readPrevious("repo"))
        assertEquals(setOf("repo", "repo.prev"), directory.list().orEmpty().toSet())
    }

    @Test
    fun deleteRemovesEveryGeneration() {
        store.write("repo", "one".encodeToByteArray())
        store.write("repo", "two".encodeToByteArray())
        store.delete("repo")

        assertNull(store.read("repo"))
        assertEquals(emptyList(), directory.list().orEmpty().toList())
    }

    @Test
    fun quarantineMovesFilesAsideWithoutDeletingThem() {
        store.write("repo", "one".encodeToByteArray())
        val folder = assertNotNull(store.quarantineAll("42"))

        assertNull(store.read("repo"))
        assertTrue(folder.name.startsWith("unreadable"))
        assertContentEquals("one".encodeToByteArray(), File(folder, "repo").readBytes())
    }

    @Test
    fun quarantineCurrentLeavesPreviousGenerationReadable() {
        store.write("repo", "one".encodeToByteArray())
        store.write("repo", "two".encodeToByteArray())

        assertTrue(store.quarantineCurrent("repo", "7"))

        assertContentEquals("one".encodeToByteArray(), store.read("repo"))
        assertContentEquals("two".encodeToByteArray(), File(directory, "unreadable-7/repo").readBytes())
    }
}
