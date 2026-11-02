package app.vezuna.export

/** RGB components in 0..1. */
data class PdfColor(val red: Double, val green: Double, val blue: Double)

/**
 * The only platform-specific part of PDF export. Coordinates are in points from the top-left corner of the page;
 * [text] positions the top of the text box, not the baseline.
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
}
