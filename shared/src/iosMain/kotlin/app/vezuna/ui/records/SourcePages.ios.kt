package app.vezuna.ui.records

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.create
import platform.PDFKit.PDFDocument
import platform.PDFKit.kPDFDisplayBoxMediaBox
import platform.UIKit.UIImagePNGRepresentation

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual suspend fun renderPdfPage(bytes: ByteArray, index: Int, widthPx: Int): RenderedPdfPage? = withContext(Dispatchers.Default) {
    require(bytes.isNotEmpty()) { "Empty PDF" }
    val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    val document = requireNotNull(PDFDocument(data = data)) { "Unreadable PDF" }
    val count = document.pageCount.toInt()
    require(count > 0) { "PDF has no pages" }
    val page = requireNotNull(document.pageAtIndex(index.coerceIn(0, count - 1).toULong())) { "Missing PDF page" }
    val (pageWidth, pageHeight) = page.boundsForBox(kPDFDisplayBoxMediaBox).useContents { size.width to size.height }
    val width = widthPx.coerceIn(1, MAX_PAGE_PX).toDouble()
    val height = (width * pageHeight / pageWidth.coerceAtLeast(1.0)).coerceIn(1.0, MAX_PAGE_PX * 2.0)
    val image = page.thumbnailOfSize(CGSizeMake(width, height), kPDFDisplayBoxMediaBox)
    val png = requireNotNull(UIImagePNGRepresentation(image)) { "PDF page could not be drawn" }
    val pngBytes = ByteArray(png.length.toInt()).apply { usePinned { platform.posix.memcpy(it.addressOf(0), png.bytes, png.length) } }
    RenderedPdfPage(pngBytes.decodeToImageBitmap(), count)
}
