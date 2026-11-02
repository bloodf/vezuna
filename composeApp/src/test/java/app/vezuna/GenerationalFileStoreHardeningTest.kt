package app.vezuna

import app.vezuna.security.GenerationalFileStore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GenerationalFileStoreHardeningTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dir get() = File(tmp.root, "vault")
    private fun b(s: String) = s.encodeToByteArray()

    @Test
    fun zeroLengthCurrentFileFallsBackToThePreviousGeneration() {
        val store = GenerationalFileStore(dir)
        store.write("a", b("one"))
        store.write("a", b("two"))
        File(dir, "a").writeBytes(ByteArray(0))

        assertContentEquals(b("one"), store.read("a"))
    }

    @Test
    fun zeroLengthFileWithoutAPreviousGenerationIsReturnedEmptyNotMissing() {
        val store = GenerationalFileStore(dir)
        store.write("a", b("one"))
        File(dir, "a").writeBytes(ByteArray(0))

        assertEquals(0, assertNotNull(store.read("a")).size)
    }

    @Test
    fun namesListCurrentAndPreviousOnceAndSkipTempFiles() {
        val store = GenerationalFileStore(dir)
        store.write("a", b("1"))
        store.write("a", b("2"))
        store.write("b", b("3"))
        File(dir, "c.tmp").writeBytes(b("unfinished"))
        File(dir, "d.prev").writeBytes(b("only previous"))

        assertEquals(setOf("a", "b", "d"), store.names().toSet())
        assertEquals(3, store.names().size)
    }
}
