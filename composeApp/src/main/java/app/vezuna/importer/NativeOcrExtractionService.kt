package app.vezuna.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import app.vezuna.NativeKey
import app.vezuna.NativeMessageException
import app.vezuna.failNative
import app.vezuna.nativeMessage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import app.vezuna.ui.entry.ImportFailure
import app.vezuna.ui.entry.ImportFailureReason
import app.vezuna.ui.entry.MAX_PDF_PAGES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.min

data class OcrExtractionResult(
    val text: String,
    val pages: List<OcrPageText>,
    val usedEmbeddedText: Boolean,
)

data class OcrPageText(val pageNumber: Int, val text: String, val source: Source) {
    enum class Source { EMBEDDED_PDF_TEXT, OFFLINE_OCR }
}

/** All extraction is local. The bundled ML Kit recognizer never downloads a model. */
class NativeOcrExtractionService(private val context: Context) : Closeable {
    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun extractImage(bytes: ByteArray): OcrExtractionResult = withContext(Dispatchers.Default) {
        if (bytes.isEmpty()) failNative(NativeKey.IMAGE_EMPTY)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: failNative(NativeKey.IMAGE_UNREADABLE)
        val oriented = try {
            applyExifRotation(bitmap, bytes)
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
        try {
            val text = recognize(oriented)
            OcrExtractionResult(text, listOf(OcrPageText(1, text, OcrPageText.Source.OFFLINE_OCR)), false)
        } finally {
            oriented.recycle()
            if (oriented !== bitmap) bitmap.recycle()
        }
    }

    /**
     * Reads a PDF on the device. More than [MAX_PDF_PAGES] pages fails before any page is rendered. [onProgress] gets
     * (page, pageCount) as each page starts. Cancelling stops between pages and always deletes the temporary copy.
     */
    suspend fun extractPdf(
        bytes: ByteArray,
        displayName: String,
        onProgress: (page: Int, pageCount: Int) -> Unit = { _, _ -> },
    ): OcrExtractionResult = withContext(Dispatchers.Default) {
        if (bytes.isEmpty()) failNative(NativeKey.PDF_EMPTY)
        PDFBoxResourceLoader.init(context.applicationContext)
        // A process kill can leave an earlier decrypted copy behind; remove stale ones (own prefix, older than a minute).
        context.cacheDir.listFiles { f -> f.name.startsWith("vezuna-ocr-") && f.name.endsWith(".pdf") && f.lastModified() < System.currentTimeMillis() - 60_000L }?.forEach { it.delete() }
        val temporary = File.createTempFile("vezuna-ocr-", ".pdf", context.cacheDir).apply { writeBytes(bytes) }
        try {
            ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val pageCount = renderer.pageCount
                    if (pageCount > MAX_PDF_PAGES) throw ImportFailure(ImportFailureReason.TOO_MANY_PAGES, displayName)
                    val embedded = embeddedPages(bytes) { ensureActive() }
                    val pages = mutableListOf<OcrPageText>()
                    for (index in 0 until pageCount) {
                        ensureActive()
                        onProgress(index + 1, pageCount)
                        val digital = embedded.getOrNull(index).orEmpty()
                        if (hasUsefulEmbeddedText(digital)) {
                            pages += OcrPageText(index + 1, digital.trim(), OcrPageText.Source.EMBEDDED_PDF_TEXT)
                        } else {
                            val text = renderAndRecognize(renderer, index)
                            pages += OcrPageText(index + 1, text, OcrPageText.Source.OFFLINE_OCR)
                        }
                    }
                    OcrExtractionResult(
                        text = pages.joinToString("\n\n") { it.text }.trim(),
                        pages = pages,
                        usedEmbeddedText = pages.any { it.source == OcrPageText.Source.EMBEDDED_PDF_TEXT },
                    )
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: ImportFailure) {
            throw error
        } catch (error: Exception) {
            throw NativeMessageException(nativeMessage(NativeKey.PDF_READ_FAILED, displayName))
        } finally {
            temporary.delete()
        }
    }

    private fun embeddedPages(bytes: ByteArray, checkActive: () -> Unit): List<String> = try {
        PDDocument.load(bytes).use { document ->
            (1..document.numberOfPages).map { page ->
                checkActive()
                PDFTextStripper().apply {
                    startPage = page
                    endPage = page
                }.getText(document)
            }
        }
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (_: Exception) {
        emptyList()
    }

    private suspend fun renderAndRecognize(renderer: PdfRenderer, index: Int): String {
        renderer.openPage(index).use { page ->
            val scale = min(2.5f, max(1.5f, 180f / 72f))
            val width = (page.width * scale).toInt().coerceAtMost(2400)
            val height = (page.height * scale).toInt().coerceAtMost(3200)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                return recognize(InputImage.fromBitmap(bitmap, 0))
            } finally {
                bitmap.recycle()
            }
        }
    }

    private suspend fun recognize(bitmap: Bitmap): String = recognize(InputImage.fromBitmap(bitmap, 0))

    /**
     * ML Kit keeps reading the bitmap until its task ends, so the call is not cancelled midway: cancellation lands
     * right after it, before the caller recycles the bitmap.
     */
    private suspend fun recognize(image: InputImage): String {
        val text = withContext(NonCancellable) { recognizer.process(image).await().text }
        currentCoroutineContext().ensureActive()
        return text
    }

    private fun applyExifRotation(original: Bitmap, bytes: ByteArray): Bitmap {
        val rotation = try {
            bytes.inputStream().use { input ->
                when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        } catch (_: Exception) {
            0f
        }
        if (rotation == 0f) return original
        val matrix = android.graphics.Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
    }

    private fun hasUsefulEmbeddedText(text: String): Boolean = text.count { it.isLetterOrDigit() } >= 8

    override fun close() = recognizer.close()
}
