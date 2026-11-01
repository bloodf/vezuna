package app.vezuna.export

import app.vezuna.domain.RangeAssessment
import app.vezuna.localization.ContentLanguage
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.round

/** Formats report text. Every function takes the export language; the default keeps the English output. */
object DoctorReportFormat {
    /** Swaps the decimal separator of an already formatted number for the export language. */
    fun localizeDecimal(formatted: String, language: ContentLanguage): String =
        formatted.replace('.', ExportText.of(language).decimalSeparator)

    fun value(value: Double, language: ContentLanguage = ContentLanguage.EN): String {
        val rounded = round(value * 1_000.0) / 1_000.0
        if (rounded == 0.0) return "0"
        if (rounded == rounded.toLong().toDouble()) return rounded.toLong().toString()
        return localizeDecimal(rounded.toString().trimEnd('0').trimEnd('.'), language)
    }

    fun originalValue(row: DoctorResultRow, language: ContentLanguage = ContentLanguage.EN): String =
        row.qualitativeValue ?: "${value(requireNotNull(row.originalValue), language)} ${row.originalUnit}"

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

    fun date(instant: Instant, language: ContentLanguage = ContentLanguage.EN): String {
        val date = instant.toLocalDateTime(TimeZone.UTC).date
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
