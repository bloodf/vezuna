package app.vezuna.ui.records

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual suspend fun renderPdfPage(bytes: ByteArray, index: Int, widthPx: Int): RenderedPdfPage? = withContext(Dispatchers.IO) {
    // PdfRenderer needs a seekable descriptor. The decrypted copy lives in the app-private cache
    // (java.io.tmpdir) only until the descriptor is open, like the OCR path; nothing outside the app can read it.
    val file = File.createTempFile("vezuna-source-", ".pdf").apply { writeBytes(bytes) }
    try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            file.delete()
            PdfRenderer(descriptor).use { renderer ->
                require(renderer.pageCount > 0) { "PDF has no pages" }
                renderer.openPage(index.coerceIn(0, renderer.pageCount - 1)).use { page ->
                    val width = widthPx.coerceIn(1, MAX_PAGE_PX)
                    val height = (width.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceIn(1, MAX_PAGE_PX * 2)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    RenderedPdfPage(bitmap.asImageBitmap(), renderer.pageCount)
                }
            }
        }
    } finally {
        file.delete()
    }
}
