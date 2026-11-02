package app.vezuna.export

/** Print sizes and colors from the PDF spec (points; 1 mm = 2.8346 pt). */
internal object PdfStyle {
    const val MM = 72.0 / 25.4
    const val TITLE = 24.0
    const val PROFILE = 13.0
    const val SECTION = 15.0
    const val BODY = 11.5
    const val LABEL = 11.0
    const val NOTE = 11.0
    const val FOOTER = 9.0
    const val BODY_LEADING = 1.38
    const val NOTE_LEADING = 1.32
    const val RULE = 0.25 * MM

    val PINE = hex(0x123B46)
    val INK = hex(0x152E35)
    val MUTED = hex(0x526C74)
    val BORDER = hex(0xC5D0D3)
    val PALE = hex(0xEEF4F2)
    val BAND = hex(0xE3F6F1)
    val MINT = hex(0x71E2CC)
    val CORAL = hex(0xFF8A65)
    val LAVENDER = hex(0xB8ABFF)
    val PAPER = PdfColor(1.0, 1.0, 1.0)

    private fun hex(rgb: Int) = PdfColor(((rgb shr 16) and 0xFF) / 255.0, ((rgb shr 8) and 0xFF) / 255.0, (rgb and 0xFF) / 255.0)
}

/** Drawing helpers over a [PdfCanvas]; every color passes through [color] so grayscale output is true gray. */
internal class PdfPen(val canvas: PdfCanvas, private val grayscale: Boolean) {
    fun color(value: PdfColor): PdfColor {
        if (!grayscale) return value
        val gray = 0.2126 * value.red + 0.7152 * value.green + 0.0722 * value.blue
        return PdfColor(gray, gray, gray)
    }

    fun measure(value: String, size: Double, bold: Boolean = false) = canvas.measure(value, size, bold)

    fun text(value: String, x: Double, top: Double, size: Double, color: PdfColor = PdfStyle.INK, bold: Boolean = false) =
        canvas.text(value, x, top, size, color(color), bold)

    fun right(value: String, x: Double, top: Double, size: Double, color: PdfColor = PdfStyle.INK, bold: Boolean = false) =
        text(value, x - measure(value, size, bold), top, size, color, bold)

    fun line(x1: Double, y1: Double, x2: Double, y2: Double, color: PdfColor, width: Double) =
        canvas.line(x1, y1, x2, y2, color(color), width)

    fun dashed(x1: Double, x2: Double, y: Double, color: PdfColor, width: Double) {
        var x = x1
        while (x < x2) {
            line(x, y, minOf(x + 4.0, x2), y, color, width)
            x += 7.0
        }
    }

    fun rect(x: Double, top: Double, width: Double, height: Double, color: PdfColor) = canvas.rect(x, top, width, height, color(color))
    fun circle(x: Double, y: Double, radius: Double, color: PdfColor) = canvas.circle(x, y, radius, color(color))

    fun lineHeight(size: Double) = size * if (size <= PdfStyle.NOTE) PdfStyle.NOTE_LEADING else PdfStyle.BODY_LEADING

    /** Draws wrapped [value] and returns its height. */
    fun paragraph(value: String, x: Double, top: Double, width: Double, size: Double, color: PdfColor = PdfStyle.INK, bold: Boolean = false): Double {
        val lines = wrap(value, width, size, bold)
        lines.forEachIndexed { index, item -> text(item, x, top + index * lineHeight(size), size, color, bold) }
        return lines.size * lineHeight(size)
    }

    fun paragraphHeight(value: String, width: Double, size: Double, bold: Boolean = false) = wrap(value, width, size, bold).size * lineHeight(size)

    /** Word wrap that never truncates: a token wider than [width] is broken across lines. */
    fun wrap(value: String, width: Double, size: Double, bold: Boolean = false): List<String> = buildList {
        var remaining = value
        while (remaining.isNotEmpty()) {
            var low = 1
            var high = remaining.length
            var count = 1
            while (low <= high) {
                val middle = (low + high) / 2
                if (measure(remaining.substring(0, middle), size, bold) <= width) {
                    count = middle
                    low = middle + 1
                } else high = middle - 1
            }
            if (count < remaining.length) {
                val whitespace = remaining.lastIndexOf(' ', count)
                if (whitespace > 0) count = whitespace
            }
            add(remaining.substring(0, count).trimEnd())
            remaining = remaining.substring(count).trimStart()
        }
        if (isEmpty()) add("")
    }
}
