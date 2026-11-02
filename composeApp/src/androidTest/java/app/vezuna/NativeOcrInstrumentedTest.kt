package app.vezuna

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.vezuna.importer.NativeOcrExtractionService
import app.vezuna.importer.OcrPageText
import app.vezuna.ui.entry.ImportFailure
import app.vezuna.ui.entry.ImportFailureReason
import app.vezuna.ui.entry.MAX_PDF_PAGES
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Uses only the committed synthetic image and a PDF generated here from fictional text. */
@RunWith(AndroidJUnit4::class)
class NativeOcrInstrumentedTest {
    @Test
    fun extractsSyntheticCameraImageOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = InstrumentationRegistry.getInstrumentation().context.assets.open("synthetic-lab.png").use { it.readBytes() }
        val service = NativeOcrExtractionService(context)
        try {
            val result = service.extractImage(image)
            assertEquals(OcrPageText.Source.OFFLINE_OCR, result.pages.single().source)
            assertTrue("Bundled OCR did not recognize synthetic lab image", result.text.contains("Hemoglobina", ignoreCase = true))
        } finally {
            service.close()
        }
    }

    @Test
    fun extractsGeneratedSyntheticPdfOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = NativeOcrExtractionService(context)
        try {
            val result = service.extractPdf(syntheticPdf(), "synthetic-generated.pdf")
            assertEquals(1, result.pages.size)
            assertTrue("Synthetic PDF text was not extracted", result.text.contains("Hemoglobina", ignoreCase = true))
        } finally {
            service.close()
        }
    }

    @Test
    fun pdfOverThePageCapFailsTruthfullyBeforeAnyPageIsRead() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = NativeOcrExtractionService(context)
        try {
            service.extractPdf(syntheticPdf(MAX_PDF_PAGES + 1), "too-long.pdf")
            fail("A PDF over the page cap must not be read")
        } catch (failure: ImportFailure) {
            assertEquals(ImportFailureReason.TOO_MANY_PAGES, failure.reason)
        } finally {
            service.close()
        }
        assertFalse("Temporary copy must be deleted", leftoverTemporaryCopies(context))
    }

    @Test
    fun pdfAtThePageCapReportsProgressForEveryPage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = NativeOcrExtractionService(context)
        try {
            val seen = mutableListOf<Pair<Int, Int>>()
            val result = service.extractPdf(syntheticPdf(3), "three.pdf") { page, count -> seen += page to count }
            assertEquals(3, result.pages.size)
            assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), seen)
        } finally {
            service.close()
        }
    }

    @Test
    fun cancellingStopsReadingAndDeletesTheTemporaryCopy() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = NativeOcrExtractionService(context)
        try {
            lateinit var job: Job
            var pagesStarted = 0
            val deferred = async(start = CoroutineStart.LAZY) {
                service.extractPdf(syntheticPdf(MAX_PDF_PAGES), "cancel-me.pdf") { _, _ ->
                    pagesStarted += 1
                    if (pagesStarted == 2) job.cancel()
                }
            }
            job = deferred
            try {
                deferred.await()
                fail("A cancelled read must not return a result")
            } catch (expected: CancellationException) {
                assertTrue("Reading must stop soon after cancel", pagesStarted < MAX_PDF_PAGES)
            }
        } finally {
            service.close()
        }
        assertFalse("Temporary copy must be deleted", leftoverTemporaryCopies(context))
    }

    private fun leftoverTemporaryCopies(context: android.content.Context): Boolean =
        context.cacheDir.listFiles { f -> f.name.startsWith("vezuna-ocr-") }.orEmpty().isNotEmpty()

    private fun syntheticPdf(pages: Int = 1): ByteArray {
        val document = PdfDocument()
        repeat(pages) { number -> addSyntheticPage(document, number + 1) }
        return ByteArrayOutputStream().use { output ->
            document.writeTo(output)
            document.close()
            output.toByteArray()
        }
    }

    private fun addSyntheticPage(document: PdfDocument, number: Int) {
        val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, number).create())
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 24f
            isAntiAlias = true
        }
        page.canvas.drawColor(Color.WHITE)
        page.canvas.drawText("DOCUMENTO SINTETICO", 40f, 80f, paint)
        page.canvas.drawText("Hemoglobina 13,5 g/dL", 40f, 140f, paint)
        page.canvas.drawText("Referencia 12,0 a 16,0 g/dL", 40f, 180f, paint)
        document.finishPage(page)
    }
}
