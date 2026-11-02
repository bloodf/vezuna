package app.vezuna.export

import app.vezuna.domain.DoseEventStatus
import app.vezuna.domain.ReferenceRange
import app.vezuna.domain.VitalMetric
import app.vezuna.domain.trends.ChangeSummary
import app.vezuna.domain.trends.ComparisonStatus
import app.vezuna.domain.trends.TrendComparison
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
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
    private val summaryChart = PdfHistoryChart(pen, language, plotHeight = 40 * MM)
    private val timeline = PdfDoseTimeline(pen, language)
    private val dailyChart = PdfDailyChart(pen, language)
    private val left = 17 * MM
    private val right = canvas.pageWidth - 17 * MM
    private val width = right - left
    private val top = 16 * MM
    private val footerTop = canvas.pageHeight - 16 * MM - 9 * MM
    private val bottom = footerTop - 2 * MM
    private var page = 0
    private var y = 0.0
    private val sections = DoctorExamSections.from(document)

    private val s = ExportSectionText.of(language)

    fun run() {
        newPage()
        if (document.scope.labsIncluded) labs()
        // A device-only export leaves medications out entirely instead of printing "Not selected".
        if (document.family != ExportFamily.DEVICE_MEASUREMENTS || document.scope.medicationsIncluded) medications()
        if (document.scope.doseHistoryIncluded && document.hasStructuredContent) doseHistory()
        if (document.scope.deviceIncluded && document.hasStructuredContent) device()
        if (document.scope.sourceAppendixIncluded && document.hasStructuredContent) sourceAppendix()
        if (!document.hasStructuredContent && document.lines.isNotEmpty()) notes()
    }

    private fun labs() {
        scope()
        if (sections.isEmpty() && document.hasStructuredContent) noResults()
        if (document.family == ExportFamily.HEALTH_SUMMARY) {
            summaryCharts()
            if (sections.isNotEmpty()) latestResults()
            return
        }
        if (sections.any { it.trend != null && it.distinctDates >= 2 }) graphKey()
        sections.forEach { section ->
            when {
                section.history.size == 1 -> singleResult(section)
                else -> history(section)
            }
            y += 6 * MM
        }
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
        val bounded = document.scope.selectedFrom != null || document.scope.selectedTo != null
        if (document.scope.labsIncluded || bounded) y += pen.paragraph(period(), left, y, width, BODY)
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
        val tests = sections.size
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

    /** Same rule as the chart: one shared numeric range prints as that range even when the reports word it differently. */
    private fun referenceText(section: DoctorExamSection): String {
        val ranges = section.history.map { DoctorExportContent.rangeText(it.referenceRange, language, rangeDecimals(section)) }.distinct()
        ranges.singleOrNull()?.let { return "${t.recordedReference}: $it" }
        val bounds = section.history.map { Triple(it.referenceRange.lower, it.referenceRange.upper, it.referenceRange.unit) }.distinct()
        val shared = bounds.singleOrNull()?.takeIf { it.first != null || it.second != null }
        // Shared bounds keep the precision the reports print (12.0–16.0), not the shortest number (12–16).
        val text = shared?.let { DoctorExportContent.rangeText(ReferenceRange(it.first, it.second, unit = it.third), language, rangeDecimals(section)) } ?: t.referenceVaries
        return "${t.recordedReference}: $text"
    }

    /** Fraction digits the reports print for this section's bounds: from the range text when present, else the values'. */
    private fun rangeDecimals(section: DoctorExamSection): Int {
        val printed = section.history.mapNotNull { it.referenceRange.text }.flatMap { Regex("\\d+[.,](\\d+)").findAll(it).map { m -> m.groupValues[1].length }.toList() }
        return printed.maxOrNull() ?: section.decimals
    }

    private fun history(section: DoctorExamSection) {
        val latest = DoctorReportFormat.originalValue(section.latest, language)
        val trailing = if (section.isText) latest else "${t.latest}: $latest"
        val reference = referenceText(section)
        val status = DoctorReportFormat.assessment(section.latest.assessment, language)
        val referenceHeight = pen.paragraphHeight(reference, width * 0.55, BODY) + 2 * MM
        val drawChart = section.trend != null && section.distinctDates >= 2
        // The unit note and a possible omitted-labels note sit between title and comparisons, so they count too.
        val notes = if (!drawChart) 0.0 else pen.paragraphHeight(t.labelsOmitted, width, NOTE) +
            (if (section.usesNormalizedUnit) pen.paragraphHeight(t.chartUnitNote.replace("{unit}", section.unit), width, NOTE) + 1 * MM else 0.0)
        val keep = referenceHeight + notes + if (drawChart) chart.height() + comparisonHeight(section) else tableHeaderHeight() + 2 * rowHeight(section.history.first())
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
    private fun changeText(section: DoctorExamSection, change: ChangeSummary?): String = changeText(section.comparison, change, section.decimals)

    private fun changeText(comparison: TrendComparison?, change: ChangeSummary?, decimals: Int): String {
        if (change == null) {
            return when (comparison?.status) {
                ComparisonStatus.NOT_ENOUGH_READINGS -> t.notEnoughReadings
                ComparisonStatus.NO_READING -> t.noReading
                else -> t.notAvailable
            }
        }
        val baseline = if (change.sameDay) t.sameCollectionDate else "${t.fromDate} ${DoctorReportFormat.date(change.fromDate, language)}"
        return "${DoctorReportFormat.signedChange(change.delta, decimals, change.unit, language)} ($baseline)"
    }

    private fun comparisons(section: DoctorExamSection) {
        val height = comparisonHeight(section)
        if (ensure(height)) carriedTitle(section.testName, t.changesContinued)
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
    private fun tableHeaderHeight() = gridHeaderHeight(headerLabels, columns)

    private fun cells(row: DoctorResultRow): List<List<Pair<String, Boolean>>> = listOf(
        listOf(DoctorReportFormat.date(row.collectedAt, language) to false),
        listOfNotNull(
            DoctorReportFormat.originalValue(row, language) to false,
            DoctorReportFormat.normalizedValue(row, language)?.let { it to true },
        ),
        listOf(DoctorExportContent.rangeText(row.referenceRange, language, row.valueDecimals) to false, DoctorReportFormat.assessment(row.assessment, language) to true),
        listOf(sourceText(row) to false),
    )

    private fun rowHeight(row: DoctorResultRow): Double = gridRowHeight(cells(row), columns)

    private fun table(section: DoctorExamSection) = grid(section.testName, headerLabels, columns, section.history.map(::cells))

    private fun widths(vararg shares: Double) = shares.map { it / shares.sum() * width }

    private fun gridHeaderHeight(labels: List<String>, widths: List<Double>) =
        labels.mapIndexed { index, label -> pen.paragraphHeight(label, widths[index] - 2 * padX, LABEL, bold = true) }.max() + 2 * padY

    /** Each cell is lines of (text, secondary); secondary lines print smaller and muted. */
    private fun gridRowHeight(cells: List<List<Pair<String, Boolean>>>, widths: List<Double>): Double {
        val content = cells.mapIndexed { index, cell ->
            cell.sumOf { (value, secondary) -> pen.paragraphHeight(value, widths[index] - 2 * padX, if (secondary) NOTE else BODY) }
        }.max()
        return maxOf(minRow, content + 2 * padY)
    }

    private fun gridHeader(labels: List<String>, widths: List<Double>) {
        val height = gridHeaderHeight(labels, widths)
        pen.rect(left, y, width, height, PdfStyle.PALE)
        var x = left
        labels.forEachIndexed { index, label ->
            pen.paragraph(label, x + padX, y + padY, widths[index] - 2 * padX, LABEL, PINE, bold = true)
            x += widths[index]
        }
        y += height
        pen.line(left, y, right, y, PdfStyle.MUTED, PdfStyle.RULE)
    }

    /** A table whose header repeats on every page it continues onto, under a "[carry] · continued" line. */
    private fun grid(carry: String, labels: List<String>, widths: List<Double>, rows: List<List<List<Pair<String, Boolean>>>>, what: String = t.tableContinued) {
        // Header plus the first two rows stay together.
        if (ensure(gridHeaderHeight(labels, widths) + rows.take(2).sumOf { gridRowHeight(it, widths) })) carriedTitle(carry, what)
        gridHeader(labels, widths)
        rows.forEach { row ->
            val height = gridRowHeight(row, widths)
            // ponytail: a single row taller than a whole page would overflow; needs a split-row path if reference texts ever get that long.
            if (ensure(height)) {
                carriedTitle(carry, what)
                gridHeader(labels, widths)
            }
            var x = left
            row.forEachIndexed { index, cell ->
                var cellY = y + padY
                cell.forEach { (value, secondary) ->
                    cellY += pen.paragraph(value, x + padX, cellY, widths[index] - 2 * padX, if (secondary) NOTE else BODY, if (secondary) MUTED else PdfStyle.INK)
                }
                x += widths[index]
            }
            y += height
            pen.line(left, y, right, y, PdfStyle.BORDER, PdfStyle.RULE)
        }
        y += 2 * MM
    }

    // endregion

    // region health summary, dose history, device

    /** Health summary: one row per test with its latest confirmed result, recorded reference and text comparison. */
    private fun latestResults() {
        val labels = listOf(s.test, s.latestResult, t.recordedReference, s.comparison)
        val shares = widths(37.0, 39.0, 43.0, 57.0)
        val rows = sections.map { section ->
            val latest = section.latest
            listOf(
                listOf(section.testName to false),
                listOf(DoctorReportFormat.originalValue(latest, language) to false, DoctorReportFormat.date(latest.collectedAt, language) to true),
                listOf(DoctorExportContent.rangeText(latest.referenceRange, language, latest.valueDecimals) to false),
                listOf(DoctorReportFormat.assessment(latest.assessment, language) to false),
            )
        }
        sectionTitle(s.latestResults, PdfStyle.MINT, extra = gridHeaderHeight(labels, shares) + rows.take(2).sumOf { gridRowHeight(it, shares) })
        grid(s.latestResults, labels, shares, rows)
        y += 4 * MM
    }

    /** pdf-01: every test with two or more collection dates as a half-width graph with its first/previous changes. */
    private fun summaryCharts() {
        val charted = sections.filter { it.trend != null && it.distinctDates >= 2 }
        if (charted.isEmpty()) return
        val gap = 6 * MM
        val column = (width - gap) / 2
        charted.chunked(2).forEach { pair ->
            val height = pair.maxOf { summaryHeight(it, column) }
            ensure(height)
            val omitted = pair.filterIndexed { index, section -> summaryBlock(section, left + index * (column + gap), y, column) }
            y += height + 4 * MM
            // The latest-results table holds only the latest row, so a graph that dropped labels gets its readings as text.
            omitted.forEach { section ->
                ensure(pen.lineHeight(LABEL) + tableHeaderHeight() + 2 * rowHeight(section.history.first()))
                carriedTitle(section.testName, s.readings)
                table(section)
            }
        }
        ensure(pen.paragraphHeight(t.graphKey, width, NOTE))
        y += pen.paragraph(t.graphKey, left, y, width, NOTE, MUTED) + 4 * MM
    }

    private fun summaryChanges(section: DoctorExamSection) = listOf(
        t.changeSinceFirst to changeText(section, section.comparison?.sinceFirst),
        t.changeSincePrevious to changeText(section, section.comparison?.sincePrevious),
    )

    private fun summaryHeight(section: DoctorExamSection, column: Double): Double =
        pen.paragraphHeight(section.testName, column, PdfStyle.SECTION, bold = true) + summaryChart.height() + pen.paragraphHeight(t.labelsOmitted, column, NOTE) +
            summaryChanges(section).sumOf { (label, value) -> pen.paragraphHeight(label, column, LABEL, bold = true) + pen.paragraphHeight(value, column, BODY) }

    /** Draws one summary graph with its changes; true when some of its labels were left out. */
    private fun summaryBlock(section: DoctorExamSection, x: Double, top: Double, column: Double): Boolean {
        var cursor = top + pen.paragraph(section.testName, x, top, column, PdfStyle.SECTION, PINE, bold = true)
        val result = summaryChart.draw(section.trend!!, x, cursor, column, section.decimals)
        cursor += result.height
        cursor += if (result.labelsOmitted) pen.paragraph(t.labelsOmitted, x, cursor, column, NOTE, MUTED) else pen.paragraphHeight(t.labelsOmitted, column, NOTE)
        summaryChanges(section).forEach { (label, value) ->
            cursor += pen.paragraph(label, x, cursor, column, LABEL, PINE, bold = true)
            cursor += pen.paragraph(value, x, cursor, column, BODY)
        }
        return result.labelsOmitted
    }

    private fun doseStatus(row: DoctorDoseRow): List<Pair<String, Boolean>> {
        val status = when (row.status) {
            DoseEventStatus.TAKEN -> s.taken
            DoseEventStatus.SKIPPED -> s.skipped
            else -> s.missed
        }
        return listOfNotNull(status to false, row.recordedTime?.let { s.recordedAt.replace("{time}", it) to true })
    }

    /** #56: every recorded dose in the period with plain counts; no percentage, no trend. */
    private fun doseHistory() {
        y += 4 * MM
        val rows = document.doseRows
        val labels = listOf(t.date, s.scheduled, s.medication, s.status)
        val shares = widths(34.0, 26.0, 60.0, 56.0)
        val counts = s.doseCounts.replace("{taken}", rows.count { it.status == DoseEventStatus.TAKEN }.toString())
            .replace("{skipped}", rows.count { it.status == DoseEventStatus.SKIPPED }.toString())
            .replace("{missed}", rows.count { it.status == DoseEventStatus.MISSED }.toString())
        sectionTitle(s.doseHistory, PdfStyle.CORAL, extra = pen.paragraphHeight(counts, width, BODY) * 2)
        if (rows.isEmpty()) {
            y += pen.paragraph(s.noDoses, left, y, width, BODY)
            return
        }
        y += pen.paragraph(counts, left, y, width, BODY, bold = true)
        y += pen.paragraph(s.missedNote, left, y, width, NOTE, MUTED) + 2 * MM
        doseTimeline(rows)
        grid(s.doseHistory, labels, shares, rows.map { row ->
            listOf(listOf(DoctorReportFormat.date(row.date, language) to false), listOf(row.time to false), listOf(row.medication to false), doseStatus(row))
        })
    }

    /** pdf-07: a seven-column categorical row per week of the period, one timeline per medication. */
    private fun doseTimeline(rows: List<DoctorDoseRow>) {
        val days = generateSequence(rows.minOf { it.date }) { it.plus(1, DateTimeUnit.DAY) }.takeWhile { it <= rows.maxOf { row -> row.date } }.toList()
        val medications = rows.map { it.medication }.distinct()
        ensure(pen.lineHeight(LABEL) + timeline.height(days.take(PdfDoseTimeline.DAYS), rows))
        pen.text(s.doseTimeline, left, y, LABEL, PINE, bold = true)
        y += pen.lineHeight(LABEL) + 1 * MM
        medications.forEach { name ->
            val mine = rows.filter { it.medication == name }
            if (medications.size > 1) {
                ensure(pen.lineHeight(BODY) + timeline.height(days.take(PdfDoseTimeline.DAYS), mine))
                y += pen.paragraph(name, left, y, width, BODY, bold = true)
            }
            days.chunked(PdfDoseTimeline.DAYS).forEach { week ->
                if (ensure(timeline.height(week, mine))) carriedTitle(s.doseTimeline, name)
                y += timeline.draw(week, mine, left, y, width) + 2 * MM
            }
        }
        y += 2 * MM
    }

    /** The device axis: the selected period, or the readings' own span; long selections fall back to the span. */
    private fun deviceDays(rows: List<DoctorDeviceRow>): List<LocalDate> {
        fun span(from: LocalDate, to: LocalDate) = generateSequence(from) { it.plus(1, DateTimeUnit.DAY) }.takeWhile { it <= to }.toList()
        val first = rows.minOf { it.date }
        val last = rows.maxOf { it.date }
        val selected = span(document.scope.selectedFrom ?: first, document.scope.selectedTo ?: last)
        // ponytail: past ~3 months bars get hairline thin; the readings' span keeps them readable, the table keeps every value.
        return if (selected.size in 1..MAX_DEVICE_DAYS) selected else span(first, last)
    }

    /** Daily steps, daily average heart rate and weight readings: summary, graph and dated table per metric. */
    private fun device() {
        y += 4 * MM
        val rows = document.deviceRows
        val notes = listOfNotNull(
            s.deviceNote,
            document.scope.deviceLastSync?.let { s.lastImport.replace("{date}", DoctorReportFormat.dateTime(it, language)) },
            s.deviceDays.replace("{days}", rows.map { it.date }.distinct().size.toString()).takeIf { rows.isNotEmpty() },
        )
        // Every intro note is reserved with the title, so none of them can reach the footer.
        sectionTitle(s.deviceMeasurements, PdfStyle.CITRON, extra = notes.sumOf { pen.paragraphHeight(it, width, NOTE) } + 4 * MM + pen.lineHeight(BODY))
        notes.forEach { y += pen.paragraph(it, left, y, width, NOTE, MUTED) }
        y += 2 * MM
        if (rows.isEmpty()) {
            y += pen.paragraph(s.noDeviceData, left, y, width, BODY)
            return
        }
        val days = deviceDays(rows)
        listOf(VitalMetric.STEPS to s.steps, VitalMetric.HEART_RATE_BPM to s.heartRate, VitalMetric.WEIGHT_KG to s.weight).forEach { (metric, title) ->
            val metricRows = rows.filter { it.metric == metric }
            if (metricRows.isEmpty()) return@forEach
            val labels = if (metric == VitalMetric.WEIGHT_KG) listOf(t.date, s.time, s.value) else listOf(t.date, s.value)
            val shares = if (metric == VitalMetric.WEIGHT_KG) widths(40.0, 30.0, 60.0) else widths(40.0, 90.0)
            val (unit, decimals) = when (metric) {
                VitalMetric.STEPS -> s.stepsUnit to 0
                VitalMetric.HEART_RATE_BPM -> "bpm" to 0
                // Weight keeps the precision it was stored with (68.35 stays 68.35), up to grams; heart rate is a computed average.
                VitalMetric.WEIGHT_KG -> "kg" to metricRows.maxOf { minOf(DoctorReportFormat.decimalsOf(it.value, null), 3) }.coerceAtLeast(1)
            }
            val comparison = deviceComparison(metricRows, unit, decimals)
            val latest = metricRows.last()
            val latestText = "${t.latest}: ${DoctorReportFormat.fixed(latest.value, decimals, language)} $unit (${DoctorReportFormat.date(latest.date, language)})"
            val changes = listOf(t.changeSinceFirst to comparison.sinceFirst, t.changeSincePrevious to comparison.sincePrevious)
                .map { (label, change) -> label to changeText(comparison, change, decimals) }
            val column = (width - 6 * MM) / 2
            val changeHeight = pen.lineHeight(LABEL) + changes.maxOf { pen.paragraphHeight(it.second, column, BODY) } + 2 * MM
            sectionTitle(title, PdfStyle.CITRON, extra = pen.lineHeight(BODY) + changeHeight + dailyChart.height() + pen.paragraphHeight(t.labelsOmitted, width, NOTE))
            y += pen.paragraph(latestText, left, y, width, BODY, bold = true) + 1 * MM
            changes.forEachIndexed { index, (label, value) ->
                val x = left + index * (column + 6 * MM)
                pen.text(label, x, y, LABEL, PINE, bold = true)
                pen.paragraph(value, x, y + pen.lineHeight(LABEL), column, BODY)
            }
            y += changeHeight
            val omitted = dailyChart.draw(days, metricRows.map { it.date to it.value }, metric == VitalMetric.STEPS, unit, decimals, left, y, width)
            y += dailyChart.height()
            if (omitted) y += pen.paragraph(t.labelsOmitted, left, y, width, NOTE, MUTED)
            y += 2 * MM
            grid(title, labels, shares, metricRows.map { row ->
                val value = when (metric) {
                    VitalMetric.STEPS -> "${row.value.toLong()} ${s.stepsUnit}"
                    VitalMetric.HEART_RATE_BPM -> "${DoctorReportFormat.fixed(row.value, 0, language)} bpm"
                    VitalMetric.WEIGHT_KG -> "${DoctorReportFormat.fixed(row.value, decimals, language)} kg"
                }
                listOfNotNull(listOf(DoctorReportFormat.date(row.date, language) to false), row.time?.let { listOf(it to false) }, listOf(value to false))
            })
            y += 2 * MM
        }
    }

    // endregion

    // region medications and notes

    private fun medicationDetails(row: DoctorMedicationRow): String = listOfNotNull(
        row.strength?.let { "${t.strength}: $it" },
        row.instructions?.let { "${t.instructions}: $it" },
        row.times.takeIf { it.isNotEmpty() }?.let { "${t.scheduledTimes}: ${it.joinToString(", ")}" },
        if (row.active) t.active else t.inactive,
        row.startDate?.let { "${t.startDate}: ${DoctorReportFormat.date(it, language)}" },
        row.startDate?.let { row.endDate?.let { end -> "${s.endDate}: ${DoctorReportFormat.date(end, language)}" } ?: s.noEndDate },
    ).joinToString(" · ")

    private fun medicationHeight(row: DoctorMedicationRow) =
        pen.paragraphHeight(row.displayName, width, BODY, bold = true) + pen.paragraphHeight(medicationDetails(row), width, BODY) + 3 * MM +
            (if (showsStrip(row)) stripHeight(row) else 0.0)

    private fun showsStrip(row: DoctorMedicationRow) = document.family == ExportFamily.MEDICATION_RECORD && row.week.isNotEmpty()

    private fun stripLabels(row: DoctorMedicationRow) = row.week.map { "${s.weekdays[it.date.dayOfWeek.isoDayNumber - 1]}\n${DoctorReportFormat.shortDate(it.date, language)}" }
    private fun stripShares(row: DoctorMedicationRow) = widths(*DoubleArray(row.week.size) { 1.0 })
    private fun stripCells(row: DoctorMedicationRow) = listOf(row.week.map { day -> day.times.ifEmpty { listOf("–") }.map { it to false } })

    private fun stripHeight(row: DoctorMedicationRow) = 1 * MM + pen.paragraphHeight(s.scheduleWeek, width, LABEL, bold = true) +
        pen.paragraphHeight(s.scheduleNote, width, NOTE) + 1 * MM + gridHeaderHeight(stripLabels(row), stripShares(row)) +
        gridRowHeight(stripCells(row).first(), stripShares(row)) + 2 * MM

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
            if (ensure(medicationHeight(row))) carriedTitle(t.recordedMedication, s.continued)
            y += pen.paragraph(row.displayName, left, y, width, BODY, bold = true)
            y += pen.paragraph(medicationDetails(row), left, y, width, BODY) + 1 * MM
            // The strip's table ends with its own rule.
            if (showsStrip(row)) scheduleStrip(row)
            else pen.line(left, y, right, y, PdfStyle.BORDER, PdfStyle.RULE)
            y += 2 * MM
        }
        ensure(pen.paragraphHeight(t.medicationNote, width, NOTE))
        y += pen.paragraph(t.medicationNote, left, y, width, NOTE, MUTED)
    }

    /** pdf-06: the planned times of the next seven dated days, labelled as schedule so it never reads as dose history. */
    private fun scheduleStrip(row: DoctorMedicationRow) {
        // The medication's own ensure() already kept name, details and strip together.
        y += 1 * MM
        y += pen.paragraph(s.scheduleWeek, left, y, width, LABEL, PINE, bold = true)
        y += pen.paragraph(s.scheduleNote, left, y, width, NOTE, MUTED) + 1 * MM
        grid(row.displayName, stripLabels(row), stripShares(row), stripCells(row), s.scheduleContinued)
    }

    /** pdf-09: the reports behind the exported results on their own page; originals are listed, never embedded. */
    private fun sourceAppendix() {
        newPage()
        val rows = document.sourceRows
        val included = rows.filter { it.results > 0 }
        val excluded = rows.filter { it.results == 0 }
        val labels = listOf(t.collected, s.report, s.originalFile)
        val shares = widths(32.0, 86.0, 58.0)
        sectionTitle(s.sourcesIncluded, PdfStyle.MINT, extra = 2 * pen.lineHeight(NOTE))
        y += pen.paragraph(s.sourcesNote, left, y, width, NOTE, MUTED) + 2 * MM
        if (included.isEmpty()) y += pen.paragraph(s.noSources, left, y, width, BODY) + 2 * MM
        else grid(s.sourcesIncluded, labels, shares, included.map(::sourceCells))
        if (excluded.isEmpty()) return
        y += 4 * MM
        sectionTitle(s.notIncluded, PdfStyle.CORAL, extra = pen.lineHeight(NOTE) + gridHeaderHeight(labels, shares) + minRow)
        y += pen.paragraph(s.notIncludedNote, left, y, width, NOTE, MUTED) + 2 * MM
        grid(s.notIncluded, labels, shares, excluded.map(::sourceCells))
    }

    private fun sourceCells(row: DoctorSourceRow): List<List<Pair<String, Boolean>>> {
        val name = row.documentName ?: when {
            row.manualEntry -> t.enteredManually
            row.results == 0 -> s.untitledDocument
            else -> t.notRecorded
        }
        val details = listOfNotNull(
            row.laboratoryName,
            row.orderedAt?.let { "${t.orderedOn} ${DoctorReportFormat.date(it, language)}" },
            row.pages?.let { s.pages.replace("{count}", it.toString()) },
            row.results.takeIf { it > 0 }?.let { s.confirmedInSource.replace("{count}", it.toString()) },
            row.unreviewed.takeIf { it > 0 }?.let { s.unreviewedInSource.replace("{count}", it.toString()) },
        )
        val original = when {
            row.originalStored -> s.originalStored
            row.manualEntry -> s.noFileManual
            else -> s.originalMissing
        }
        return listOf(
            listOf(row.collectedAt?.let { DoctorReportFormat.date(it, language) to false } ?: ("${s.imported} ${row.importedAt?.let { DoctorReportFormat.date(it, language) } ?: t.notRecorded}" to false)),
            listOf(name to false) + details.map { it to true },
            listOf(original to false),
        )
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

    private companion object {
        const val MAX_DEVICE_DAYS = 92
    }
}

