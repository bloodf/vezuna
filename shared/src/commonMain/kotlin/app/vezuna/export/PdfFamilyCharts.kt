package app.vezuna.export

import app.vezuna.domain.DoseEventStatus
import app.vezuna.domain.ReferenceRange
import app.vezuna.domain.trends.Granularity
import app.vezuna.domain.trends.PointSource
import app.vezuna.domain.trends.PointSourceKind
import app.vezuna.domain.trends.RawReading
import app.vezuna.domain.trends.TrendComparison
import app.vezuna.domain.trends.TrendEngine
import app.vezuna.domain.trends.TrendRange
import app.vezuna.export.PdfStyle.LABEL
import app.vezuna.localization.ContentLanguage
import kotlinx.datetime.LocalDate
import kotlin.math.ceil

/**
 * Categorical dose timeline (pdf-07): one column per calendar day, a distinct glyph per outcome (check, bar, cross)
 * plus its word, so grayscale prints keep the meaning. No percentage, trend or judgement.
 */
internal class PdfDoseTimeline(private val pen: PdfPen, private val language: ContentLanguage) {
    private val s = ExportSectionText.of(language)
    private val dateRow = pen.lineHeight(LABEL) + 2.0
    private val dose get() = 2 * RADIUS + 4.0 + pen.lineHeight(LABEL) + 4.0

    /** Height of one row of up to seven [days]; a day with several doses stacks them, each with its time. */
    fun height(days: List<LocalDate>, rows: List<DoctorDoseRow>): Double {
        val most = days.maxOf { day -> rows.count { it.date == day } }.coerceAtLeast(1)
        val timeLine = if (most > 1) pen.lineHeight(LABEL) else 0.0
        return dateRow + most * (dose + timeLine) + 2.0
    }

    fun draw(days: List<LocalDate>, rows: List<DoctorDoseRow>, left: Double, top: Double, width: Double): Double {
        val column = width / DAYS
        val height = height(days, rows)
        pen.rect(left, top, width, height, PdfStyle.PALE)
        days.forEachIndexed { index, day ->
            val x = left + index * column
            val center = x + column / 2
            if (index > 0) pen.line(x, top + 3.0, x, top + height - 3.0, PdfStyle.BORDER, PdfStyle.RULE)
            centered(DoctorReportFormat.shortDate(day, language), center, top + 2.0, bold = true)
            val doses = rows.filter { it.date == day }
            var cursor = top + dateRow
            if (doses.isEmpty()) centered("–", center, cursor + RADIUS, bold = false)
            doses.forEach { row ->
                if (doses.size > 1) {
                    centered(row.time, center, cursor, bold = false)
                    cursor += pen.lineHeight(LABEL)
                }
                mark(row.status, center, cursor + RADIUS + 2.0)
                centered(word(row.status), center, cursor + 2 * RADIUS + 4.0, bold = false)
                cursor += dose
            }
        }
        return height
    }

    private fun word(status: DoseEventStatus) = when (status) {
        DoseEventStatus.TAKEN -> s.taken
        DoseEventStatus.SKIPPED -> s.skipped
        else -> s.missed
    }

    private fun centered(text: String, center: Double, top: Double, bold: Boolean) =
        pen.text(text, center - pen.measure(text, LABEL, bold) / 2, top, LABEL, PdfStyle.INK, bold)

    /** Check for taken, bar for skipped, cross for missed: the glyph, not the fill, carries the meaning. */
    fun mark(status: DoseEventStatus, cx: Double, cy: Double) {
        val fill = when (status) {
            DoseEventStatus.TAKEN -> PdfStyle.MINT
            DoseEventStatus.SKIPPED -> PdfStyle.LAVENDER
            else -> PdfStyle.CORAL
        }
        pen.circle(cx, cy, RADIUS, fill)
        val g = RADIUS * 0.5
        when (status) {
            DoseEventStatus.TAKEN -> pen.path(
                listOf(PathOp.MoveTo(cx - g, cy), PathOp.LineTo(cx - g * 0.2, cy + g * 0.75), PathOp.LineTo(cx + g, cy - g * 0.7)),
                PdfStyle.PINE, 1.8,
            )
            DoseEventStatus.SKIPPED -> pen.line(cx - g, cy, cx + g, cy, PdfStyle.PINE, 2.0)
            else -> {
                pen.line(cx - g * 0.8, cy - g * 0.8, cx + g * 0.8, cy + g * 0.8, PdfStyle.PINE, 1.8)
                pen.line(cx - g * 0.8, cy + g * 0.8, cx + g * 0.8, cy - g * 0.8, PdfStyle.PINE, 1.8)
            }
        }
    }

    companion object {
        const val DAYS = 7
        const val RADIUS = 8.0
    }
}

/**
 * Device graph (pdf-08) over calendar days at equal spacing: bars for daily step totals, dots joined by a guide line
 * for daily average heart rate and weight. A day without a reading stays empty; every drawn value is labelled when it fits.
 */
internal class PdfDailyChart(private val pen: PdfPen, private val language: ContentLanguage) {
    fun height(): Double = UNIT_ROW + PLOT + DATE_ROW

    /** Returns true when some value labels had to be left out for space (the table below still has every value). */
    fun draw(days: List<LocalDate>, points: List<Pair<LocalDate, Double>>, bars: Boolean, unit: String, decimals: Int, left: Double, top: Double, width: Double): Boolean {
        val (ticks, fraction) = scale(points.map { it.second }, bars)
        val tickLabels = ticks.map { DoctorReportFormat.value(it, language) }
        val plotLeft = left + (tickLabels.maxOf { pen.measure(it, LABEL) }) + 12.0
        val plotRight = left + width - 4.0
        val plotTop = top + UNIT_ROW
        val plotBottom = plotTop + PLOT
        val slot = (plotRight - plotLeft) / days.size
        fun x(day: LocalDate) = plotLeft + (days.indexOf(day) + 0.5) * slot
        fun y(value: Double) = plotBottom - fraction(value) * PLOT

        pen.text(unit, left, top, LABEL, PdfStyle.MUTED, bold = true)
        ticks.forEachIndexed { index, tick ->
            pen.line(plotLeft, y(tick), plotRight, y(tick), PdfStyle.BORDER, 0.5)
            pen.right(tickLabels[index], plotLeft - 6.0, y(tick) - LABEL * 0.62, LABEL, PdfStyle.MUTED)
        }
        pen.line(plotLeft, plotBottom, plotRight, plotBottom, PdfStyle.MUTED, 0.8)
        val drawn = points.filter { it.first in days }.sortedBy { it.first }
        if (bars) {
            drawn.forEach { (day, value) ->
                val barWidth = slot * 0.6
                pen.rect(x(day) - barWidth / 2, y(value), barWidth, plotBottom - y(value), PdfStyle.CORAL)
            }
        } else {
            drawn.zipWithNext().forEach { (a, b) -> pen.line(x(a.first), y(a.second), x(b.first), y(b.second), PdfStyle.PINE, 1.5) }
            drawn.forEach { (day, value) ->
                pen.circle(x(day), y(value), DOT, PdfStyle.PINE)
                pen.circle(x(day), y(value), 3.0, PdfStyle.LAVENDER)
            }
        }
        var omitted = false
        var lastRight = Double.NEGATIVE_INFINITY
        drawn.forEach { (day, value) ->
            val text = DoctorReportFormat.fixed(value, decimals, language)
            val textWidth = pen.measure(text, LABEL, bold = true)
            val labelLeft = x(day) - textWidth / 2
            if (labelLeft < lastRight + 3.0 || textWidth > slot * 1.8) {
                omitted = true
                return@forEach
            }
            pen.text(text, labelLeft, y(value) - (if (bars) 3.0 else 7.0) - LABEL * 1.15, LABEL, PdfStyle.INK, bold = true)
            lastRight = labelLeft + textWidth
        }
        val dateWidth = days.maxOf { pen.measure(DoctorReportFormat.shortDate(it, language), LABEL) }
        val every = ceil((dateWidth + 4.0) / slot).toInt().coerceAtLeast(1)
        days.forEachIndexed { index, day ->
            if (index % every != 0) return@forEachIndexed
            val text = DoctorReportFormat.shortDate(day, language)
            pen.text(text, x(day) - pen.measure(text, LABEL) / 2, plotBottom + 5.0, LABEL, PdfStyle.MUTED)
        }
        return omitted
    }

    /** Bars start at zero; dots use the shared history-chart scale around the recorded values. */
    private fun scale(values: List<Double>, bars: Boolean): Pair<List<Double>, (Double) -> Double> {
        if (bars) {
            val step = DoctorChartGeometryFactory.niceStep(maxOf(values.max(), 1.0))
            val high = ceil(maxOf(values.max(), 1.0) / step) * step
            val ticks = generateSequence(0.0) { it + step }.takeWhile { it <= high + step / 2 }.toList()
            return ticks to { value: Double -> value / high }
        }
        // A margin above and below keeps the lowest and highest dots off the frame; the margins are never drawn.
        val span = values.max() - values.min()
        val margin = if (span > 0) span * 0.15 else maxOf(kotlin.math.abs(values.max()) * 0.05, 1.0)
        val framed = values + listOf(values.min() - margin, values.max() + margin)
        val series = DoctorTrendSeries("", "", "", framed.mapIndexed { index, value ->
            DoctorTrendPoint(kotlinx.datetime.Instant.fromEpochSeconds(index.toLong()), value, ReferenceRange())
        })
        val geometry = DoctorChartGeometryFactory.create(series)
        return geometry.ticks to geometry::yFraction
    }

    companion object {
        const val UNIT_ROW = 20.0
        const val PLOT = 45.0 * PdfStyle.MM
        const val DATE_ROW = 24.0
        const val DOT = 4.4
    }
}

/** First/previous arithmetic for one device metric from the shared trends engine, over the exported rows only. */
internal fun deviceComparison(rows: List<DoctorDeviceRow>, unit: String, decimals: Int): TrendComparison {
    val readings = rows.mapIndexed { index, row ->
        RawReading(
            "$index", row.date, row.value, row.value, unit, decimals, null, PointSource(PointSourceKind.DEVICE),
            if (row.time == null) Granularity.DAILY_SUMMARY else Granularity.READING,
            row.time.orEmpty() + index.toString().padStart(6, '0'),
        )
    }
    val today = rows.maxOf { it.date }
    return TrendEngine.series("device|$unit", unit, unit, readings, TrendRange.All, today).comparison
}
