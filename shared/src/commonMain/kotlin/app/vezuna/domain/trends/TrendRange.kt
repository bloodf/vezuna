package app.vezuna.domain.trends

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/** Inclusive calendar-day window of a selected period. */
data class DateWindow(val start: LocalDate, val end: LocalDate)

/** Time range chosen on a trend screen: presets or a custom period. */
sealed interface TrendRange {
    /** Calendar-month subtraction from today (3M, 6M, 1Y, 3Y). */
    data class Months(val months: Int) : TrendRange {
        init {
            require(months > 0)
        }
    }

    /** First through latest available reading. */
    data object All : TrendRange

    data class Custom(val from: LocalDate, val to: LocalDate) : TrendRange {
        init {
            require(from <= to) { "Custom range must not end before it starts" }
        }
    }

    companion object {
        val THREE_MONTHS: TrendRange = Months(3)
        val SIX_MONTHS: TrendRange = Months(6)
        val ONE_YEAR: TrendRange = Months(12)
        val THREE_YEARS: TrendRange = Months(36)
    }
}

/**
 * Resolves the period shown. [today] is the caller's local calendar day; [available] is the first and last
 * reading day of the whole history (null when there is none). Returns null only for [TrendRange.All] without data.
 */
fun TrendRange.window(today: LocalDate, available: DateWindow?): DateWindow? = when (this) {
    is TrendRange.Months -> DateWindow(today.minus(months, DateTimeUnit.MONTH), today)
    TrendRange.All -> available
    is TrendRange.Custom -> DateWindow(from, to)
}
