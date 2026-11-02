package app.vezuna.export

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import java.io.ByteArrayOutputStream

actual class NativePdfExporter {
    actual fun export(document: DoctorPdfDocument): ExportedPdf = PDDocument().use { pdf ->
        pdf.documentInformation.title = document.title
        pdf.documentInformation.creator = "Vezuna"
        pdf.documentCatalog.language = document.language.name.lowercase()
        val canvas = JvmPdfCanvas(pdf, document.paper)
        PdfReportLayout(canvas).render(document)
        canvas.finish()
        val output = ByteArrayOutputStream()
        pdf.save(output)
        ExportedPdf(DoctorExportContent.fileName(document), output.toByteArray())
    }
}

/** PDFBox canvas: embedded subset Noto Sans (the app's bundled font), real text and vector shapes. */
private class JvmPdfCanvas(private val pdf: PDDocument, paper: PaperSize) : PdfCanvas {
    override val pageWidth = paper.width
    override val pageHeight = paper.height
    private val regular = font("NotoSans-Regular.ttf")
    private val bold = font("NotoSans-Bold.ttf")
    private var stream: PDPageContentStream? = null

    private fun font(name: String): PDType0Font {
        val resource = "composeResources/vezuna.shared.generated.resources/font/$name"
        val bytes = checkNotNull(javaClass.classLoader.getResourceAsStream(resource)) { "Missing bundled font $resource" }.use { it.readBytes() }
        return PDType0Font.load(pdf, bytes.inputStream(), true)
    }

    override fun newPage() {
        finish()
        val page = PDPage(PDRectangle(pageWidth.toFloat(), pageHeight.toFloat()))
        pdf.addPage(page)
        stream = PDPageContentStream(pdf, page)
    }

    fun finish() {
        stream?.close()
        stream = null
    }

    private fun font(bold: Boolean) = if (bold) this.bold else regular

    override fun measure(text: String, size: Double, bold: Boolean): Double = font(bold).getStringWidth(printable(text, bold)) / 1000.0 * size

    private val fallbacks = mapOf("\u2212" to "-", "\u2264" to "<=", "\u2265" to ">=")

    /** The bundled Noto subset lacks a few symbols; spell them in ASCII rather than fail or drop text. */
    private fun printable(text: String, bold: Boolean): String {
        val font = font(bold)
        return buildString {
            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                val char = String(Character.toChars(codePoint))
                append(if (runCatching { font.encode(char) }.isSuccess) char else fallbacks[char] ?: "?")
                index += Character.charCount(codePoint)
            }
        }
    }

    override fun text(text: String, x: Double, top: Double, size: Double, color: PdfColor, bold: Boolean) {
        val font = font(bold)
        val baseline = top + font.fontDescriptor.ascent / 1000.0 * size
        with(stream()) {
            setNonStrokingColor(color.red.toFloat(), color.green.toFloat(), color.blue.toFloat())
            beginText()
            setFont(font, size.toFloat())
            newLineAtOffset(x.toFloat(), (pageHeight - baseline).toFloat())
            showText(printable(text, bold))
            endText()
        }
    }

    override fun line(x1: Double, y1: Double, x2: Double, y2: Double, color: PdfColor, width: Double) = with(stream()) {
        stroke(color, width)
        moveTo(x1.toFloat(), flip(y1))
        lineTo(x2.toFloat(), flip(y2))
        stroke()
    }

    override fun rect(x: Double, top: Double, width: Double, height: Double, color: PdfColor) = with(stream()) {
        setNonStrokingColor(color.red.toFloat(), color.green.toFloat(), color.blue.toFloat())
        addRect(x.toFloat(), flip(top + height), width.toFloat(), height.toFloat())
        fill()
    }

    override fun strokeRect(x: Double, top: Double, width: Double, height: Double, color: PdfColor, lineWidth: Double) = with(stream()) {
        stroke(color, lineWidth)
        addRect(x.toFloat(), flip(top + height), width.toFloat(), height.toFloat())
        stroke()
    }

    override fun circle(x: Double, y: Double, radius: Double, color: PdfColor) {
        val k = radius * 0.5522848
        path(
            listOf(
                PathOp.MoveTo(x + radius, y),
                PathOp.CubicTo(x + radius, y + k, x + k, y + radius, x, y + radius),
                PathOp.CubicTo(x - k, y + radius, x - radius, y + k, x - radius, y),
                PathOp.CubicTo(x - radius, y - k, x - k, y - radius, x, y - radius),
                PathOp.CubicTo(x + k, y - radius, x + radius, y - k, x + radius, y),
                PathOp.Close,
            ),
            color,
            null,
        )
    }

    override fun path(ops: List<PathOp>, color: PdfColor, strokeWidth: Double?) = with(stream()) {
        // Graphics state must be set before path construction starts.
        if (strokeWidth == null) {
            setNonStrokingColor(color.red.toFloat(), color.green.toFloat(), color.blue.toFloat())
        } else {
            stroke(color, strokeWidth)
            setLineCapStyle(1)
            setLineJoinStyle(1)
        }
        var current = 0.0 to 0.0
        ops.forEach { op ->
            when (op) {
                is PathOp.MoveTo -> { moveTo(op.x.toFloat(), flip(op.y)); current = op.x to op.y }
                is PathOp.LineTo -> { lineTo(op.x.toFloat(), flip(op.y)); current = op.x to op.y }
                is PathOp.CubicTo -> {
                    curveTo(op.x1.toFloat(), flip(op.y1), op.x2.toFloat(), flip(op.y2), op.x.toFloat(), flip(op.y))
                    current = op.x to op.y
                }
                is PathOp.QuadTo -> {
                    // PDF has no quadratic curve: raise it to the equivalent cubic.
                    val (sx, sy) = current
                    curveTo(
                        (sx + 2.0 / 3.0 * (op.x1 - sx)).toFloat(), flip(sy + 2.0 / 3.0 * (op.y1 - sy)),
                        (op.x + 2.0 / 3.0 * (op.x1 - op.x)).toFloat(), flip(op.y + 2.0 / 3.0 * (op.y1 - op.y)),
                        op.x.toFloat(), flip(op.y),
                    )
                    current = op.x to op.y
                }
                PathOp.Close -> closePath()
            }
        }
        if (strokeWidth == null) {
            fill()
        } else {
            stroke()
            setLineCapStyle(0)
            setLineJoinStyle(0)
        }
    }

    private fun PDPageContentStream.stroke(color: PdfColor, width: Double) {
        setStrokingColor(color.red.toFloat(), color.green.toFloat(), color.blue.toFloat())
        setLineWidth(width.toFloat())
    }

    private fun flip(y: Double) = (pageHeight - y).toFloat()
    private fun stream() = checkNotNull(stream) { "newPage() must be called before drawing" }
}
