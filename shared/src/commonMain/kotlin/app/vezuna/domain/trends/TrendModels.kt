package app.vezuna.domain.trends

import app.vezuna.domain.ReferenceRange
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

enum class PointSourceKind { REPORT, MANUAL, DEVICE }

/** Where a reading came from: a lab report (with its lab and file name when known), a typed-in entry or a device. */
data class PointSource(
    val kind: PointSourceKind,
    val label: String? = null,
    val reportId: String? = null,
    val documentId: String? = null,
)

/** A daily summary is one figure for a whole day, never an instantaneous or hourly measurement. */
enum class Granularity { READING, DAILY_SUMMARY }

/**
 * One plotted reading. [value] is in the series' [HistorySeries.unit]; [originalValue]/[originalUnit] are what was
 * recorded and are what the screen shows. [xFraction] is the actual elapsed-time position on the series axis (0..1).
 */
data class SeriesPoint(
    val id: String,
    val date: LocalDate,
    /** Noon UTC of [date]: the calendar day, independent of the viewer's time zone. */
    val at: Instant,
    val value: Double,
    val originalValue: Double,
    val originalUnit: String,
    val valueDecimals: Int?,
    val referenceRange: ReferenceRange?,
    val source: PointSource,
    val granularity: Granularity,
    val xFraction: Double,
)

/** Epoch-second span of the plotted period. Positions are linear in elapsed time; nothing is interpolated or zero filled. */
data class TimeAxis(val startEpochSeconds: Long, val endEpochSeconds: Long) {
    fun fractionOf(epochSeconds: Long): Double =
        if (endEpochSeconds <= startEpochSeconds) 0.5
        else ((epochSeconds - startEpochSeconds).toDouble() / (endEpochSeconds - startEpochSeconds)).coerceIn(0.0, 1.0)
}

/**
 * Plain arithmetic difference. Deliberately carries no better/worse judgement.
 * Readings are ordered by date, then by id (stable, not real recency): when two readings share a day the
 * "previous" one can have the same date, see [sameDay]; the UI should say "same day" rather than "since <date>".
 */
data class ChangeSummary(
    val delta: Double,
    val unit: String,
    val fromDate: LocalDate,
    val fromValue: Double,
    val toDate: LocalDate,
    val toValue: Double,
) {
    val sameDay: Boolean get() = fromDate == toDate
}

/** Copy for these states ("No reading recorded", "Not enough readings to compare") is localized by the UI/PDF layer. */
enum class ComparisonStatus { NO_READING, NOT_ENOUGH_READINGS, COMPARABLE }

/** Latest reading in the selected period plus its change against the period's first and previous reading. */
data class TrendComparison(
    val status: ComparisonStatus,
    val latest: SeriesPoint?,
    val sinceFirst: ChangeSummary?,
    val sincePrevious: ChangeSummary?,
)

/**
 * One numeric series of a single test in a single compatible unit.
 * [points] are the readings inside [window]; [allPoints] is the whole stored history (so "Show all history" and
 * "available data differs from selected period" need no second query).
 */
data class HistorySeries(
    val key: String,
    val displayName: String,
    /** Unit of [SeriesPoint.value] and of every [ChangeSummary]. */
    val unit: String,
    val window: DateWindow?,
    val axis: TimeAxis?,
    val points: List<SeriesPoint>,
    val allPoints: List<SeriesPoint>,
    val comparison: TrendComparison,
) {
    val available: DateWindow? get() = allPoints.firstOrNull()?.let { DateWindow(it.date, allPoints.last().date) }
    val hasHistoryOutsideWindow: Boolean get() = allPoints.size > points.size
    /** The line may only join actual points, and only when they span at least two distinct dates. */
    val canDrawLine: Boolean get() = points.map { it.date }.distinct().size >= 2
}

/** A text (qualitative) result kept as text: never a number, zero or score. */
data class TextEvent(
    val id: String,
    val date: LocalDate,
    val at: Instant,
    val text: String,
    val source: PointSource,
)

/** Everything one lab test shows: separate series per incompatible unit, plus text results. */
data class TestTrend(
    val testCode: String,
    val displayName: String,
    val series: List<HistorySeries>,
    val textEvents: List<TextEvent>,
    val textEventsAll: List<TextEvent>,
)
