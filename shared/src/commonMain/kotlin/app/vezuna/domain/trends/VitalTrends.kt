package app.vezuna.domain.trends

import app.vezuna.domain.ProfileVitals
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

enum class VitalSeriesKind { STEPS, HEART_RATE_DAILY_AVERAGE, WEIGHT }

/** Device measurement trends. Steps and heart rate are daily summaries; weight readings are dated individually. */
object VitalTrends {
    private val device = PointSource(PointSourceKind.DEVICE)

    fun series(
        kind: VitalSeriesKind,
        vitals: ProfileVitals,
        range: TrendRange,
        today: LocalDate,
        zone: TimeZone,
    ): HistorySeries {
        val (name, unit, raw) = when (kind) {
            VitalSeriesKind.STEPS -> Triple("Steps", "steps", vitals.steps.map {
                RawReading("steps-${it.date}", it.date, it.count.toDouble(), it.count.toDouble(), "steps", 0, null, device, Granularity.DAILY_SUMMARY, "")
            })
            VitalSeriesKind.HEART_RATE_DAILY_AVERAGE -> Triple("Daily average heart rate", "bpm", vitals.heartRateDaily.map {
                RawReading("hr-${it.date}", it.date, it.avg, it.avg, "bpm", null, null, device, Granularity.DAILY_SUMMARY, "")
            })
            VitalSeriesKind.WEIGHT -> Triple("Weight", "kg", vitals.weights.map {
                RawReading("weight-${it.recordId}", it.time.toLocalDateTime(zone).date, it.kg, it.kg, "kg", null, null, device, Granularity.READING, it.time.toString())
            })
        }
        return TrendEngine.series(kind.name, name, unit, raw, range, today)
    }
}
