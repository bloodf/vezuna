package app.vezuna.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vezuna.domain.ReferenceBand
import app.vezuna.localization.formatLabValue
import kotlin.math.abs

/** One plotted mark. [x] is the elapsed-time position on the period axis (0..1); [hollow] marks a converted value. */
internal data class ChartPoint(val x: Float, val value: Double, val hollow: Boolean = false)

/**
 * Dated numeric chart. Points sit at their actual elapsed-time position; a segment is drawn only where [joins]
 * allows it (never through a single point or across a gap). [band] with both bounds is shaded and outlined; a
 * single bound is a dashed line. Tapping selects the nearest point; the screen must offer the same selection
 * outside the chart (rows, previous/next), because the canvas exposes only its [description].
 */
@Composable
internal fun LineChart(
    points: List<ChartPoint>,
    identity: String,
    description: String,
    modifier: Modifier,
    unit: String = "",
    band: ReferenceBand? = null,
    joins: List<Boolean> = List((points.size - 1).coerceAtLeast(0)) { true },
    startLabel: String? = null,
    endLabel: String? = null,
    selected: Int? = null,
    onSelect: ((Int) -> Unit)? = null,
    formatValue: (Double) -> String = { formatLabValue(it) },
    /** Series ink; pass Category.series(darkTheme) so marks reach 3:1 on the surface. */
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val pointCenter = MaterialTheme.colorScheme.surface
    val labelStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val valueStyle = TextStyle(fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
    val measurer = rememberTextMeasurer()
    val motionEnabled = LocalVitaAnimationsEnabled.current
    val progress = remember(identity) { Animatable(if (motionEnabled) 0.2f else 1f) }
    LaunchedEffect(identity, points.size) {
        if (motionEnabled) progress.animateTo(1f, tween(VezunaTokens.MotionNormalMs)) else progress.snapTo(1f)
    }
    // Plot edges from the last draw, read by the tap handler (not state: drawing must not recompose).
    val plotEdges = remember { FloatArray(2) }
    val currentPoints by rememberUpdatedState(points)
    val select by rememberUpdatedState(onSelect)
    val tap = if (onSelect == null) Modifier else Modifier.pointerInput(identity) {
        detectTapGestures { offset ->
            val pts = currentPoints
            if (pts.isEmpty()) return@detectTapGestures
            val width = plotEdges[1] - plotEdges[0]
            val nearest = pts.indices.minBy { abs(plotEdges[0] + pts[it].x * width - offset.x) }
            select?.invoke(nearest)
        }
    }
    Canvas(modifier.then(tap).semantics { contentDescription = description }) {
        if (points.isEmpty()) return@Canvas
        val scale = ChartScale.of(points.map { it.value }, band)
        val tickLabels = scale.ticks.map { measurer.measure("${formatValue(it)} $unit".trim(), labelStyle) }
        val start = startLabel?.let { measurer.measure(it, labelStyle) }
        val end = endLabel?.let { measurer.measure(it, labelStyle) }
        val inset = INSET_DP.dp.toPx()
        val plotLeft = tickLabels.maxOf { it.size.width } + inset
        val plotRight = size.width - inset
        val plotTop = inset + VALUE_LABEL_DP.dp.toPx()
        val axisHeight = maxOf(start?.size?.height ?: 0, end?.size?.height ?: 0).toFloat()
        val plotBottom = size.height - inset - axisHeight
        plotEdges[0] = plotLeft
        plotEdges[1] = plotRight
        fun yOf(value: Double) = plotBottom - scale.fraction(value) * (plotBottom - plotTop)

        band?.let { drawBand(it, ::yOf, plotLeft, plotRight, plotTop, plotBottom, color) }
        scale.ticks.forEachIndexed { index, tick ->
            val y = yOf(tick)
            drawLine(grid, Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = 1.dp.toPx())
            val label = tickLabels[index]
            val labelY = (y - label.size.height / 2f).coerceIn(0f, size.height - label.size.height)
            drawText(label, topLeft = Offset(plotLeft - inset / 2 - label.size.width, labelY))
        }
        start?.let { drawText(it, topLeft = Offset(plotLeft, size.height - it.size.height)) }
        end?.let { drawText(it, topLeft = Offset((plotRight - it.size.width).coerceAtLeast(plotLeft), size.height - it.size.height)) }
        val offsets = points.map { Offset(plotLeft + it.x * (plotRight - plotLeft), yOf(it.value)) }
        val shown = { index: Int -> offsets.size == 1 || index.toFloat() / offsets.lastIndex <= progress.value }
        offsets.zipWithNext().forEachIndexed { index, (a, b) ->
            if (joins.getOrElse(index) { false } && shown(index + 1)) drawLine(color, a, b, strokeWidth = LINE_DP.dp.toPx())
        }
        selected?.let { offsets.getOrNull(it) }?.let { at ->
            drawLine(color.copy(alpha = 0.5f), Offset(at.x, plotTop), Offset(at.x, plotBottom), strokeWidth = 1.dp.toPx(), pathEffect = DASH)
            drawCircle(color, radius = SELECTED_RING_DP.dp.toPx(), center = at, style = Stroke(2.dp.toPx()))
        }
        offsets.forEachIndexed { index, point ->
            if (shown(index)) {
                drawCircle(color, radius = POINT_DP.dp.toPx(), center = point)
                val hollow = points[index].hollow
                drawCircle(pointCenter, radius = (if (hollow) POINT_HOLLOW_DP else POINT_CENTER_DP).dp.toPx(), center = point)
            }
        }
        if (progress.value >= 1f) {
            val labelled = selected?.takeIf { it in points.indices } ?: points.lastIndex
            drawValueLabel(measurer, "${formatValue(points[labelled].value)} $unit".trim(), valueStyle, offsets[labelled], plotLeft)
        }
    }
}

private fun DrawScope.drawBand(band: ReferenceBand, yOf: (Double) -> Float, left: Float, right: Float, top: Float, bottom: Float, color: Color) {
    val outline = 1.5.dp.toPx()
    if (band.lower != null && band.upper != null) {
        val bandTop = yOf(band.upper)
        val bandBottom = yOf(band.lower)
        drawRect(color.copy(alpha = 0.12f), Offset(left, bandTop), Size(right - left, (bandBottom - bandTop).coerceAtLeast(1f)))
        drawLine(color.copy(alpha = 0.6f), Offset(left, bandTop), Offset(right, bandTop), strokeWidth = outline)
        drawLine(color.copy(alpha = 0.6f), Offset(left, bandBottom), Offset(right, bandBottom), strokeWidth = outline)
    } else {
        val y = yOf(band.upper ?: band.lower ?: return).coerceIn(top, bottom)
        drawLine(color.copy(alpha = 0.7f), Offset(left, y), Offset(right, y), strokeWidth = outline, pathEffect = DASH)
    }
}

private fun DrawScope.drawValueLabel(measurer: TextMeasurer, text: String, style: TextStyle, at: Offset, minLeft: Float) {
    val label = measurer.measure(text, style)
    val x = (at.x - label.size.width / 2f).coerceIn(minLeft, (size.width - label.size.width).coerceAtLeast(minLeft))
    val y = (at.y - label.size.height - POINT_DP.dp.toPx() - 4.dp.toPx()).coerceAtLeast(0f)
    drawText(label, topLeft = Offset(x, y))
}

private val DASH = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
private const val INSET_DP = 10
private const val VALUE_LABEL_DP = 22
private const val LINE_DP = 2.5f
private const val POINT_DP = 5
private const val POINT_CENTER_DP = 2f
private const val POINT_HOLLOW_DP = 3.2f
private const val SELECTED_RING_DP = 10
