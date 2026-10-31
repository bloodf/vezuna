package com.bloodf.vitahearth.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

private fun vezunaIcon(vararg paths: String): ImageVector = ImageVector.Builder(
    name = "Vezuna",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    paths.forEach { data ->
        addPath(PathParser().parsePathString(data).toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }
}.build()

object VezunaIcons {
    val Activity = vezunaIcon("M3 12h4l3-7 4 14 3-7h4")
    val Calendar = vezunaIcon("M6 5H18A3 3 0 0 1 21 8V18A3 3 0 0 1 18 21H6A3 3 0 0 1 3 18V8A3 3 0 0 1 6 5ZM7 3v4M17 3v4M3 10h18M8 15h2M14 15h2")
    val Check = vezunaIcon("m5 12 4 4L19 6")
    val Chevron = vezunaIcon("m9 5 7 7-7 7")
    val Close = vezunaIcon("m6 6 12 12M18 6 6 18")
    val Connect = vezunaIcon("m9 15 6-6M8 17l-1 1a4 4 0 0 1-6-6l4-4a4 4 0 0 1 6 0M16 7l1-1a4 4 0 0 1 6 6l-4 4a4 4 0 0 1-6 0")
    val Lab = vezunaIcon("M9 3h6M10 3v7l-5 8a2 2 0 0 0 2 3h10a2 2 0 0 0 2-3l-5-8V3M8 15h8")
    val Medication = vezunaIcon("M8 5a5 5 0 0 1 7 7l-3 3a5 5 0 0 1-7-7ZM7 7l7 7")
    val Plus = vezunaIcon("M12 4v16M4 12h16")
    val Privacy = vezunaIcon("m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6ZM8 12l3 3 5-6")
    val Records = vezunaIcon("M8 3H16A3 3 0 0 1 19 6V18A3 3 0 0 1 16 21H8A3 3 0 0 1 5 18V6A3 3 0 0 1 8 3ZM9 8h6M9 12h6M9 16h4")
    val Settings = vezunaIcon("M3 7h3M12 7h9M3 17h9M18 17h3M9 7a3 3 0 1 0 0 6 3 3 0 0 0 0-6ZM15 17a3 3 0 1 0 0 6 3 3 0 0 0 0-6Z")
}
