package app.vezuna.ui.records

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

/** One page of a PDF rendered for display, with the file's page count. */
class RenderedPdfPage(val image: ImageBitmap, val pageCount: Int)

/**
 * Renders page [index] (0-based, clamped) of a decrypted PDF at about [widthPx] wide, off the main thread.
 * Null when this platform has no PDF renderer; throws when the file cannot be read as a PDF.
 */
expect suspend fun renderPdfPage(bytes: ByteArray, index: Int, widthPx: Int): RenderedPdfPage?

internal const val MAX_PAGE_PX = 2400

/** Opens the file the way the viewer will: first page of a PDF, every photo. A platform without a PDF renderer cannot tell, so it passes. */
internal suspend fun storedFileOpens(mediaType: String, pages: List<ByteArray>): Boolean = try {
    if (mediaType.equals("application/pdf", ignoreCase = true)) {
        renderPdfPage(pages.first(), 0, CHECK_WIDTH_PX)
    } else {
        withContext(Dispatchers.Default) { pages.forEach { it.decodeToImageBitmap() } }
    }
    true
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (unreadable: Throwable) {
    false
}

private const val CHECK_WIDTH_PX = 64
