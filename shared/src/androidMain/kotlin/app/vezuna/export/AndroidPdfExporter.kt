package app.vezuna.export

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream

actual class NativePdfExporter {
    actual fun export(document: DoctorPdfDocument): ExportedPdf {
        val pdf = PdfDocument()
        val canvas = AndroidPdfCanvas(pdf)
        PdfReportLayout(canvas).render(document)
        canvas.finish()
        val output = ByteArrayOutputStream()
        pdf.writeTo(output)
        pdf.close()
        return ExportedPdf("vezuna-doctor-export.pdf", output.toByteArray())
    }
}

private class AndroidPdfCanvas(private val pdf: PdfDocument) : PdfCanvas {
    override val pageWidth = 595.0
    override val pageHeight = 842.0
    private var page: PdfDocument.Page? = null
    private var pageNumber = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    override fun newPage() {
        finish()
        pageNumber++
        page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth.toInt(), pageHeight.toInt(), pageNumber).create())
    }

    fun finish() {
        page?.let(pdf::finishPage)
        page = null
    }

    override fun measure(text: String, size: Double, bold: Boolean): Double = textPaint(size, PdfColor(0.0, 0.0, 0.0), bold).measureText(text).toDouble()

    override fun text(text: String, x: Double, top: Double, size: Double, color: PdfColor, bold: Boolean) {
        val textPaint = textPaint(size, color, bold)
        canvas().drawText(text, x.toFloat(), (top - textPaint.fontMetrics.ascent).toFloat(), textPaint)
    }

    override fun line(x1: Double, y1: Double, x2: Double, y2: Double, color: PdfColor, width: Double) {
        canvas().drawLine(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), shapePaint(color, Paint.Style.STROKE, width))
    }

    override fun rect(x: Double, top: Double, width: Double, height: Double, color: PdfColor) {
        canvas().drawRect(x.toFloat(), top.toFloat(), (x + width).toFloat(), (top + height).toFloat(), shapePaint(color, Paint.Style.FILL, 0.0))
    }

    override fun strokeRect(x: Double, top: Double, width: Double, height: Double, color: PdfColor, lineWidth: Double) {
        canvas().drawRect(x.toFloat(), top.toFloat(), (x + width).toFloat(), (top + height).toFloat(), shapePaint(color, Paint.Style.STROKE, lineWidth))
    }

    override fun circle(x: Double, y: Double, radius: Double, color: PdfColor) {
        canvas().drawCircle(x.toFloat(), y.toFloat(), radius.toFloat(), shapePaint(color, Paint.Style.FILL, 0.0))
    }

    private fun canvas() = checkNotNull(page) { "newPage() must be called before drawing" }.canvas

    private fun textPaint(size: Double, color: PdfColor, bold: Boolean) = paint.apply {
        style = Paint.Style.FILL
        textSize = size.toFloat()
        this.color = color.argb()
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun shapePaint(color: PdfColor, style: Paint.Style, width: Double) = paint.apply {
        this.style = style
        this.color = color.argb()
        strokeWidth = width.toFloat()
    }

    private fun PdfColor.argb() = Color.rgb((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
}
