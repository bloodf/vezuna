package app.vezuna.importer

import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BoundedSourceReadTest {
    private class CountingStream(private val size: Int) : InputStream() {
        var consumed = 0
        var closed = false
        override fun read(): Int = if (consumed < size) { consumed++; 0 } else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (consumed == size) return -1
            val count = minOf(len, size - consumed)
            consumed += count
            return count
        }
        override fun close() { closed = true }
    }

    @Test fun acceptsExactLimitAndRejectsOverflow() = runBlocking {
        val limit = 64 * 1024 * 1024
        val exact = CountingStream(limit)
        exact.use { assertEquals(limit, readBoundedSource(it, limit)!!.size) }
        assertEquals(limit, exact.consumed)
        assertTrue(exact.closed)
        val overflow = CountingStream(limit + 100)
        overflow.use { assertNull(readBoundedSource(it, limit)) }
        assertEquals(limit + 1, overflow.consumed)
        assertTrue(overflow.closed)
    }

    @Test fun propagatesFailuresAndClosesOwnedStream() = runBlocking {
        for (failure in listOf(IOException("synthetic"), CancellationException("synthetic"))) {
            var closed = false
            val input = object : InputStream() {
                override fun read(): Int = throw failure
                override fun read(b: ByteArray, off: Int, len: Int): Int = throw failure
                override fun close() { closed = true }
            }
            try {
                input.use { readBoundedSource(it, 10) }
                fail("Expected failure")
            } catch (caught: Exception) {
                assertSame(failure, caught)
            }
            assertTrue(closed)
        }
    }
}
