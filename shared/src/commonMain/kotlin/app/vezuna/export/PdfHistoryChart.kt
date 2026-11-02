package app.vezuna.export

import app.vezuna.localization.ContentLanguage

/**
 * Full-width history graph: actual collection dates at their real spacing, every dot labelled with its recorded
 * value when the label fits at 11 pt, the source reference as a band/dashed line (shared bounds) or per-reading bars.
 */
internal class PdfHistoryChart(private val pen: PdfPen, private val language: ContentLanguage) {
    private val referenceLabel = ExportText.of(language).recordedReference

    data class Result(val height: Double, val labelsOmitted: Boolean)

    private data class Label(val text: String, val left: Double, val top: Double, val width: Double)

    fun height(): Double = UNIT_ROW + PLOT_HEIGHT + DATE_ROW

    /** [decimals] prints each dot as recorded (13.0, not 13); null prints the shortest exact value. */
    fun draw(series: DoctorTrendSeries, left: Double, top: Double, width: Double, decimals: Int? = null): Result {
        val geometry = DoctorChartGeometryFactory.create(series)
        val size = PdfStyle.LABEL
        val tickLabels = geometry.ticks.map { DoctorReportFormat.value(it, language) }
        val axisWidth = (tickLabels.maxOfOrNull { pen.measure(it, size) } ?: 0.0) + 8.0
        val plotLeft = left + axisWidth + 4.0
        val plotRight = left + width - 6.0
        val plotTop = top + UNIT_ROW
        val plotBottom = plotTop + PLOT_HEIGHT
        fun x(fraction: Double) = plotLeft + 14.0 + fraction * (plotRight - plotLeft - 28.0)
        fun y(value: Double) = plotBottom - geometry.yFraction(value) * PLOT_HEIGHT

        pen.text(series.unit, left, top, size, PdfStyle.MUTED, bold = true)
        geometry.ticks.forEachIndexed { index, tick ->
            pen.line(plotLeft, y(tick), plotRight, y(tick), PdfStyle.BORDER, 0.5)
            pen.right(tickLabels[index], plotLeft - 6.0, y(tick) - size * 0.62, size, PdfStyle.MUTED)
        }
        pen.line(plotLeft, plotBottom, plotRight, plotBottom, PdfStyle.MUTED, 0.8)
        reference(geometry, plotLeft, plotRight, plotTop, plotBottom, ::x, ::y)

        val points = geometry.points.map { Triple(it, x(it.xFraction), y(it.point.value)) }
        points.zipWithNext().forEach { (a, b) -> pen.line(a.second, a.third, b.second, b.third, PdfStyle.PINE, 1.5) }
        points.forEach { (_, px, py) ->
            pen.circle(px, py, 4.4, PdfStyle.PINE)
            pen.circle(px, py, 3.0, PdfStyle.LAVENDER)
        }

        val values = points.map { (point, px, py) ->
            val text = DoctorReportFormat.recorded(point.point.value, decimals, language)
            val textWidth = pen.measure(text, size, bold = true)
            val above = py - 7.0 - size * 1.15
            val labelTop = if (above < plotTop - 2.0) py + 7.0 else above
            Label(text, clamp(px - textWidth / 2, left, left + width - textWidth), labelTop, textWidth)
        }
        val keptValues = place(values)
        keptValues.forEach { pen.text(it.text, it.left, it.top, size, PdfStyle.INK, bold = true) }

        val dates = points.map { (point, px, _) ->
            val text = DoctorReportFormat.date(point.point.timestamp, language)
            val textWidth = pen.measure(text, size)
            Label(text, clamp(px - textWidth / 2, left, left + width - textWidth), plotBottom + 5.0, textWidth)
        }
        val keptDates = place(dates)
        keptDates.forEach { pen.text(it.text, it.left, it.top, size, PdfStyle.MUTED) }
        return Result(height(), keptValues.size < values.size || keptDates.size < dates.size)
    }

    private fun reference(
        geometry: DoctorChartGeometry,
        plotLeft: Double,
        plotRight: Double,
        plotTop: Double,
        plotBottom: Double,
        x: (Double) -> Double,
        y: (Double) -> Double,
    ) {
        val lowers = geometry.points.map { it.referenceLower }.distinct()
        val uppers = geometry.points.map { it.referenceUpper }.distinct()
        if (lowers.size == 1 && uppers.size == 1) {
            val lower = lowers.single()
            val upper = uppers.single()
            if (lower == null && upper == null) return
            val bandTop = upper?.let(y) ?: plotTop
            val bandBottom = lower?.let(y) ?: plotBottom
            if (lower != null && upper != null) pen.rect(plotLeft, bandTop, plotRight - plotLeft, bandBottom - bandTop, PdfStyle.BAND)
            listOfNotNull(lower, upper).forEach { pen.dashed(plotLeft, plotRight, y(it), PdfStyle.MUTED, 1.0) }
            pen.text(referenceLabel, plotLeft + 6.0, (upper ?: lower!!).let(y) + 3.0, PdfStyle.LABEL, PdfStyle.MUTED)
            return
        }
        // Reports disagree: a bar per reading at its own date, never one misleading band.
        geometry.points.forEach { point ->
            if (point.referenceLower == null && point.referenceUpper == null) return@forEach
            val px = x(point.xFraction)
            val high = point.referenceUpper?.let(y) ?: plotTop
            val low = point.referenceLower?.let(y) ?: plotBottom
            pen.rect(px - 2.5, high, 5.0, low - high, PdfStyle.BAND)
            pen.line(px - 4.0, high, px + 4.0, high, PdfStyle.MUTED, 1.0)
            pen.line(px - 4.0, low, px + 4.0, low, PdfStyle.MUTED, 1.0)
        }
    }

    /** Latest first, then first, then the rest left to right; a label that would overlap a kept one is dropped. */
    private fun place(labels: List<Label>): List<Label> {
        if (labels.isEmpty()) return labels
        val order = (listOf(labels.lastIndex, 0) + labels.indices.drop(1).dropLast(1)).distinct()
        val kept = mutableListOf<Label>()
        order.forEach { index ->
            val label = labels[index]
            val overlaps = kept.any { other ->
                label.left < other.left + other.width + 4.0 && other.left < label.left + label.width + 4.0 &&
                    label.top < other.top + PdfStyle.LABEL * 1.2 && other.top < label.top + PdfStyle.LABEL * 1.2
            }
            if (!overlaps) kept += label
        }
        return kept
    }

    private fun clamp(value: Double, low: Double, high: Double) = value.coerceAtLeast(low).coerceAtMost(maxOf(low, high))

    companion object {
        const val UNIT_ROW = 20.0
        const val PLOT_HEIGHT = 54.0 * PdfStyle.MM
        const val DATE_ROW = 24.0
    }
}
