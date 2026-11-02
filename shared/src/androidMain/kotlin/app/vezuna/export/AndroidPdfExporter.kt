package app.vezuna.export

import android.content.res.AssetManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** The app installs its asset manager at startup so PDFs embed the bundled Noto Sans; without it the system sans is used. */
object AndroidPdfFonts {
    @Volatile var assets: AssetManager? = null

    internal fun typeface(bold: Boolean): Typeface {
        val name = if (bold) "NotoSans-Bold.ttf" else "NotoSans-Regular.ttf"
        return assets?.let { manager ->
            runCatching { Typeface.createFromAsset(manager, "composeResources/vezuna.shared.generated.resources/font/$name") }.getOrNull()
        } ?: Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
}

actual class NativePdfExporter {
    actual fun export(document: DoctorPdfDocument): ExportedPdf {
        val pdf = PdfDocument()
        try {
            val canvas = AndroidPdfCanvas(pdf, document.paper)
            PdfReportLayout(canvas).render(document)
            canvas.finish()
            val output = ByteArrayOutputStream()
            pdf.writeTo(output)
            return ExportedPdf(DoctorExportContent.fileName(document), output.toByteArray())
        } finally {
            pdf.close()
        }
    }
}

private class AndroidPdfCanvas(private val pdf: PdfDocument, paper: PaperSize) : PdfCanvas {
    // PdfDocument pages are whole points.
    override val pageWidth = paper.width.roundToInt().toDouble()
    override val pageHeight = paper.height.roundToInt().toDouble()
    private var page: PdfDocument.Page? = null
    private var pageNumber = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val regular = AndroidPdfFonts.typeface(bold = false)
    private val bold = AndroidPdfFonts.typeface(bold = true)

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

    override fun path(ops: List<PathOp>, color: PdfColor, strokeWidth: Double?) {
        val path = Path()
        ops.forEach { op ->
            when (op) {
                is PathOp.MoveTo -> path.moveTo(op.x.toFloat(), op.y.toFloat())
                is PathOp.LineTo -> path.lineTo(op.x.toFloat(), op.y.toFloat())
                is PathOp.CubicTo -> path.cubicTo(op.x1.toFloat(), op.y1.toFloat(), op.x2.toFloat(), op.y2.toFloat(), op.x.toFloat(), op.y.toFloat())
                is PathOp.QuadTo -> path.quadTo(op.x1.toFloat(), op.y1.toFloat(), op.x.toFloat(), op.y.toFloat())
                PathOp.Close -> path.close()
            }
        }
        val shape = if (strokeWidth == null) shapePaint(color, Paint.Style.FILL, 0.0) else shapePaint(color, Paint.Style.STROKE, strokeWidth).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas().drawPath(path, shape)
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeJoin = Paint.Join.MITER
    }

    private fun canvas() = checkNotNull(page) { "newPage() must be called before drawing" }.canvas

    private fun textPaint(size: Double, color: PdfColor, bold: Boolean) = paint.apply {
        style = Paint.Style.FILL
        textSize = size.toFloat()
        this.color = color.argb()
        typeface = if (bold) this@AndroidPdfCanvas.bold else regular
    }

    private fun shapePaint(color: PdfColor, style: Paint.Style, width: Double) = paint.apply {
        this.style = style
        this.color = color.argb()
        strokeWidth = width.toFloat()
    }

    private fun PdfColor.argb() = Color.rgb((red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt())
}
