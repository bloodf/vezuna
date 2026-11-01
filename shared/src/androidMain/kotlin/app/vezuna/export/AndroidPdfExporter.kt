package app.vezuna.export

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import app.vezuna.domain.RangeAssessment
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

actual class NativePdfExporter {
    actual fun export(document: DoctorPdfDocument): ExportedPdf {
        val pdf = PdfDocument()
        AndroidLabReport(pdf).render(document)
        val output = ByteArrayOutputStream()
        pdf.writeTo(output)
        pdf.close()
        return ExportedPdf("vezuna-doctor-export.pdf", output.toByteArray())
    }
}

private class AndroidLabReport(private val pdf: PdfDocument) {
    private lateinit var page: PdfDocument.Page
    private var pageNumber = 0
    private var y = 0f
    private var sectionName = "Clinical report"
    private val body = paint(9f, INK)
    private val small = paint(7.5f, MUTED)
    private val title = paint(18f, NAVY, bold = true)
    private val examTitle = paint(12f, NAVY, bold = true)
    private val result = paint(14f, INK, bold = true)
    private val line = paint(0.65f, BORDER)
    private val navyLine = paint(1.6f, NAVY)
    private val trendLine = paint(1.5f, NAVY)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun render(document: DoctorPdfDocument) {
        newPage()
        text("Vezuna", MARGIN, y, title)
        y += 26f
        if (document.profileDisplayName.isNotEmpty()) {
            text(document.profileDisplayName, MARGIN, y, examTitle)
            y += 17f
        }
        text("Generated ${DoctorReportFormat.dateTime(document.generatedAt)}", MARGIN, y, small)
        y += 13f
        text("Collection / report period: ${DoctorReportFormat.period(document.resultRows)}", MARGIN, y, small)
        y += 13f
        y += wrapped("Private health record for clinical discussion. OCR-derived values are included only after user confirmation.", small, MARGIN, y, CONTENT_WIDTH)
        y += 8f
        line(MARGIN, y, RIGHT, y, navyLine)
        y += 12f
        legend()
        val exams = DoctorExamSections.from(document)
        exams.forEach { exam(it) }
        medications(document.medicationRows)
        if (!document.hasStructuredContent && document.lines.isNotEmpty()) {
            sectionHeading("Report notes")
            document.lines.forEach {
                ensureSpace(18f)
                y += wrapped(it, body, MARGIN, y, CONTENT_WIDTH) + 5f
            }
            document.trends.filter { it.points.size >= 2 }.forEach { chart(it) }
        }
        footer()
    }

    private fun legend() {
        val entries = listOf(
            RangeAssessment.WITHIN to "Within reference",
            RangeAssessment.BELOW to "Below reference",
            RangeAssessment.ABOVE to "Above reference",
            RangeAssessment.UNKNOWN to "Not assessed",
        )
        val width = CONTENT_WIDTH / entries.size
        entries.forEachIndexed { index, entry ->
            val x = MARGIN + index * width
            rect(x, y + 2f, 3f, 12f, statusColor(entry.first))
            wrapped(entry.second, small, x + 8f, y, width - 12f)
        }
        y += 26f
    }

    private fun exam(section: DoctorExamSection) {
        sectionName = section.testName
        ensureSectionSpace(measuredExamHeight(section), section.testName)
        y += wrapped(section.testName, examTitle, MARGIN, y, CONTENT_WIDTH - 70f)
        text("${section.testCode} · ${section.unit}", RIGHT - 70f, y - 14f, small)
        line(MARGIN, y, RIGHT, y, navyLine)
        y += 8f
        latestResult(section.latest)
        history(section)
        section.trend?.takeIf { it.points.size >= 2 }?.let { chart(it) }
        y += 18f
    }

    private fun measuredExamHeight(section: DoctorExamSection): Float {
        val titleHeight = wrap(section.testName, examTitle, CONTENT_WIDTH - 70f).size * (examTitle.textSize + 4f) + 8f
        val historyHeight = if (section.history.isEmpty()) 0f else {
            15f + section.history.chunked(4).sumOf { historyChunkHeight(it).toDouble() }.toFloat()
        }
        val chartHeight = if (section.trend?.points?.size?.let { it >= 2 } == true) CHART_HEIGHT else 0f
        return titleHeight + latestResultHeight(section.latest) + historyHeight + chartHeight + 18f
    }

    private fun latestResultHeight(row: DoctorResultRow): Float {
        val referenceLines = wrap(DoctorExportContent.rangeText(row.referenceRange), body, 190f)
        val normalized = DoctorReportFormat.normalizedValue(row)
        val contentHeight = maxOf(48f, referenceLines.size * 13f + 16f, if (normalized == null) 48f else 61f)
        return 19f + contentHeight + 12f
    }

    private fun latestResult(row: DoctorResultRow) {
        rect(MARGIN, y, CONTENT_WIDTH, 19f, PALE_NAVY)
        text("RESULT", MARGIN + 7f, y + 5f, small)
        right("REFERENCE RANGE", RIGHT - 7f, y + 5f, small)
        y += 19f
        val referenceLines = wrap(DoctorExportContent.rangeText(row.referenceRange), body, 190f)
        val normalized = DoctorReportFormat.normalizedValue(row)
        val height = maxOf(48f, referenceLines.size * 13f + 16f, if (normalized == null) 48f else 61f)
        rect(MARGIN, y, CONTENT_WIDTH, height, statusContainer(row.assessment))
        rect(MARGIN, y, 4f, height, statusColor(row.assessment))
        text(DoctorReportFormat.originalValue(row), MARGIN + 13f, y + 8f, result)
        normalized?.let { text(it, MARGIN + 13f, y + 29f, small) }
        text(DoctorReportFormat.assessment(row.assessment), MARGIN + 13f, y + height - 14f, small)
        referenceLines.forEachIndexed { index, value -> right(value, RIGHT - 8f, y + 9f + index * 13f, body) }
        outline(MARGIN, y, CONTENT_WIDTH, height)
        y += height + 12f
    }

    private fun history(section: DoctorExamSection) {
        if (section.history.isEmpty()) return
        val chunks = section.history.chunked(4)
        ensureSpace(15f + historyChunkHeight(chunks.first()))
        text("History", MARGIN, y, body)
        y += 15f
        chunks.forEachIndexed { index, chunk ->
            if (index == chunks.lastIndex && section.trend?.points?.size?.let { it >= 2 } == true) {
                ensureSectionSpace(historyChunkHeight(chunk) + CHART_HEIGHT, "${section.testName} · history and trend")
            }
            historyChunk(section.testName, chunk)
        }
    }

    private fun historyChunkHeight(rows: List<DoctorResultRow>): Float {
        val valueWidth = (CONTENT_WIDTH - 82f) / rows.size
        val referenceLines = rows.map { wrap(DoctorExportContent.rangeText(it.referenceRange), small, valueWidth - 8f) }
        val referenceHeight = maxOf(28f, referenceLines.maxOf { it.size } * 11f + 12f)
        return 29f + 38f + referenceHeight + 12f
    }

    private fun historyChunk(testName: String, rows: List<DoctorResultRow>) {
        val labelWidth = 82f
        val valueWidth = (CONTENT_WIDTH - labelWidth) / rows.size
        val referenceLines = rows.map { wrap(DoctorExportContent.rangeText(it.referenceRange), small, valueWidth - 8f) }
        val referenceHeight = maxOf(28f, referenceLines.maxOf { it.size } * 11f + 12f)
        val totalHeight = 29f + 38f + referenceHeight
        if (y + totalHeight > BOTTOM) {
            sectionName = "$testName · history continued"
            newPage()
            sectionHeading(sectionName, compact = true)
        }
        rect(MARGIN, y, CONTENT_WIDTH, 29f, PALE_NAVY)
        text("Date", MARGIN + 6f, y + 9f, small)
        rows.forEachIndexed { index, row ->
            val x = MARGIN + labelWidth + index * valueWidth
            center(DoctorReportFormat.shortDate(row.collectedAt), x, y + 5f, valueWidth, small)
            center(row.collectedAt.toString().take(4), x, y + 16f, valueWidth, small)
        }
        y += 29f
        text("Result", MARGIN + 6f, y + 13f, small)
        rows.forEachIndexed { index, row ->
            val x = MARGIN + labelWidth + index * valueWidth
            rect(x, y, valueWidth, 38f, statusContainer(row.assessment))
            rect(x, y, 2.5f, 38f, statusColor(row.assessment))
            center(DoctorReportFormat.originalValue(row), x + 3f, y + 7f, valueWidth - 6f, body)
            DoctorReportFormat.normalizedValue(row)?.let { center(it, x + 3f, y + 22f, valueWidth - 6f, small) }
        }
        outline(MARGIN, y, CONTENT_WIDTH, 38f)
        y += 38f
        text("Reference", MARGIN + 6f, y + 12f, small)
        rows.forEachIndexed { index, _ ->
            val x = MARGIN + labelWidth + index * valueWidth
            if (index % 2 == 1) rect(x, y, valueWidth, referenceHeight, HISTORY_ALT)
            referenceLines[index].forEachIndexed { lineIndex, value ->
                center(value, x + 4f, y + 6f + lineIndex * 11f, valueWidth - 8f, small)
            }
        }
        outline(MARGIN, y, CONTENT_WIDTH, referenceHeight)
        y += referenceHeight + 12f
    }

    private fun chart(series: DoctorTrendSeries) {
        ensureSectionSpace(CHART_HEIGHT, "${series.testName} · trend")
        text("History chart · ${series.unit}", MARGIN, y, small)
        y += 15f
        val geometry = DoctorChartGeometryFactory.create(series)
        val left = MARGIN + 34f
        val top = y + 8f
        val width = CONTENT_WIDTH - 48f
        val height = 72f
        val bottom = top + height
        repeat(3) { step ->
            val gridY = top + step * height / 2f
            line(left, gridY, left + width, gridY, line)
        }
        val points = geometry.points.map { point ->
            Triple(point, left + point.xFraction.toFloat() * width, bottom - geometry.yFraction(point.point.value).toFloat() * height)
        }
        points.zipWithNext().forEach { (a, b) -> line(a.second, a.third, b.second, b.third, trendLine) }
        points.forEachIndexed { index, item ->
            val (point, x, valueY) = item
            if (point.referenceLower != null || point.referenceUpper != null) {
                val low = point.referenceLower?.let { bottom - geometry.yFraction(it).toFloat() * height } ?: bottom
                val high = point.referenceUpper?.let { bottom - geometry.yFraction(it).toFloat() * height } ?: top
                line(x, high, x, low, paint(2f, RANGE_GRAY))
            }
            fill.color = NAVY
            page.canvas.drawCircle(x, valueY, 2.6f, fill)
            if (index == points.lastIndex) right(DoctorReportFormat.value(point.point.value), min(x + 16f, left + width), max(top, valueY - 12f), small)
        }
        text(DoctorReportFormat.date(series.points.first().timestamp), left, bottom + 12f, small)
        right(DoctorReportFormat.date(series.points.last().timestamp), left + width, bottom + 12f, small)
        y = bottom + 30f
    }

    private fun medications(rows: List<DoctorMedicationRow>) {
        if (rows.isEmpty()) return
        ensureSectionSpace(30f + rows.sumOf { medicationHeight(it).toDouble() }.toFloat(), "Medications")
        sectionHeading("Medications")
        rows.forEach { row ->
            val height = medicationHeight(row)
            ensureSectionSpace(height, "Medications")
            val detail = listOfNotNull(row.strength, row.instructions).joinToString(" · ")
            val nameLines = wrap(row.displayName, body, 175f)
            val detailLines = if (detail.isEmpty()) emptyList() else wrap(detail, small, CONTENT_WIDTH - 196f)
            nameLines.forEachIndexed { index, value -> text(value, MARGIN + 6f, y + 7f + index * 12f, body) }
            detailLines.forEachIndexed { index, value -> text(value, MARGIN + 190f, y + 5f + index * 11f, small) }
            line(MARGIN, y + height, RIGHT, y + height, line)
            y += height
        }
    }

    private fun medicationHeight(row: DoctorMedicationRow): Float {
        val detail = listOfNotNull(row.strength, row.instructions).joinToString(" · ")
        val nameLines = wrap(row.displayName, body, 175f)
        val detailLines = if (detail.isEmpty()) emptyList() else wrap(detail, small, CONTENT_WIDTH - 196f)
        return maxOf(26f, maxOf(nameLines.size, detailLines.size) * 12f + 12f)
    }

    private fun sectionHeading(value: String, compact: Boolean = false) {
        sectionName = value
        ensureSpace(if (compact) 35f else 30f)
        text(value, MARGIN, y, if (compact) body else examTitle)
        y += if (compact) 18f else 20f
        line(MARGIN, y, RIGHT, y, navyLine)
        y += 10f
    }

    private fun ensureSectionSpace(height: Float, name: String) {
        if (y + height > BOTTOM) {
            sectionName = name
            newPage()
        }
    }

    private fun ensureSpace(height: Float) { if (y + height > BOTTOM) newPage() }

    private fun newPage() {
        if (pageNumber > 0) footer()
        pageNumber++
        page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), pageNumber).create())
        page.canvas.drawColor(PAPER)
        y = MARGIN
        if (pageNumber > 1) {
            text("Vezuna  ·  $sectionName", MARGIN, y, small)
            y += 13f
            line(MARGIN, y, RIGHT, y, navyLine)
            y += 12f
        }
    }

    private fun footer() {
        line(MARGIN, PAGE_HEIGHT - 29f, RIGHT, PAGE_HEIGHT - 29f, line)
        text("Vezuna · Private health record", MARGIN, PAGE_HEIGHT - 22f, small)
        right("Page $pageNumber", RIGHT, PAGE_HEIGHT - 22f, small)
        pdf.finishPage(page)
    }

    private fun wrapped(value: String, paint: Paint, x: Float, top: Float, width: Float): Float {
        val lines = wrap(value, paint, width)
        lines.forEachIndexed { index, item -> text(item, x, top + index * (paint.textSize + 4f), paint) }
        return lines.size * (paint.textSize + 4f)
    }

    private fun wrap(value: String, paint: Paint, width: Float): List<String> = buildList {
        var remaining = value
        while (remaining.isNotEmpty()) {
            var count = paint.breakText(remaining, true, width, null).coerceAtLeast(1)
            if (count < remaining.length) {
                val whitespace = remaining.lastIndexOf(' ', count - 1)
                if (whitespace > 0) count = whitespace
            }
            add(remaining.substring(0, count).trimEnd())
            remaining = remaining.substring(count).trimStart()
        }
        if (isEmpty()) add("")
    }

    private fun text(value: String, x: Float, top: Float, paint: Paint) {
        paint.textAlign = Paint.Align.LEFT
        page.canvas.drawText(value, x, top - paint.fontMetrics.ascent, paint)
    }
    private fun right(value: String, x: Float, top: Float, paint: Paint) {
        paint.textAlign = Paint.Align.RIGHT
        page.canvas.drawText(value, x, top - paint.fontMetrics.ascent, paint)
        paint.textAlign = Paint.Align.LEFT
    }
    private fun center(value: String, x: Float, top: Float, width: Float, paint: Paint) {
        paint.textAlign = Paint.Align.CENTER
        page.canvas.drawText(value, x + width / 2, top - paint.fontMetrics.ascent, paint)
        paint.textAlign = Paint.Align.LEFT
    }
    private fun line(x1: Float, y1: Float, x2: Float, y2: Float, paint: Paint) = page.canvas.drawLine(x1, y1, x2, y2, paint)
    private fun rect(x: Float, top: Float, width: Float, height: Float, color: Int) {
        fill.color = color
        page.canvas.drawRect(x, top, x + width, top + height, fill)
    }
    private fun outline(x: Float, top: Float, width: Float, height: Float) = page.canvas.drawRect(x, top, x + width, top + height, line)

    private fun paint(size: Float, color: Int, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        strokeWidth = size
        style = if (size <= 3f) Paint.Style.STROKE else Paint.Style.FILL
    }
    private fun statusColor(status: RangeAssessment) = when (status) {
        RangeAssessment.WITHIN -> GREEN
        RangeAssessment.BELOW, RangeAssessment.ABOVE -> AMBER
        RangeAssessment.UNKNOWN -> GRAY
    }
    private fun statusContainer(status: RangeAssessment) = when (status) {
        RangeAssessment.WITHIN -> PALE_GREEN
        RangeAssessment.BELOW, RangeAssessment.ABOVE -> PALE_AMBER
        RangeAssessment.UNKNOWN -> PALE_GRAY
    }

    private companion object {
        const val PAGE_WIDTH = 595f
        const val PAGE_HEIGHT = 842f
        const val MARGIN = 36f
        const val RIGHT = PAGE_WIDTH - MARGIN
        const val BOTTOM = 804f
        const val CHART_HEIGHT = 125f
        const val CONTENT_WIDTH = RIGHT - MARGIN
        val PAPER = Color.rgb(253, 253, 250)
        val INK = Color.rgb(34, 39, 43)
        val MUTED = Color.rgb(91, 99, 103)
        val NAVY = Color.rgb(35, 61, 86)
        val GREEN = Color.rgb(49, 119, 78)
        val AMBER = Color.rgb(157, 103, 22)
        val GRAY = Color.rgb(111, 118, 121)
        val PALE_NAVY = Color.rgb(230, 235, 239)
        val PALE_GREEN = Color.rgb(228, 241, 230)
        val PALE_AMBER = Color.rgb(249, 238, 213)
        val PALE_GRAY = Color.rgb(237, 239, 238)
        val HISTORY_ALT = Color.rgb(247, 247, 242)
        val BORDER = Color.rgb(199, 205, 207)
        val RANGE_GRAY = Color.rgb(195, 207, 199)
    }
}
