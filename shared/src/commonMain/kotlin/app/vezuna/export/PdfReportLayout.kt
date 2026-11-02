package app.vezuna.export

import app.vezuna.domain.RangeAssessment
import app.vezuna.localization.ContentLanguage
import kotlin.math.max
import kotlin.math.min

/** Page layout shared by every platform; platform code only supplies the [PdfCanvas] drawing primitives. */
class PdfReportLayout(private val canvas: PdfCanvas) {
    private var pageNumber = 0
    private val pageWidth = canvas.pageWidth
    private val pageHeight = canvas.pageHeight
    private val RIGHT = pageWidth - MARGIN
    private val BOTTOM = pageHeight - PAGE_BOTTOM_INSET
    private val CONTENT_WIDTH = RIGHT - MARGIN
    private var y = 0.0
    private var sectionName = ""
    private var language = ContentLanguage.EN
    private var t = ExportText.of(language)

    fun render(document: DoctorPdfDocument) {
        language = document.language
        t = ExportText.of(language)
        sectionName = t.clinicalReport
        newPage()
        text("Vezuna", MARGIN, y, 18.0, NAVY, bold = true)
        y += 26.0
        if (document.profileDisplayName.isNotEmpty()) {
            text(document.profileDisplayName, MARGIN, y, 12.0, NAVY, bold = true)
            y += 17.0
        }
        document.ageYears?.let {
            text("${t.age}: $it", MARGIN, y, 9.0, INK)
            y += 14.0
        }
        text("${t.generated} ${DoctorReportFormat.dateTime(document.generatedAt, language)}", MARGIN, y, 7.5, MUTED)
        y += 13.0
        text("${t.reportPeriod}: ${DoctorReportFormat.period(document.resultRows, language)}", MARGIN, y, 7.5, MUTED)
        y += 13.0
        DoctorReportFormat.sources(document.resultRows, language)?.let { y += wrapped(it, MARGIN, y, CONTENT_WIDTH, 7.5, MUTED) + 4.0 }
        y += wrapped(t.disclaimer, MARGIN, y, CONTENT_WIDTH, 7.5, MUTED)
        y += 8.0
        line(MARGIN, y, RIGHT, y, NAVY, 1.6)
        y += 12.0
        legend()
        DoctorExamSections.from(document).forEach { exam(it) }
        medications(document.medicationRows)
        if (!document.hasStructuredContent && document.lines.isNotEmpty()) {
            sectionHeading(t.reportNotes)
            document.lines.forEach {
                ensureSpace(18.0)
                y += wrapped(it, MARGIN, y, CONTENT_WIDTH, 9.0) + 5.0
            }
            document.trends.filter { it.points.size >= 2 }.forEach { chart(it) }
        }
        footer()
    }

    private fun legend() {
        val entries = listOf(
            RangeAssessment.WITHIN to t.withinReference,
            RangeAssessment.BELOW to t.belowReference,
            RangeAssessment.ABOVE to t.aboveReference,
            RangeAssessment.UNKNOWN to t.legendNotAssessed,
        )
        val width = CONTENT_WIDTH / entries.size
        entries.forEachIndexed { index, entry ->
            val x = MARGIN + index * width
            rect(x, y + 2.0, 3.0, 12.0, statusColor(entry.first))
            wrapped(entry.second, x + 8.0, y, width - 12.0, 7.5, MUTED)
        }
        y += 26.0
    }

    private fun exam(section: DoctorExamSection) {
        sectionName = section.testName
        ensureSectionSpace(measuredExamHeight(section), section.testName)
        y += wrapped(section.testName, MARGIN, y, CONTENT_WIDTH - 70.0, 12.0, NAVY, bold = true)
        right("${section.testCode}, ${section.unit}", RIGHT, y - 14.0, 7.5, MUTED)
        line(MARGIN, y, RIGHT, y, NAVY, 1.6)
        y += 8.0
        latestResult(section.latest)
        history(section)
        section.trend?.takeIf { it.points.size >= 2 }?.let { chart(it) }
        y += 18.0
    }

    private fun measuredExamHeight(section: DoctorExamSection): Double {
        val titleHeight = wrap(section.testName, CONTENT_WIDTH - 70.0, 12.0, bold = true).size * 16.0 + 8.0
        val historyHeight = if (section.history.isEmpty()) 0.0 else {
            15.0 + section.history.chunked(4).sumOf { historyChunkHeight(it) }
        }
        val chartHeight = if (section.trend?.points?.size?.let { it >= 2 } == true) CHART_HEIGHT else 0.0
        return titleHeight + latestResultHeight(section.latest) + historyHeight + chartHeight + 18.0
    }

    private fun latestResultHeight(row: DoctorResultRow): Double {
        val referenceLines = wrap(DoctorExportContent.rangeText(row.referenceRange, language), 190.0, 9.0)
        val normalized = DoctorReportFormat.normalizedValue(row, language)
        val contentHeight = maxOf(48.0, referenceLines.size * 13.0 + 16.0, if (normalized == null) 48.0 else 61.0)
        return 19.0 + contentHeight + 12.0
    }

    private fun latestResult(row: DoctorResultRow) {
        rect(MARGIN, y, CONTENT_WIDTH, 19.0, PALE_NAVY)
        text(t.result.uppercase(), MARGIN + 7.0, y + 5.0, 7.5, MUTED)
        right(t.referenceRange.uppercase(), RIGHT - 7.0, y + 5.0, 7.5, MUTED)
        y += 19.0
        val referenceLines = wrap(DoctorExportContent.rangeText(row.referenceRange, language), 190.0, 9.0)
        val normalized = DoctorReportFormat.normalizedValue(row, language)
        val height = maxOf(48.0, referenceLines.size * 13.0 + 16.0, if (normalized == null) 48.0 else 61.0)
        rect(MARGIN, y, CONTENT_WIDTH, height, statusContainer(row.assessment))
        rect(MARGIN, y, 4.0, height, statusColor(row.assessment))
        text(DoctorReportFormat.originalValue(row, language), MARGIN + 13.0, y + 8.0, 14.0, INK, bold = true)
        normalized?.let { text(it, MARGIN + 13.0, y + 29.0, 7.5, MUTED) }
        text(DoctorReportFormat.assessment(row.assessment, language), MARGIN + 13.0, y + height - 14.0, 7.5, MUTED)
        referenceLines.forEachIndexed { index, value -> right(value, RIGHT - 8.0, y + 9.0 + index * 13.0, 9.0) }
        outline(MARGIN, y, CONTENT_WIDTH, height)
        y += height + 12.0
    }

    private fun history(section: DoctorExamSection) {
        if (section.history.isEmpty()) return
        val chunks = section.history.chunked(4)
        ensureSpace(15.0 + historyChunkHeight(chunks.first()))
        text(t.history, MARGIN, y, 9.0)
        y += 15.0
        chunks.forEachIndexed { index, chunk ->
            if (index == chunks.lastIndex && section.trend?.points?.size?.let { it >= 2 } == true) {
                ensureSectionSpace(historyChunkHeight(chunk) + CHART_HEIGHT, "${section.testName} - ${t.historyAndTrend}")
            }
            historyChunk(section.testName, chunk)
        }
    }

    private fun historyChunkHeight(rows: List<DoctorResultRow>): Double {
        val valueWidth = (CONTENT_WIDTH - 82.0) / rows.size
        val referenceLines = rows.map { wrap(DoctorExportContent.rangeText(it.referenceRange, language), valueWidth - 8.0, 7.5) }
        val referenceHeight = maxOf(28.0, referenceLines.maxOf { it.size } * 11.0 + 12.0)
        return 29.0 + 38.0 + referenceHeight + 12.0
    }

    private fun historyChunk(testName: String, rows: List<DoctorResultRow>) {
        val labelWidth = 82.0
        val valueWidth = (CONTENT_WIDTH - labelWidth) / rows.size
        val referenceLines = rows.map { wrap(DoctorExportContent.rangeText(it.referenceRange, language), valueWidth - 8.0, 7.5) }
        val referenceHeight = maxOf(28.0, referenceLines.maxOf { it.size } * 11.0 + 12.0)
        val totalHeight = 29.0 + 38.0 + referenceHeight
        if (y + totalHeight > BOTTOM) {
            sectionName = "$testName - ${t.historyContinued}"
            newPage()
            sectionHeading(sectionName, compact = true)
        }
        rect(MARGIN, y, CONTENT_WIDTH, 29.0, PALE_NAVY)
        text(t.date, MARGIN + 6.0, y + 9.0, 7.5, MUTED)
        rows.forEachIndexed { index, row ->
            val x = MARGIN + labelWidth + index * valueWidth
            center(DoctorReportFormat.shortDate(row.collectedAt, language), x, y + 5.0, valueWidth, 7.5, MUTED)
            center(row.collectedAt.toString().take(4), x, y + 16.0, valueWidth, 7.5, MUTED)
        }
        y += 29.0
        text(t.result, MARGIN + 6.0, y + 13.0, 7.5, MUTED)
        rows.forEachIndexed { index, row ->
            val x = MARGIN + labelWidth + index * valueWidth
            rect(x, y, valueWidth, 38.0, statusContainer(row.assessment))
            rect(x, y, 2.5, 38.0, statusColor(row.assessment))
            center(DoctorReportFormat.originalValue(row, language), x + 3.0, y + 7.0, valueWidth - 6.0, 9.0)
            DoctorReportFormat.normalizedValue(row, language)?.let { center(it, x + 3.0, y + 22.0, valueWidth - 6.0, 7.5, MUTED) }
        }
        outline(MARGIN, y, CONTENT_WIDTH, 38.0)
        y += 38.0
        text(t.reference, MARGIN + 6.0, y + 12.0, 7.5, MUTED)
        rows.forEachIndexed { index, _ ->
            val x = MARGIN + labelWidth + index * valueWidth
            if (index % 2 == 1) rect(x, y, valueWidth, referenceHeight, HISTORY_ALT)
            referenceLines[index].forEachIndexed { lineIndex, value -> center(value, x + 4.0, y + 6.0 + lineIndex * 11.0, valueWidth - 8.0, 7.5, MUTED) }
        }
        outline(MARGIN, y, CONTENT_WIDTH, referenceHeight)
        y += referenceHeight + 12.0
    }

    private fun chart(series: DoctorTrendSeries) {
        ensureSectionSpace(CHART_HEIGHT, "${series.testName} - ${t.trend}")
        text("${t.historyChart} (${series.unit})", MARGIN, y, 7.5, MUTED)
        y += 15.0
        val geometry = DoctorChartGeometryFactory.create(series)
        val left = MARGIN + 34.0
        val top = y + 8.0
        val width = CONTENT_WIDTH - 48.0
        val height = 72.0
        val bottom = top + height
        repeat(3) { step -> line(left, top + step * height / 2.0, left + width, top + step * height / 2.0, BORDER, 0.65) }
        val points = geometry.points.map { point ->
            Triple(point, left + point.xFraction * width, bottom - geometry.yFraction(point.point.value) * height)
        }
        points.zipWithNext().forEach { (a, b) -> line(a.second, a.third, b.second, b.third, NAVY, 1.5) }
        points.forEachIndexed { index, item ->
            val (point, x, valueY) = item
            if (point.referenceLower != null || point.referenceUpper != null) {
                val low = point.referenceLower?.let { bottom - geometry.yFraction(it) * height } ?: bottom
                val high = point.referenceUpper?.let { bottom - geometry.yFraction(it) * height } ?: top
                line(x, high, x, low, RANGE_GRAY, 2.0)
            }
            circle(x, valueY, 2.6)
            if (index == points.lastIndex) right(DoctorReportFormat.value(point.point.value, language), min(x + 16.0, left + width), max(top, valueY - 12.0), 7.5, MUTED)
        }
        text(DoctorReportFormat.date(series.points.first().timestamp, language), left, bottom + 12.0, 7.5, MUTED)
        right(DoctorReportFormat.date(series.points.last().timestamp, language), left + width, bottom + 12.0, 7.5, MUTED)
        y = bottom + 30.0
    }

    private fun medications(rows: List<DoctorMedicationRow>) {
        if (rows.isEmpty()) return
        ensureSectionSpace(30.0 + rows.sumOf { medicationHeight(it) }, t.medications)
        sectionHeading(t.medications)
        rows.forEach { row ->
            val height = medicationHeight(row)
            ensureSectionSpace(height, t.medications)
            val detailLines = medicationDetailLines(row)
            val nameLines = wrap(row.displayName, 175.0, 9.0)
            nameLines.forEachIndexed { index, value -> text(value, MARGIN + 6.0, y + 7.0 + index * 12.0, 9.0) }
            detailLines.forEachIndexed { index, value -> text(value, MARGIN + 190.0, y + 5.0 + index * 11.0, 7.5, MUTED) }
            line(MARGIN, y + height, RIGHT, y + height, BORDER, 0.65)
            y += height
        }
    }

    /** Strength and instructions each start on their own line. */
    private fun medicationDetailLines(row: DoctorMedicationRow): List<String> =
        listOfNotNull(row.strength, row.instructions).flatMap { wrap(it, CONTENT_WIDTH - 196.0, 7.5) }

    private fun medicationHeight(row: DoctorMedicationRow): Double {
        val detailLines = medicationDetailLines(row)
        val nameLines = wrap(row.displayName, 175.0, 9.0)
        return maxOf(26.0, maxOf(nameLines.size, detailLines.size) * 12.0 + 12.0)
    }

    private fun sectionHeading(value: String, compact: Boolean = false) {
        sectionName = value
        ensureSpace(if (compact) 35.0 else 30.0)
        text(value, MARGIN, y, if (compact) 9.0 else 12.0, NAVY, bold = !compact)
        y += if (compact) 18.0 else 20.0
        line(MARGIN, y, RIGHT, y, NAVY, 1.6)
        y += 10.0
    }

    private fun ensureSectionSpace(height: Double, name: String) {
        if (y + height > BOTTOM) {
            sectionName = name
            newPage()
        }
    }

    private fun ensureSpace(height: Double) { if (y + height > BOTTOM) newPage() }

    private fun newPage() {
        if (pageNumber > 0) footer()
        pageNumber++
        canvas.newPage()
        rect(0.0, 0.0, pageWidth, pageHeight, PAPER)
        y = MARGIN
        if (pageNumber > 1) {
            text("Vezuna - $sectionName", MARGIN, y, 7.5, MUTED)
            y += 13.0
            line(MARGIN, y, RIGHT, y, NAVY, 1.6)
            y += 12.0
        }
    }

    private fun footer() {
        line(MARGIN, pageHeight - 29.0, RIGHT, pageHeight - 29.0, BORDER, 0.65)
        text("Vezuna - ${t.footer}", MARGIN, pageHeight - 22.0, 7.5, MUTED)
        right("${t.page} $pageNumber", RIGHT, pageHeight - 22.0, 7.5, MUTED)
    }

    private fun wrapped(value: String, x: Double, top: Double, width: Double, size: Double, color: PdfColor = INK, bold: Boolean = false): Double {
        val lines = wrap(value, width, size, bold)
        lines.forEachIndexed { index, item -> text(item, x, top + index * (size + 4.0), size, color, bold) }
        return lines.size * (size + 4.0)
    }

    private fun wrap(value: String, width: Double, size: Double, bold: Boolean = false): List<String> = buildList {
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
                val whitespace = remaining.lastIndexOf(' ', count - 1)
                if (whitespace > 0) count = whitespace
            }
            add(remaining.substring(0, count).trimEnd())
            remaining = remaining.substring(count).trimStart()
        }
        if (isEmpty()) add("")
    }

    private fun measure(value: String, size: Double, bold: Boolean = false) = canvas.measure(value, size, bold)

    private fun text(value: String, x: Double, top: Double, size: Double, color: PdfColor = INK, bold: Boolean = false) =
        canvas.text(value, x, top, size, color, bold)
    private fun right(value: String, x: Double, top: Double, size: Double, color: PdfColor = INK, bold: Boolean = false) =
        text(value, x - measure(value, size, bold), top, size, color, bold)
    private fun center(value: String, x: Double, top: Double, width: Double, size: Double, color: PdfColor = INK, bold: Boolean = false) =
        text(value, x + (width - measure(value, size, bold)) / 2.0, top, size, color, bold)
    private fun line(x1: Double, y1: Double, x2: Double, y2: Double, color: PdfColor, width: Double) = canvas.line(x1, y1, x2, y2, color, width)
    private fun rect(x: Double, top: Double, width: Double, height: Double, color: PdfColor) = canvas.rect(x, top, width, height, color)
    private fun outline(x: Double, top: Double, width: Double, height: Double) = canvas.strokeRect(x, top, width, height, BORDER, 0.65)
    private fun circle(x: Double, y: Double, radius: Double) = canvas.circle(x, y, radius, NAVY)
    private fun statusColor(status: RangeAssessment): PdfColor = when (status) {
        RangeAssessment.WITHIN -> GREEN
        RangeAssessment.BELOW, RangeAssessment.ABOVE -> AMBER
        RangeAssessment.UNKNOWN -> GRAY
    }
    private fun statusContainer(status: RangeAssessment): PdfColor = when (status) {
        RangeAssessment.WITHIN -> PALE_GREEN
        RangeAssessment.BELOW, RangeAssessment.ABOVE -> PALE_AMBER
        RangeAssessment.UNKNOWN -> PALE_GRAY
    }

    private companion object {
        const val MARGIN = 36.0
        const val PAGE_BOTTOM_INSET = 38.0
        const val CHART_HEIGHT = 125.0
        val PAPER = PdfColor(0.992, 0.992, 0.980)
        val INK = PdfColor(0.133, 0.153, 0.169)
        val MUTED = PdfColor(0.357, 0.388, 0.404)
        val NAVY = PdfColor(0.137, 0.239, 0.337)
        val GREEN = PdfColor(0.192, 0.467, 0.306)
        val AMBER = PdfColor(0.616, 0.404, 0.086)
        val GRAY = PdfColor(0.435, 0.463, 0.475)
        val PALE_NAVY = PdfColor(0.902, 0.922, 0.937)
        val PALE_GREEN = PdfColor(0.894, 0.945, 0.902)
        val PALE_AMBER = PdfColor(0.976, 0.933, 0.831)
        val PALE_GRAY = PdfColor(0.929, 0.937, 0.933)
        val HISTORY_ALT = PdfColor(0.969, 0.969, 0.949)
        val BORDER = PdfColor(0.780, 0.804, 0.812)
        val RANGE_GRAY = PdfColor(0.765, 0.812, 0.780)
    }
}
