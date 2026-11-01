package app.vezuna.export

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

actual class NativePdfExporter {
    actual fun export(document: DoctorPdfDocument): ExportedPdf {
        val canvas = JvmPdfCanvas()
        PdfReportLayout(canvas).render(document)
        return ExportedPdf("vezuna-doctor-export.pdf", JvmPdfBuilder.build(canvas.pages))
    }
}

private data class JvmPdfPage(val commands: StringBuilder = StringBuilder())

/** Raw PDF content-stream canvas; also used by JVM tests to exercise the shared layout. */
private class JvmPdfCanvas : PdfCanvas {
    val pages = mutableListOf<JvmPdfPage>()
    override val pageWidth = 595.0
    override val pageHeight = 842.0

    override fun newPage() { pages += JvmPdfPage() }

    override fun measure(text: String, size: Double, bold: Boolean) = text.sumOf { characterWidth(it, size) } * if (bold) 1.08 else 1.0

    override fun text(text: String, x: Double, top: Double, size: Double, color: PdfColor, bold: Boolean) {
        commands().append("${rgb(color)} rg BT /${if (bold) "F2" else "F1"} ${n(size)} Tf 1 0 0 1 ${n(x)} ${n(pageHeight - top - size)} Tm (")
            .append(pdfEscape(text)).append(") Tj ET\n")
    }

    override fun line(x1: Double, y1: Double, x2: Double, y2: Double, color: PdfColor, width: Double) {
        commands().append("${rgb(color)} RG ${n(width)} w ${n(x1)} ${n(pageHeight - y1)} m ${n(x2)} ${n(pageHeight - y2)} l S\n")
    }

    override fun rect(x: Double, top: Double, width: Double, height: Double, color: PdfColor) {
        commands().append("${rgb(color)} rg ${n(x)} ${n(pageHeight - top - height)} ${n(width)} ${n(height)} re f\n")
    }

    override fun strokeRect(x: Double, top: Double, width: Double, height: Double, color: PdfColor, lineWidth: Double) {
        commands().append("${rgb(color)} RG ${n(lineWidth)} w ${n(x)} ${n(pageHeight - top - height)} ${n(width)} ${n(height)} re S\n")
    }

    override fun circle(x: Double, y: Double, radius: Double, color: PdfColor) {
        val centerY = pageHeight - y
        val k = radius * 0.5522848
        commands().append("${rgb(color)} rg ${n(x + radius)} ${n(centerY)} m ")
            .append("${n(x + radius)} ${n(centerY + k)} ${n(x + k)} ${n(centerY + radius)} ${n(x)} ${n(centerY + radius)} c ")
            .append("${n(x - k)} ${n(centerY + radius)} ${n(x - radius)} ${n(centerY + k)} ${n(x - radius)} ${n(centerY)} c ")
            .append("${n(x - radius)} ${n(centerY - k)} ${n(x - k)} ${n(centerY - radius)} ${n(x)} ${n(centerY - radius)} c ")
            .append("${n(x + k)} ${n(centerY - radius)} ${n(x + radius)} ${n(centerY - k)} ${n(x + radius)} ${n(centerY)} c f\n")
    }

    private fun characterWidth(character: Char, size: Double) = when {
        character == ' ' -> 0.28
        character.isUpperCase() -> 0.62
        character.isDigit() -> 0.56
        else -> 0.51
    } * size

    private fun commands() = pages.last().commands
    private fun rgb(color: PdfColor) = "${n(color.red)} ${n(color.green)} ${n(color.blue)}"
    private fun n(value: Double) = "%.3f".format(java.util.Locale.ROOT, value)
    private fun pdfEscape(value: String) = value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
}

private object JvmPdfBuilder {
    private val windows1252 = charset("windows-1252")

    fun build(pages: List<JvmPdfPage>): ByteArray {
        val fontObject = 3 + pages.size * 2
        val boldFontObject = fontObject + 1
        val objects = mutableMapOf<Int, ByteArray>()
        objects[1] = ascii("<< /Type /Catalog /Pages 2 0 R >>")
        objects[2] = ascii("<< /Type /Pages /Kids [${pages.indices.joinToString(" ") { "${3 + it * 2} 0 R" }}] /Count ${pages.size} >>")
        pages.forEachIndexed { index, page ->
            val pageObject = 3 + index * 2
            val contentObject = pageObject + 1
            objects[pageObject] = ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 $fontObject 0 R /F2 $boldFontObject 0 R >> >> /Contents $contentObject 0 R >>")
            val content = page.commands.toString().toByteArray(windows1252)
            objects[contentObject] = ascii("<< /Length ${content.size} >>\nstream\n") + content + ascii("\nendstream")
        }
        objects[fontObject] = ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        objects[boldFontObject] = ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Times-Bold /Encoding /WinAnsiEncoding >>")
        val output = ByteArrayOutputStream()
        output.write(ascii("%PDF-1.4\n%Vezuna\n"))
        val offsets = mutableMapOf<Int, Int>()
        objects.toSortedMap().forEach { (number, body) ->
            offsets[number] = output.size()
            output.write(ascii("$number 0 obj\n"))
            output.write(body)
            output.write(ascii("\nendobj\n"))
        }
        val xref = output.size()
        output.write(ascii("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n"))
        objects.toSortedMap().keys.forEach { output.write(ascii(offsets.getValue(it).toString().padStart(10, '0') + " 00000 n \n")) }
        output.write(ascii("trailer << /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF"))
        return output.toByteArray()
    }

    private fun ascii(value: String) = value.toByteArray(StandardCharsets.US_ASCII)
}
