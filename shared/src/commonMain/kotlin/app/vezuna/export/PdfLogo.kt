package app.vezuna.export

/**
 * The official ink lockup (`brand/logos/lockup-ink.svg`, viewBox 410 x 110) as vector paths. The path data is
 * copied verbatim from the SVG, so the PDF draws the real mark and outlined wordmark, never retyped text.
 */
internal object PdfLogo {
    const val VIEW_WIDTH = 410.0
    const val VIEW_HEIGHT = 110.0
    val INK = PdfColor(0x15 / 255.0, 0x2E / 255.0, 0x35 / 255.0)

    private const val STROKE_WIDTH = 14.0
    private const val GLYPH_SCALE = 0.067
    private const val BASELINE = 77.0
    private val strokes = listOf("M24 25 C28 47 38 65 50 77", "M76 25 C72 47 62 65 50 77")
    private val glyphs = listOf(
        116.0 to "M650 714 407 0H242L0 714H153L287 289Q291 278 298.5 248.5Q306 219 314.0 186.5Q322 154 325 132Q328 154 335.5 186.5Q343 219 351.0 248.5Q359 278 362 289L497 714Z",
        159.55 to "M303 556Q416 556 482.0 491.5Q548 427 548 308V236H196Q198 173 233.5 137.0Q269 101 332 101Q385 101 428.0 111.5Q471 122 517 144V29Q477 9 432.5 -0.5Q388 -10 325 -10Q243 -10 180.0 20.5Q117 51 81.0 113.0Q45 175 45 269Q45 365 77.5 428.5Q110 492 168.0 524.0Q226 556 303 556ZM304 450Q261 450 232.5 422.0Q204 394 199 335H408Q407 385 382.0 417.5Q357 450 304 450Z",
        199.147 to "M458 0H27V88L280 432H42V546H449V449L203 114H458Z",
        231.843 to "M579 546V0H465L445 70H437Q411 28 365.5 9.0Q320 -10 269 -10Q181 -10 128.0 37.5Q75 85 75 190V546H224V227Q224 169 245.0 139.0Q266 109 312 109Q380 109 405.0 155.5Q430 202 430 289V546Z",
        275.862 to "M388 556Q476 556 529.0 508.5Q582 461 582 356V0H433V319Q433 378 412.0 407.5Q391 437 345 437Q277 437 252.0 390.5Q227 344 227 257V0H78V546H192L212 476H220Q246 518 291.5 537.0Q337 556 388 556Z",
        319.881 to "M302 557Q412 557 470.5 509.5Q529 462 529 364V0H425L396 74H392Q357 30 318.0 10.0Q279 -10 211 -10Q138 -10 90.0 32.5Q42 75 42 163Q42 250 103.0 291.5Q164 333 286 337L381 340V364Q381 407 358.5 427.0Q336 447 296 447Q256 447 218.0 435.5Q180 424 142 407L93 508Q137 531 190.5 544.0Q244 557 302 557ZM323 251Q251 249 223.0 225.0Q195 201 195 162Q195 128 215.0 113.5Q235 99 267 99Q315 99 348.0 127.5Q381 156 381 208V253Z",
    )

    /** Draws the lockup [width] points wide with its top-left corner at ([left], [top]); returns the drawn height. */
    fun draw(canvas: PdfCanvas, left: Double, top: Double, width: Double, color: PdfColor): Double {
        val scale = width / VIEW_WIDTH
        fun point(x: Double, y: Double) = (left + x * scale) to (top + y * scale)
        // The mark group is translated (0, 5) in the SVG.
        strokes.forEach { canvas.path(parse(it) { x, y -> point(x, y + 5.0) }, color, STROKE_WIDTH * scale) }
        val (cx, cy) = point(50.0, 34.0)
        canvas.circle(cx, cy, 7.0 * scale, color)
        glyphs.forEach { (offset, data) ->
            canvas.path(parse(data) { x, y -> point(offset + x * GLYPH_SCALE, BASELINE - y * GLYPH_SCALE) }, color, null)
        }
        return VIEW_HEIGHT * scale
    }

    private val token = Regex("[MLHVCQZ]|-?\\d*\\.?\\d+")

    /** Absolute M/L/H/V/C/Q/Z only, which is all the lockup uses; [map] applies the SVG transform. */
    internal fun parse(data: String, map: (Double, Double) -> Pair<Double, Double>): List<PathOp> {
        val tokens = token.findAll(data).map { it.value }.toList()
        val ops = mutableListOf<PathOp>()
        var index = 0
        var command = 'M'
        var x = 0.0
        var y = 0.0
        fun next() = tokens[index++].toDouble()
        while (index < tokens.size) {
            val head = tokens[index]
            if (head.length == 1 && head[0].isLetter()) {
                command = head[0]
                index++
                if (command == 'Z') { ops += PathOp.Close; continue }
            }
            when (command) {
                'M', 'L' -> {
                    x = next(); y = next()
                    val (px, py) = map(x, y)
                    ops += if (command == 'M') PathOp.MoveTo(px, py) else PathOp.LineTo(px, py)
                    if (command == 'M') command = 'L'
                }
                'H', 'V' -> {
                    if (command == 'H') x = next() else y = next()
                    val (px, py) = map(x, y)
                    ops += PathOp.LineTo(px, py)
                }
                'C' -> {
                    val (x1, y1) = map(next(), next())
                    val (x2, y2) = map(next(), next())
                    x = next(); y = next()
                    val (px, py) = map(x, y)
                    ops += PathOp.CubicTo(x1, y1, x2, y2, px, py)
                }
                'Q' -> {
                    val (x1, y1) = map(next(), next())
                    x = next(); y = next()
                    val (px, py) = map(x, y)
                    ops += PathOp.QuadTo(x1, y1, px, py)
                }
                else -> error("Unsupported SVG path command $command")
            }
        }
        return ops
    }
}
