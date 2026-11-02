package app.vezuna

import app.vezuna.security.GenerationalFileStore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GenerationalFileStoreEdgeCaseTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store(): Pair<GenerationalFileStore, File> {
        val dir = File(tmp.root, "vault")
        return GenerationalFileStore(dir) to dir
    }

    private fun b(s: String) = s.encodeToByteArray()

    @Test
    fun writeReadRoundTripLeavesNoTempFile() {
        val (s, dir) = store()
        assertNull(s.read("a"))
        s.write("a", b("one"))
        assertContentEquals(b("one"), s.read("a"))
        assertNull(s.readPrevious("a"))
        assertFalse(File(dir, "a.tmp").exists())
    }

    @Test
    fun staleTempFromCrashDoesNotBlockNextWrite() {
        val (s, dir) = store()
        s.write("a", b("one"))
        File(dir, "a.tmp").writeBytes(b("garbage"))
        s.write("a", b("two"))
        assertContentEquals(b("two"), s.read("a"))
        assertFalse(File(dir, "a.tmp").exists())
    }

    @Test
    fun quarantineCurrentReturnsFalseWhenNothingToMove() {
        val (s, dir) = store()
        s.write("a", b("x"))
        assertFalse(s.quarantineCurrent("missing", "t1"))
        assertFalse(File(dir, "unreadable-t1").exists())
    }

    @Test
    fun quarantineAllMovesRegularFilesAndReportsFolder() {
        val (s, dir) = store()
        assertNull(s.quarantineAll("t0"))
        s.write("a", b("1")); s.write("a", b("2")); s.write("b", b("3"))
        val folder = assertNotNull(s.quarantineAll("t1"))
        assertEquals(File(dir, "unreadable-t1"), folder)
        assertEquals(setOf("a", "a.prev", "b"), folder.list()!!.toSet())
        assertNull(s.read("a"))
        assertNull(s.read("b"))
    }

    @Test
    fun writeRefusedWhenDirectoryCannotBeCreated() {
        val blocker = tmp.newFile("blocker")
        val s = GenerationalFileStore(File(blocker, "sub"))
        assertFailsWith<java.io.IOException> { s.write("a", b("x")) }
    }

    @Test
    fun quarantineRefusedWhenTargetFolderCannotBeCreated() {
        val (s, dir) = store()
        s.write("a", b("x"))
        File(dir, "unreadable-t1").writeBytes(b("a file in the way"))
        assertFailsWith<IllegalStateException> { s.quarantineCurrent("a", "t1") }
        assertContentEquals(b("x"), s.read("a"))
    }
}
