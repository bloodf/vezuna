package app.vezuna.importer

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun readBoundedSource(input: InputStream, limit: Int): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var remaining = limit
    while (remaining > 0) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer, 0, minOf(buffer.size, remaining))
        if (count == -1) return output.toByteArray()
        if (count == 0) continue
        output.write(buffer, 0, count)
        remaining -= count
    }
    currentCoroutineContext().ensureActive()
    return if (input.read() == -1) output.toByteArray() else null
}
