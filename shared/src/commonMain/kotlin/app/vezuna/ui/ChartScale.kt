package app.vezuna.ui

import app.vezuna.domain.ReferenceBand
import kotlin.math.abs
import kotlin.math.max

/** Padded y-domain of a chart: always includes the reference band, never touches the data. */
internal class ChartScale private constructor(val min: Double, val max: Double) {
    /** Bottom, middle, top. */
    val ticks: List<Double> get() = listOf(min, (min + max) / 2.0, max)

    fun fraction(value: Double): Float = ((value - min) / (max - min)).toFloat()

    companion object {
        private const val PADDING = 0.1

        fun of(values: List<Double>, band: ReferenceBand? = null): ChartScale {
            require(values.isNotEmpty())
            val all = values + listOfNotNull(band?.lower, band?.upper)
            val low = all.min()
            val high = all.max()
            val pad = if (high > low) (high - low) * PADDING else max(abs(low) * PADDING, 1.0)
            val floor = if (low >= 0.0) max(0.0, low - pad) else low - pad
            return ChartScale(floor, high + pad)
        }
    }
}
