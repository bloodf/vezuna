package app.vezuna.ui.records

import androidx.compose.ui.graphics.ImageBitmap

/** One page of a PDF rendered for display, with the file's page count. */
class RenderedPdfPage(val image: ImageBitmap, val pageCount: Int)

/**
 * Renders page [index] (0-based, clamped) of a decrypted PDF at about [widthPx] wide, off the main thread.
 * Null when this platform has no PDF renderer; throws when the file cannot be read as a PDF.
 */
expect suspend fun renderPdfPage(bytes: ByteArray, index: Int, widthPx: Int): RenderedPdfPage?

internal const val MAX_PAGE_PX = 2400
