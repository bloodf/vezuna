package com.bloodf.vitahearth

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bloodf.vitahearth.importer.NativeOcrExtractionService
import com.bloodf.vitahearth.importer.OcrPageText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private fun syntheticPdf(): ByteArray {
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
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
        return ByteArrayOutputStream().use { output ->
            document.writeTo(output)
            document.close()
            output.toByteArray()
        }
    }
}
