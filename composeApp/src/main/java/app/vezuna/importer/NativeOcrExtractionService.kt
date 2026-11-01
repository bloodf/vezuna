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
import kotlinx.coroutines.Dispatchers
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

    suspend fun extractPdf(bytes: ByteArray, displayName: String): OcrExtractionResult = withContext(Dispatchers.Default) {
        if (bytes.isEmpty()) failNative(NativeKey.PDF_EMPTY)
        PDFBoxResourceLoader.init(context.applicationContext)
        val embedded = embeddedPages(bytes)
        val temporary = File.createTempFile("vezuna-ocr-", ".pdf", context.cacheDir).apply { writeBytes(bytes) }
        try {
            ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val pages = mutableListOf<OcrPageText>()
                    for (index in 0 until renderer.pageCount) {
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
        } catch (error: Exception) {
            throw NativeMessageException(nativeMessage(NativeKey.PDF_READ_FAILED, displayName))
        } finally {
            temporary.delete()
        }
    }

    private fun embeddedPages(bytes: ByteArray): List<String> = try {
        PDDocument.load(bytes).use { document ->
            (1..document.numberOfPages).map { page ->
                PDFTextStripper().apply {
                    startPage = page
                    endPage = page
                }.getText(document)
            }
        }
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

    private suspend fun recognize(image: InputImage): String = recognizer.process(image).await().text

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
