package app.vezuna.export

import app.vezuna.domain.RangeAssessment
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.round

object DoctorReportFormat {
    private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun value(value: Double): String {
        val rounded = round(value * 1_000.0) / 1_000.0
        if (rounded == 0.0) return "0"
        if (rounded == rounded.toLong().toDouble()) return rounded.toLong().toString()
        return rounded.toString().trimEnd('0').trimEnd('.')
    }

    fun originalValue(row: DoctorResultRow): String =
        row.qualitativeValue ?: "${value(row.originalValue)} ${row.originalUnit}"

    fun normalizedValue(row: DoctorResultRow): String? {
        if (row.qualitativeValue != null) return null
        val unitChanged = !row.originalUnit.equals(row.normalizedUnit, ignoreCase = true)
        val tolerance = 1e-9 * maxOf(1.0, abs(row.originalValue), abs(row.normalizedValue))
        if (!unitChanged && abs(row.originalValue - row.normalizedValue) <= tolerance) return null
        return "${value(row.normalizedValue)} ${row.normalizedUnit} normalized"
    }

    fun assessment(assessment: RangeAssessment): String = when (assessment) {
        RangeAssessment.BELOW -> "Below reference"
        RangeAssessment.WITHIN -> "Within reference"
        RangeAssessment.ABOVE -> "Above reference"
        RangeAssessment.UNKNOWN -> "Reference not assessed"
    }

    fun date(instant: Instant): String {
        val date = instant.toLocalDateTime(TimeZone.UTC).date
        return "${date.dayOfMonth.toString().padStart(2, '0')} ${months[date.monthNumber - 1]} ${date.year}"
    }

    fun shortDate(instant: Instant): String {
        val date = instant.toLocalDateTime(TimeZone.UTC).date
        return "${date.dayOfMonth.toString().padStart(2, '0')} ${months[date.monthNumber - 1]}"
    }

    fun dateTime(instant: Instant): String {
        val local = instant.toLocalDateTime(TimeZone.UTC)
        val time = "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
        return "${date(instant)}, $time UTC"
    }

    fun period(rows: List<DoctorResultRow>): String {
        if (rows.isEmpty()) return "No confirmed results"
        val first = rows.minOf { it.collectedAt }
        val last = rows.maxOf { it.collectedAt }
        return if (first == last) date(first) else "${date(first)} – ${date(last)}"
    }

    fun latestValue(series: DoctorTrendSeries): String = "${value(series.points.last().value)} ${series.unit}"

    fun changeContext(series: DoctorTrendSeries): String {
        if (series.points.size < 2) return "Latest confirmed result"
        val latest = series.points.last().value
        val previous = series.points[series.points.lastIndex - 1].value
        val delta = latest - previous
        val tolerance = 1e-9 * maxOf(1.0, abs(latest), abs(previous))
        return when {
            delta > tolerance -> "+${value(delta)} ${series.unit} from previous"
            delta < -tolerance -> "${value(delta)} ${series.unit} from previous"
            else -> "No change from previous"
        }
    }
}
