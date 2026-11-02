package app.vezuna.export

import app.vezuna.domain.RangeAssessment
import app.vezuna.localization.ContentLanguage
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.round

/** Formats report text. Every function takes the export language; the default keeps the English output. */
object DoctorReportFormat {
    /** Swaps the decimal separator of an already formatted number for the export language. */
    fun localizeDecimal(formatted: String, language: ContentLanguage): String =
        formatted.replace('.', ExportText.of(language).decimalSeparator)

    /**
     * Shortest plain decimal for a computed number. Rounds only away binary noise (9 decimals), never to a
     * fixed short precision, so 0.0004 stays 0.0004.
     */
    fun value(value: Double, language: ContentLanguage = ContentLanguage.EN): String {
        if (abs(value) >= 1e9) return round(value).toLong().toString()
        val text = fixed(value, 9, ContentLanguage.EN).trimEnd('0').trimEnd('.')
        return if (text == "0" || text == "-0") "0" else localizeDecimal(text, language)
    }

    /** [value] with exactly [decimals] fraction digits, as a report prints it (5.70). */
    fun fixed(value: Double, decimals: Int, language: ContentLanguage = ContentLanguage.EN): String {
        var factor = 1L
        repeat(decimals) { factor *= 10 }
        val scaled = round(abs(value) * factor).toLong()
        val sign = if (value < 0 && scaled != 0L) "-" else ""
        val whole = (scaled / factor).toString()
        if (decimals == 0) return sign + whole
        val fraction = (scaled % factor).toString().padStart(decimals, '0')
        return sign + whole + ExportText.of(language).decimalSeparator + fraction
    }

    /** The result exactly as recorded: source precision when known, otherwise every stored digit. */
    fun recorded(value: Double, decimals: Int?, language: ContentLanguage = ContentLanguage.EN): String =
        if (decimals == null) value(value, language) else fixed(value, decimals, language)

    fun originalValue(row: DoctorResultRow, language: ContentLanguage = ContentLanguage.EN): String =
        row.qualitativeValue ?: "${recorded(requireNotNull(row.originalValue), row.valueDecimals, language)} ${row.originalUnit}"

    /** Fraction digits worth printing for arithmetic on these values: the most precise operand wins. */
    fun decimalsOf(value: Double, recorded: Int?): Int =
        recorded ?: value(value).substringAfter('.', "").length

    /** "+0.2 g/dL" / "-14 mg/dL" / "0 g/dL": a signed arithmetic difference, never a judgement. ASCII minus: the bundled Noto subset has no U+2212. */
    fun signedChange(delta: Double, decimals: Int, unit: String, language: ContentLanguage = ContentLanguage.EN): String {
        val magnitude = fixed(abs(delta), decimals, language)
        val zero = magnitude.all { it == '0' || !it.isDigit() }
        val sign = when {
            zero -> ""
            delta > 0 -> "+"
            else -> "-"
        }
        return "$sign$magnitude $unit".trim()
    }

    fun normalizedValue(row: DoctorResultRow, language: ContentLanguage = ContentLanguage.EN): String? {
        if (row.qualitativeValue != null) return null
        val original = requireNotNull(row.originalValue)
        val normalized = requireNotNull(row.normalizedValue)
        val unitChanged = !row.originalUnit.equals(row.normalizedUnit, ignoreCase = true)
        val tolerance = 1e-9 * maxOf(1.0, abs(original), abs(normalized))
        if (!unitChanged && abs(original - normalized) <= tolerance) return null
        return "${value(normalized, language)} ${row.normalizedUnit} ${ExportText.of(language).normalized}"
    }

    fun assessment(assessment: RangeAssessment, language: ContentLanguage = ContentLanguage.EN): String {
        val text = ExportText.of(language)
        return when (assessment) {
            RangeAssessment.BELOW -> text.belowReference
            RangeAssessment.WITHIN -> text.withinReference
            RangeAssessment.ABOVE -> text.aboveReference
            RangeAssessment.UNKNOWN -> text.referenceNotAssessed
        }
    }

    fun date(instant: Instant, language: ContentLanguage = ContentLanguage.EN): String =
        date(instant.toLocalDateTime(TimeZone.UTC).date, language)

    fun date(date: LocalDate, language: ContentLanguage = ContentLanguage.EN): String {
        return "${date.dayOfMonth.toString().padStart(2, '0')} ${ExportText.of(language).months[date.monthNumber - 1]} ${date.year}"
    }

    fun shortDate(instant: Instant, language: ContentLanguage = ContentLanguage.EN): String {
        val date = instant.toLocalDateTime(TimeZone.UTC).date
        return "${date.dayOfMonth.toString().padStart(2, '0')} ${ExportText.of(language).months[date.monthNumber - 1]}"
    }

    fun dateTime(instant: Instant, language: ContentLanguage = ContentLanguage.EN): String {
        val local = instant.toLocalDateTime(TimeZone.UTC)
        val time = "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
        return "${date(instant, language)}, $time UTC"
    }

    /** "Laboratories: Lab Central (ordered 20 Apr 2026); ..." from the rows' source reports, or null when none is known. */
    fun sources(rows: List<DoctorResultRow>, language: ContentLanguage = ContentLanguage.EN): String? {
        val t = ExportText.of(language)
        val entries = rows.filter { it.laboratoryName != null || it.orderedAt != null }
            .map { it.laboratoryName to it.orderedAt }.distinct()
            .map { (name, ordered) ->
                val ordering = ordered?.let { "${t.orderedOn} ${date(it, language)}" }
                listOfNotNull(name, ordering?.let { if (name == null) it else "($it)" }).joinToString(" ")
            }
        return entries.takeIf { it.isNotEmpty() }?.joinToString("; ")?.let { "${t.sources}: $it" }
    }

    fun period(rows: List<DoctorResultRow>, language: ContentLanguage = ContentLanguage.EN): String {
        if (rows.isEmpty()) return ExportText.of(language).noConfirmedResults
        val first = rows.minOf { it.collectedAt }
        val last = rows.maxOf { it.collectedAt }
        return if (first == last) date(first, language) else "${date(first, language)} – ${date(last, language)}"
    }

    fun latestValue(series: DoctorTrendSeries, language: ContentLanguage = ContentLanguage.EN): String =
        "${value(series.points.last().value, language)} ${series.unit}"

    fun changeContext(series: DoctorTrendSeries, language: ContentLanguage = ContentLanguage.EN): String {
        val text = ExportText.of(language)
        if (series.points.size < 2) return text.latestConfirmedResult
        val latest = series.points.last().value
        val previous = series.points[series.points.lastIndex - 1].value
        val delta = latest - previous
        val tolerance = 1e-9 * maxOf(1.0, abs(latest), abs(previous))
        return when {
            delta > tolerance -> "+${value(delta, language)} ${series.unit} ${text.fromPrevious}"
            delta < -tolerance -> "${value(delta, language)} ${series.unit} ${text.fromPrevious}"
            else -> text.noChangeFromPrevious
        }
    }
}
