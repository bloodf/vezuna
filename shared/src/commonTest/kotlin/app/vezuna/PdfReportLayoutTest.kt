package app.vezuna

import app.vezuna.domain.RangeAssessment
import app.vezuna.domain.ReferenceRange
import app.vezuna.export.DoctorMedicationRow
import app.vezuna.export.DoctorPdfDocument
import app.vezuna.export.DoctorResultRow
import app.vezuna.export.PdfCanvas
import app.vezuna.export.PdfColor
import app.vezuna.export.PdfReportLayout
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfReportLayoutTest {
    private val range = ReferenceRange(12.0, 15.5, unit = "g/dL")

    private fun document(exams: Int) = DoctorPdfDocument(
        title = "t",
        lines = emptyList(),
        profileDisplayName = "Ana",
        resultRows = (1..exams).map { index ->
            DoctorResultRow(Instant.parse("2026-03-05T08:30:00Z"), "Exam $index", "E$index", 17.5, "g/dL", 175.0, "g/L", range, RangeAssessment.ABOVE)
        },
        medicationRows = listOf(DoctorMedicationRow("Metformin", "500 mg", "Daily")),
        generatedAt = Instant.parse("2026-03-06T10:00:00Z"),
    )

    private fun render(width: Double, height: Double, exams: Int): RecordingCanvas =
        RecordingCanvas(width, height).also { PdfReportLayout(it).render(document(exams)) }

    @Test
    fun manyExamsPaginateAndEveryPageGetsAFooter() {
        val canvas = render(595.0, 842.0, exams = 30)
        assertTrue(canvas.pages > 1, "expected pagination, got ${canvas.pages}")
        assertEquals((1..canvas.pages).map { "Page $it" }, canvas.texts.filter { it.startsWith("Page ") })
    }

    @Test
    fun contentStaysInsideThePageForEveryPageSize() {
        listOf(595.0 to 842.0, 612.0 to 792.0).forEach { (width, height) ->
            val canvas = render(width, height, exams = 30)
            assertTrue(canvas.maxRight <= width, "content past right edge on ${width}x$height")
            assertTrue(canvas.maxBottom <= height, "content past bottom edge on ${width}x$height")
        }
    }

    @Test
    fun narrowerPagesNeedAtLeastAsManyPages() {
        assertTrue(render(612.0, 792.0, 30).pages >= render(595.0, 842.0, 30).pages)
    }
}

private class RecordingCanvas(override val pageWidth: Double, override val pageHeight: Double) : PdfCanvas {
    var pages = 0
    val texts = mutableListOf<String>()
    var maxRight = 0.0
    var maxBottom = 0.0

    override fun newPage() { pages++ }
    override fun measure(text: String, size: Double, bold: Boolean) = text.length * size * 0.5
    override fun text(text: String, x: Double, top: Double, size: Double, color: PdfColor, bold: Boolean) {
        texts += text
        maxRight = maxOf(maxRight, x + measure(text, size, bold))
        maxBottom = maxOf(maxBottom, top + size)
    }
    override fun line(x1: Double, y1: Double, x2: Double, y2: Double, color: PdfColor, width: Double) {
        maxRight = maxOf(maxRight, x1, x2); maxBottom = maxOf(maxBottom, y1, y2)
    }
    override fun rect(x: Double, top: Double, width: Double, height: Double, color: PdfColor) {
        if (width < pageWidth) { maxRight = maxOf(maxRight, x + width); maxBottom = maxOf(maxBottom, top + height) }
    }
    override fun strokeRect(x: Double, top: Double, width: Double, height: Double, color: PdfColor, lineWidth: Double) {
        maxRight = maxOf(maxRight, x + width); maxBottom = maxOf(maxBottom, top + height)
    }
    override fun circle(x: Double, y: Double, radius: Double, color: PdfColor) {
        maxRight = maxOf(maxRight, x + radius); maxBottom = maxOf(maxBottom, y + radius)
    }
}
