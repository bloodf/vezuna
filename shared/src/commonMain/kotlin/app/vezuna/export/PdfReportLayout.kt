package app.vezuna.export

import app.vezuna.domain.trends.ChangeSummary
import app.vezuna.domain.trends.ComparisonStatus
import app.vezuna.export.PdfStyle.BODY
import app.vezuna.export.PdfStyle.LABEL
import app.vezuna.export.PdfStyle.MM
import app.vezuna.export.PdfStyle.MUTED
import app.vezuna.export.PdfStyle.NOTE
import app.vezuna.export.PdfStyle.PINE

/**
 * Laboratory-history page layout shared by every platform; platform code only supplies [PdfCanvas] primitives.
 * Pages are laid out twice: a counting pass finds the total so every footer can say "Page X of Y".
 */
class PdfReportLayout(private val canvas: PdfCanvas) {
    fun render(document: DoctorPdfDocument) {
        val counter = CountingCanvas(canvas)
        ReportPass(counter, document, totalPages = 0).run()
        ReportPass(canvas, document, counter.pages).run()
    }
}

/** Measures like the real canvas and draws nothing. */
private class CountingCanvas(private val real: PdfCanvas) : PdfCanvas {
    var pages = 0
    override val pageWidth get() = real.pageWidth
    override val pageHeight get() = real.pageHeight
    override fun newPage() { pages++ }
    override fun measure(text: String, size: Double, bold: Boolean) = real.measure(text, size, bold)
    override fun text(text: String, x: Double, top: Double, size: Double, color: PdfColor, bold: Boolean) = Unit
    override fun line(x1: Double, y1: Double, x2: Double, y2: Double, color: PdfColor, width: Double) = Unit
    override fun rect(x: Double, top: Double, width: Double, height: Double, color: PdfColor) = Unit
    override fun strokeRect(x: Double, top: Double, width: Double, height: Double, color: PdfColor, lineWidth: Double) = Unit
    override fun circle(x: Double, y: Double, radius: Double, color: PdfColor) = Unit
    override fun path(ops: List<PathOp>, color: PdfColor, strokeWidth: Double?) = Unit
}

private class ReportPass(private val canvas: PdfCanvas, private val document: DoctorPdfDocument, private val totalPages: Int) {
    private val pen = PdfPen(canvas, document.grayscale)
    private val language = document.language
    private val t = ExportText.of(language)
    private val chart = PdfHistoryChart(pen, language)
    private val left = 17 * MM
    private val right = canvas.pageWidth - 17 * MM
    private val width = right - left
    private val top = 16 * MM
    private val footerTop = canvas.pageHeight - 16 * MM - 9 * MM
    private val bottom = footerTop - 2 * MM
    private var page = 0
    private var y = 0.0

    fun run() {
        newPage()
        scope()
        val sections = DoctorExamSections.from(document)
        if (sections.any { it.trend != null && it.distinctDates >= 2 }) graphKey()
        if (sections.isEmpty() && document.hasStructuredContent) noResults()
        sections.forEach { section ->
            when {
                section.history.size == 1 -> singleResult(section)
                else -> history(section)
            }
            y += 6 * MM
        }
        medications()
        if (!document.hasStructuredContent && document.lines.isNotEmpty()) notes()
    }

    // region pages

    private fun newPage() {
        page++
        canvas.newPage()
        pen.rect(0.0, 0.0, canvas.pageWidth, canvas.pageHeight, PdfStyle.PAPER)
        if (page == 1) firstHeader() else continuationHeader()
        footer()
    }

    private fun ensure(height: Double): Boolean {
        if (y + height <= bottom) return false
        newPage()
        return true
    }

    private fun firstHeader() {
        y = top + PdfLogo.draw(canvas, left, top, 42 * MM, pen.color(PdfLogo.INK)) + 4 * MM
        y += pen.paragraph(document.title, left, y, width, PdfStyle.TITLE, PINE, bold = true)
        if (document.profileDisplayName.isNotEmpty()) {
            val details = listOfNotNull(
                document.birthDate?.let { "${t.birthDate}: ${DoctorReportFormat.date(it, language)}" },
                document.ageYears?.let { "${t.age}: $it" },
            ).joinToString(" · ")
            val nameWidth = pen.measure(document.profileDisplayName, PdfStyle.PROFILE, bold = true)
            if (details.isNotEmpty() && nameWidth + 8 + pen.measure(details, BODY) <= width) {
                pen.text(document.profileDisplayName, left, y, PdfStyle.PROFILE, PINE, bold = true)
                pen.text(details, left + nameWidth + 8, y + 1.0, BODY, MUTED)
                y += pen.lineHeight(PdfStyle.PROFILE)
            } else {
                y += pen.paragraph(document.profileDisplayName, left, y, width, PdfStyle.PROFILE, PINE, bold = true)
                if (details.isNotEmpty()) y += pen.paragraph(details, left, y, width, BODY, MUTED)
            }
        }
        y += pen.paragraph(period(), left, y, width, BODY)
        DoctorReportFormat.sources(document.resultRows, language)?.let { y += pen.paragraph(it, left, y, width, BODY) }
        y += 2 * MM
    }

    private fun continuationHeader() {
        val logoHeight = PdfLogo.draw(canvas, left, top, 30 * MM, pen.color(PdfLogo.INK))
        val title = document.title + t.titleContinued
        pen.right(title, right, top, LABEL, PINE, bold = true)
        if (document.profileDisplayName.isNotEmpty()) pen.right(document.profileDisplayName, right, top + pen.lineHeight(LABEL), LABEL, MUTED)
        y = top + maxOf(logoHeight, 2 * pen.lineHeight(LABEL)) + 2 * MM
        pen.line(left, y, right, y, PdfStyle.BORDER, PdfStyle.RULE)
        y += 4 * MM
    }

    private fun footer() {
        pen.line(left, footerTop, right, footerTop, PdfStyle.BORDER, PdfStyle.RULE)
        val size = PdfStyle.FOOTER
        pen.text("Vezuna · ${t.footer}", left, footerTop + 3.0, size, MUTED)
        if (totalPages > 0) {
            pen.right(t.pageOf.replace("{page}", page.toString()).replace("{total}", totalPages.toString()), right, footerTop + 3.0, size, MUTED)
        }
        pen.text("${t.generated} ${DoctorReportFormat.dateTime(document.generatedAt, language)}", left, footerTop + 3.0 + size * 1.4, size, MUTED)
    }

    private fun period(): String {
        val scope = document.scope
        val first = document.resultRows.minOfOrNull { it.collectedAt }
        val last = document.resultRows.maxOfOrNull { it.collectedAt }
        if (scope.selectedFrom != null || scope.selectedTo != null) {
            val from = scope.selectedFrom?.let { DoctorReportFormat.date(it, language) } ?: first?.let { DoctorReportFormat.date(it, language) } ?: "…"
            val to = scope.selectedTo?.let { DoctorReportFormat.date(it, language) } ?: last?.let { DoctorReportFormat.date(it, language) } ?: "…"
            return "${t.selectedPeriod}: $from – $to"
        }
        return "${t.collectionPeriod}: ${DoctorReportFormat.period(document.resultRows, language)}"
    }

    // endregion

    // region scope, key, empty states

    private fun scope() {
        if (!document.hasStructuredContent) return
        val tests = DoctorExamSections.from(document).size
        y += pen.paragraph(
            t.includedCounts.replace("{results}", document.resultRows.size.toString()).replace("{tests}", tests.toString()),
            left, y, width, BODY,
        )
        if (document.scope.excludedUnreviewed > 0) {
            y += pen.paragraph(t.excludedUnreviewed.replace("{count}", document.scope.excludedUnreviewed.toString()), left, y, width, BODY)
        }
        y += pen.paragraph(t.scopeNote, left, y, width, NOTE, MUTED)
        y += 3 * MM
        pen.line(left, y, right, y, PdfStyle.BORDER, PdfStyle.RULE)
        y += 4 * MM
    }

    private fun graphKey() {
        ensure(pen.lineHeight(LABEL) + pen.paragraphHeight(t.graphKey, width, NOTE))
        pen.text(t.graphKeyTitle, left, y, LABEL, PINE, bold = true)
        y += pen.lineHeight(LABEL)
        y += pen.paragraph(t.graphKey, left, y, width, NOTE, MUTED) + 6 * MM
    }

    private fun noResults() {
        sectionTitle(t.laboratoryResults, PdfStyle.MINT, extra = 3 * pen.lineHeight(BODY))
        y += pen.paragraph(t.noResultsInPeriod, left, y, width, BODY, bold = true)
        val first = document.scope.availableFirst
        val last = document.scope.availableLast
        if (first != null && last != null) {
            val range = if (first == last) DoctorReportFormat.date(first, language) else "${DoctorReportFormat.date(first, language)} – ${DoctorReportFormat.date(last, language)}"
            y += pen.paragraph("${t.availableHistory}: $range", left, y, width, BODY, MUTED)
        }
        y += 6 * MM
    }

    // endregion

    // region test sections

    /** Section heading with a category accent; [extra] keeps that much following content on the same page. */
    private fun sectionTitle(title: String, accent: PdfColor, trailing: String? = null, extra: Double = 0.0) {
        val trailingWidth = trailing?.let { minOf(pen.measure(it, PdfStyle.SECTION, bold = true), width * 0.45) } ?: 0.0
        val titleWidth = width - 4 * MM - (if (trailing == null) 0.0 else trailingWidth + 6 * MM)
        val titleHeight = pen.paragraphHeight(title, titleWidth, PdfStyle.SECTION, bold = true)
        val trailingHeight = trailing?.let { pen.paragraphHeight(it, trailingWidth, PdfStyle.SECTION, bold = true) } ?: 0.0
        val height = maxOf(titleHeight, trailingHeight)
        ensure(height + 2 * MM + extra)
        pen.rect(left, y + 1.0, 1.2 * MM, height - 2.0, accent)
        pen.paragraph(title, left + 4 * MM, y, titleWidth, PdfStyle.SECTION, PINE, bold = true)
        trailing?.let { value ->
            pen.wrap(value, trailingWidth, PdfStyle.SECTION, bold = true).forEachIndexed { index, line ->
                pen.right(line, right, y + index * pen.lineHeight(PdfStyle.SECTION), PdfStyle.SECTION, PINE, bold = true)
            }
        }
        y += height + 2 * MM
    }

    /** "Recorded reference: …" on the left, the textual comparison on the right; both wrap, neither truncates. */
    private fun referenceLine(reference: String, status: String) {
        val statusWidth = minOf(pen.measure(status, BODY), width * 0.4)
        val referenceWidth = width - statusWidth - 6 * MM
        val height = maxOf(pen.paragraphHeight(reference, referenceWidth, BODY), pen.paragraphHeight(status, statusWidth, BODY))
        pen.paragraph(reference, left, y, referenceWidth, BODY)
        pen.wrap(status, statusWidth, BODY).forEachIndexed { index, line -> pen.right(line, right, y + index * pen.lineHeight(BODY), BODY) }
        y += height + 2 * MM
    }

    private fun referenceText(section: DoctorExamSection): String {
        val ranges = section.history.map { DoctorExportContent.rangeText(it.referenceRange, language) }.distinct()
        return "${t.recordedReference}: ${ranges.singleOrNull() ?: t.referenceVaries}"
    }

    private fun history(section: DoctorExamSection) {
        val latest = DoctorReportFormat.originalValue(section.latest, language)
        val trailing = if (section.isText) latest else "${t.latest}: $latest"
        val reference = referenceText(section)
        val status = DoctorReportFormat.assessment(section.latest.assessment, language)
        val referenceHeight = pen.paragraphHeight(reference, width * 0.55, BODY) + 2 * MM
        val drawChart = section.trend != null && section.distinctDates >= 2
        val keep = referenceHeight + if (drawChart) chart.height() + comparisonHeight(section) else tableHeaderHeight() + 2 * rowHeight(section.history.first())
        sectionTitle(section.testName, PdfStyle.MINT, trailing, extra = keep)
        referenceLine(reference, status)
        when {
            drawChart -> {
                if (section.usesNormalizedUnit) y += pen.paragraph(t.chartUnitNote.replace("{unit}", section.unit), left, y, width, NOTE, MUTED) + 1 * MM
                val result = chart.draw(section.trend!!, left, y, width, section.decimals)
                y += result.height
                if (result.labelsOmitted) y += pen.paragraph(t.labelsOmitted, left, y, width, NOTE, MUTED)
                y += 2 * MM
                comparisons(section)
            }
            section.isText -> y += pen.paragraph(t.textResult, left, y, width, NOTE, MUTED) + 2 * MM
            else -> y += pen.paragraph(t.sameDay, left, y, width, NOTE, MUTED) + 2 * MM
        }
        table(section)
    }

    private fun comparisonHeight(section: DoctorExamSection): Double {
        val column = (width - 6 * MM) / 2
        val comparison = section.comparison
        val values = listOf(changeText(section, comparison?.sinceFirst), changeText(section, comparison?.sincePrevious))
        return pen.lineHeight(LABEL) + values.maxOf { pen.paragraphHeight(it, column, BODY) } + 4 * MM
    }

    /** A signed difference with its baseline date, or the localized reason it is unavailable; never zero. */
    private fun changeText(section: DoctorExamSection, change: ChangeSummary?): String {
        if (change == null) {
            return when (section.comparison?.status) {
                ComparisonStatus.NOT_ENOUGH_READINGS -> t.notEnoughReadings
                ComparisonStatus.NO_READING -> t.noReading
                else -> t.notAvailable
            }
        }
        val baseline = if (change.sameDay) t.sameCollectionDate else "${t.fromDate} ${DoctorReportFormat.date(change.fromDate, language)}"
        return "${DoctorReportFormat.signedChange(change.delta, section.decimals, change.unit, language)} ($baseline)"
    }

    private fun comparisons(section: DoctorExamSection) {
        val height = comparisonHeight(section)
        if (ensure(height)) carriedTitle(section.testName, t.chartContinued)
        val column = (width - 6 * MM) / 2
        val comparison = section.comparison
        listOf(t.changeSinceFirst to comparison?.sinceFirst, t.changeSincePrevious to comparison?.sincePrevious).forEachIndexed { index, (label, change) ->
            val x = left + index * (column + 6 * MM)
            pen.text(label, x, y, LABEL, PINE, bold = true)
            pen.paragraph(changeText(section, change), x, y + pen.lineHeight(LABEL), column, BODY)
        }
        y += height
    }

    /** Identifies content that continues on a new page: test name plus what continues. */
    private fun carriedTitle(testName: String, what: String) {
        y += pen.paragraph("$testName · $what", left, y, width, LABEL, PINE, bold = true) + 1 * MM
    }

    private fun singleResult(section: DoctorExamSection) {
        val row = section.latest
        val lines = buildList {
            add("${t.collected} ${DoctorReportFormat.date(row.collectedAt, language)}")
            DoctorReportFormat.normalizedValue(row, language)?.let { add(it) }
            add("${t.source}: ${sourceText(row)}")
        }
        val note = if (section.isText) t.textResult else t.oneResult
        val reference = referenceText(section)
        val status = DoctorReportFormat.assessment(row.assessment, language)
        val body = lines.sumOf { pen.paragraphHeight(it, width - 4 * MM, BODY) } +
            pen.paragraphHeight(reference, width * 0.55, BODY) + pen.paragraphHeight(note, width - 4 * MM, NOTE) + 2 * MM
        sectionTitle(section.testName, PdfStyle.MINT, DoctorReportFormat.originalValue(row, language), extra = body)
        val indent = left + 4 * MM
        y += pen.paragraph(lines.first(), indent, y, width - 4 * MM, BODY)
        val start = y
        val statusWidth = minOf(pen.measure(status, BODY), width * 0.4)
        val referenceHeight = pen.paragraph(reference, indent, y, width - 4 * MM - statusWidth - 6 * MM, BODY)
        pen.wrap(status, statusWidth, BODY).forEachIndexed { index, line -> pen.right(line, right, start + index * pen.lineHeight(BODY), BODY) }
        y += maxOf(referenceHeight, pen.paragraphHeight(status, statusWidth, BODY))
        lines.drop(1).forEach { y += pen.paragraph(it, indent, y, width - 4 * MM, BODY) }
        y += pen.paragraph(note, indent, y, width - 4 * MM, NOTE, MUTED)
    }

    private fun sourceText(row: DoctorResultRow): String =
        listOfNotNull(row.laboratoryName, row.sourceName).distinct().joinToString(" · ").ifEmpty { if (row.manualEntry) t.enteredManually else t.notRecorded }

    // endregion

    // region history table

    private val columnShares = listOf(32.0, 34.0, 44.0, 66.0)
    private val columns get() = columnShares.map { it / columnShares.sum() * width }
    private val padX = 2 * MM
    private val padY = 3 * MM
    private val minRow = 10 * MM

    private val headerLabels get() = listOf(t.date, t.result, t.recordedReference, t.source)

    /** Labels wrap inside their column (long PT/ES headings) instead of running into the next one. */
    private fun tableHeaderHeight() =
        headerLabels.mapIndexed { index, label -> pen.paragraphHeight(label, columns[index] - 2 * padX, LABEL, bold = true) }.max() + 2 * padY

    private fun cells(row: DoctorResultRow): List<List<Pair<String, Boolean>>> = listOf(
        listOf(DoctorReportFormat.date(row.collectedAt, language) to false),
        listOfNotNull(
            DoctorReportFormat.originalValue(row, language) to false,
            DoctorReportFormat.normalizedValue(row, language)?.let { it to true },
        ),
        listOf(DoctorExportContent.rangeText(row.referenceRange, language) to false, DoctorReportFormat.assessment(row.assessment, language) to true),
        listOf(sourceText(row) to false),
    )

    private fun rowHeight(row: DoctorResultRow): Double {
        val content = cells(row).mapIndexed { index, cell ->
            cell.sumOf { (value, secondary) -> pen.paragraphHeight(value, columns[index] - 2 * padX, if (secondary) NOTE else BODY) }
        }.max()
        return maxOf(minRow, content + 2 * padY)
    }

    private fun tableHeader() {
        val height = tableHeaderHeight()
        pen.rect(left, y, width, height, PdfStyle.PALE)
        var x = left
        headerLabels.forEachIndexed { index, label ->
            pen.paragraph(label, x + padX, y + padY, columns[index] - 2 * padX, LABEL, PINE, bold = true)
            x += columns[index]
        }
        y += height
        pen.line(left, y, right, y, PdfStyle.MUTED, PdfStyle.RULE)
    }

    private fun table(section: DoctorExamSection) {
        // Header plus the first two rows stay together.
        if (ensure(tableHeaderHeight() + section.history.take(2).sumOf { rowHeight(it) })) carriedTitle(section.testName, t.tableContinued)
        tableHeader()
        section.history.forEach { row ->
            val height = rowHeight(row)
            // ponytail: a single row taller than a whole page would overflow; needs a split-row path if reference texts ever get that long.
            if (ensure(height)) {
                carriedTitle(section.testName, t.tableContinued)
                tableHeader()
            }
            var x = left
            cells(row).forEachIndexed { index, cell ->
                var cellY = y + padY
                cell.forEach { (value, secondary) ->
                    cellY += pen.paragraph(value, x + padX, cellY, columns[index] - 2 * padX, if (secondary) NOTE else BODY, if (secondary) MUTED else PdfStyle.INK)
                }
                x += columns[index]
            }
            y += height
            pen.line(left, y, right, y, PdfStyle.BORDER, PdfStyle.RULE)
        }
        y += 2 * MM
    }

    // endregion

    // region medications and notes

    private fun medicationDetails(row: DoctorMedicationRow): String = listOfNotNull(
        row.strength?.let { "${t.strength}: $it" },
        row.instructions?.let { "${t.instructions}: $it" },
        row.times.takeIf { it.isNotEmpty() }?.let { "${t.scheduledTimes}: ${it.joinToString(", ")}" },
        if (row.active) t.active else t.inactive,
        row.startDate?.let { "${t.startDate}: ${DoctorReportFormat.date(it, language)}" },
    ).joinToString(" · ")

    private fun medicationHeight(row: DoctorMedicationRow) =
        pen.paragraphHeight(row.displayName, width, BODY, bold = true) + pen.paragraphHeight(medicationDetails(row), width, BODY) + 3 * MM

    private fun medications() {
        if (!document.hasStructuredContent) return
        val rows = document.medicationRows
        val first = when {
            !document.scope.medicationsIncluded -> pen.lineHeight(BODY)
            rows.isEmpty() -> pen.lineHeight(BODY)
            else -> medicationHeight(rows.first())
        }
        sectionTitle(t.recordedMedication, PdfStyle.CORAL, extra = first)
        if (!document.scope.medicationsIncluded || rows.isEmpty()) {
            y += pen.paragraph(if (!document.scope.medicationsIncluded) t.notSelected else t.noMedications, left, y, width, BODY)
            return
        }
        rows.forEach { row ->
            ensure(medicationHeight(row))
            y += pen.paragraph(row.displayName, left, y, width, BODY, bold = true)
            y += pen.paragraph(medicationDetails(row), left, y, width, BODY) + 1 * MM
            pen.line(left, y, right, y, PdfStyle.BORDER, PdfStyle.RULE)
            y += 2 * MM
        }
        ensure(pen.paragraphHeight(t.medicationNote, width, NOTE))
        y += pen.paragraph(t.medicationNote, left, y, width, NOTE, MUTED)
    }

    /** Documents built from plain lines only (no structured rows): print every line, then any multi-point graphs. */
    private fun notes() {
        sectionTitle(t.reportNotes, PdfStyle.MINT, extra = pen.lineHeight(BODY))
        document.lines.forEach { line ->
            val height = pen.paragraphHeight(line, width, BODY)
            ensure(minOf(height, pen.lineHeight(BODY)))
            pen.wrap(line, width, BODY).forEach { part ->
                ensure(pen.lineHeight(BODY))
                pen.text(part, left, y, BODY)
                y += pen.lineHeight(BODY)
            }
        }
        document.trends.filter { it.points.size >= 2 }.forEach { series ->
            ensure(pen.lineHeight(LABEL) + chart.height())
            pen.text(series.testName, left, y, LABEL, PINE, bold = true)
            y += pen.lineHeight(LABEL)
            y += chart.draw(series, left, y, width).height
        }
    }

    // endregion
}
