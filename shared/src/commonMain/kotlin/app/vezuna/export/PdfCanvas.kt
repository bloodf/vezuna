package app.vezuna.export

/** RGB components in 0..1. */
data class PdfColor(val red: Double, val green: Double, val blue: Double)

/** Paper sizes in PDF points. Letter reflows the layout; it is never a scaled-down A4 page. */
enum class PaperSize(val width: Double, val height: Double) {
    A4(595.28, 841.89),
    LETTER(612.0, 792.0),
}

/** Absolute path commands in page points (top-left origin), used for the vector logo. */
sealed interface PathOp {
    data class MoveTo(val x: Double, val y: Double) : PathOp
    data class LineTo(val x: Double, val y: Double) : PathOp
    data class CubicTo(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val x: Double, val y: Double) : PathOp
    data class QuadTo(val x1: Double, val y1: Double, val x: Double, val y: Double) : PathOp
    data object Close : PathOp
}

/**
 * The only platform-specific part of PDF export. Coordinates are in points from the top-left corner of the page;
 * [text] positions the top of the text box, not the baseline. Text must stay real, selectable text and shapes
 * must stay vector: no canvas may rasterize.
 */
interface PdfCanvas {
    val pageWidth: Double
    val pageHeight: Double

    /** Starts a new page; the previous page, if any, is complete. */
    fun newPage()
    fun measure(text: String, size: Double, bold: Boolean): Double
    fun text(text: String, x: Double, top: Double, size: Double, color: PdfColor, bold: Boolean)
    fun line(x1: Double, y1: Double, x2: Double, y2: Double, color: PdfColor, width: Double)
    fun rect(x: Double, top: Double, width: Double, height: Double, color: PdfColor)
    fun strokeRect(x: Double, top: Double, width: Double, height: Double, color: PdfColor, lineWidth: Double)
    fun circle(x: Double, y: Double, radius: Double, color: PdfColor)

    /** Fills [ops] when [strokeWidth] is null, otherwise strokes them with round caps and joins. */
    fun path(ops: List<PathOp>, color: PdfColor, strokeWidth: Double?)
}
