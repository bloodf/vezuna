package app.vezuna.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vezuna.domain.ReferenceBand
import app.vezuna.domain.TrendSeries
import app.vezuna.domain.referenceBand
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatLabValue
import app.vezuna.localization.vitaPlural
import app.vezuna.localization.vitaText

@Composable
internal fun TrendChart(series: TrendSeries, modifier: Modifier) {
    val values = series.points.map { it.y }
    val convertedCount = series.points.count { it.converted }
    val summary = vitaPlural(
        L10nKey.TREND_CHART_ACCESSIBILITY, series.points.size, series.unit,
        formatLabValue(values.min()), formatLabValue(values.max()), formatLabValue(values.last()),
    )
    val description = if (convertedCount == 0) summary else "$summary ${vitaText(L10nKey.CHART_CONVERTED_POINTS, convertedCount)}"
    LineChart(
        series.points.map { it.xEpochSeconds to it.y }, "${series.testCode}|${series.unit}", description, modifier,
        unit = series.unit, band = series.referenceBand(), converted = series.points.map { it.converted },
    )
}

/**
 * Points are (epoch seconds, value) sorted by time. [identity] restarts the draw-in animation when it changes.
 * The plot is inset, shows three labelled y ticks (with [unit]), shades [band] and labels the latest value.
 */
@Composable
internal fun LineChart(
    points: List<Pair<Long, Double>>,
    identity: String,
    description: String,
    modifier: Modifier,
    unit: String = "",
    band: ReferenceBand? = null,
    /** Per point: true when drawn from a converted value; those get a hollow marker. */
    converted: List<Boolean> = emptyList(),
) {
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val pointCenter = MaterialTheme.colorScheme.surface
    val labelStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val latestStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
    val measurer = rememberTextMeasurer()
    val motionEnabled = LocalVitaAnimationsEnabled.current
    val progress = remember(identity) { Animatable(if (motionEnabled) 0.2f else 1f) }
    LaunchedEffect(identity, points.size) {
        if (motionEnabled) progress.animateTo(1f, tween(VezunaTokens.MotionNormalMs)) else progress.snapTo(1f)
    }
    Canvas(modifier.semantics { contentDescription = description }) {
        if (points.isEmpty()) return@Canvas
        val scale = ChartScale.of(points.map { it.second }, band)
        val tickLabels = scale.ticks.map { measurer.measure("${formatLabValue(it)} $unit".trim(), labelStyle) }
        val inset = INSET_DP.dp.toPx()
        val plotLeft = tickLabels.maxOf { it.size.width } + inset
        val plotRight = size.width - inset
        val plotTop = inset + LATEST_LABEL_DP.dp.toPx()
        val plotBottom = size.height - inset
        fun yOf(value: Double) = plotBottom - scale.fraction(value) * (plotBottom - plotTop)

        band?.let { drawBand(it, scale, plotLeft, plotRight, plotTop, plotBottom, color) }
        scale.ticks.forEachIndexed { index, tick ->
            val y = yOf(tick)
            drawLine(grid, Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = 1.dp.toPx())
            val label = tickLabels[index]
            val labelY = (y - label.size.height / 2f).coerceIn(0f, size.height - label.size.height)
            drawText(label, topLeft = Offset(plotLeft - inset / 2 - label.size.width, labelY))
        }
        val first = points.first().first
        val last = points.last().first
        val offsets = points.map { (x, y) ->
            val xFraction = if (first == last) 0.5f else (x - first).toFloat() / (last - first).toFloat()
            Offset(plotLeft + xFraction * (plotRight - plotLeft), yOf(y))
        }
        offsets.zipWithNext().forEachIndexed { index, pair ->
            if ((index + 1).toFloat() / offsets.lastIndex <= progress.value) {
                drawLine(color, pair.first, pair.second, strokeWidth = LINE_DP.dp.toPx())
            }
        }
        offsets.forEachIndexed { index, point ->
            if (offsets.size == 1 || index.toFloat() / offsets.lastIndex <= progress.value) {
                drawCircle(color, radius = POINT_DP.dp.toPx(), center = point)
                val hollow = converted.getOrNull(index) == true
                drawCircle(pointCenter, radius = (if (hollow) POINT_HOLLOW_DP else POINT_CENTER_DP).dp.toPx(), center = point)
            }
        }
        if (progress.value >= 1f) drawLatestLabel(measurer, "${formatLabValue(points.last().second)} $unit".trim(), latestStyle, offsets.last(), plotLeft)
    }
}

private fun DrawScope.drawBand(band: ReferenceBand, scale: ChartScale, left: Float, right: Float, top: Float, bottom: Float, color: androidx.compose.ui.graphics.Color) {
    fun yOf(value: Double) = bottom - scale.fraction(value) * (bottom - top)
    val bandTop = band.upper?.let { yOf(it) } ?: top
    val bandBottom = band.lower?.let { yOf(it) } ?: bottom
    drawRect(color.copy(alpha = 0.14f), Offset(left, bandTop), Size(right - left, (bandBottom - bandTop).coerceAtLeast(1f)))
}

private fun DrawScope.drawLatestLabel(measurer: TextMeasurer, text: String, style: TextStyle, at: Offset, minLeft: Float) {
    val label = measurer.measure(text, style)
    val x = (at.x - label.size.width / 2f).coerceIn(minLeft, (size.width - label.size.width).coerceAtLeast(minLeft))
    val y = (at.y - label.size.height - POINT_DP.dp.toPx() - 2.dp.toPx()).coerceAtLeast(0f)
    drawText(label, topLeft = Offset(x, y))
}

private const val INSET_DP = 10
private const val LATEST_LABEL_DP = 14
private const val LINE_DP = 2
private const val POINT_DP = 4
private const val POINT_CENTER_DP = 1.5f
private const val POINT_HOLLOW_DP = 2.8f
