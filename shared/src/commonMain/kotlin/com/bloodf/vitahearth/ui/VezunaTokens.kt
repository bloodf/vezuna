package com.bloodf.vitahearth.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object VezunaTokens {
    val Navy = Color(0xFF123B46)
    val Teal = Color(0xFF087F8C)
    val Aqua = Color(0xFF71E2CC)
    val Offwhite = Color(0xFFF5FAF9)
    val Ink = Color(0xFF152E35)
    val Deep = Color(0xFF0C2027)
    val Mist = Color(0xFFE7F5F2)

    object Light {
        val background = Offwhite
        val surface = Color(0xFFFFFFFF)
        val surfaceSubtle = Color(0xFFE7F3F1)
        val text = Ink
        val textMuted = Color(0xFF526C74)
        val border = Color(0xFF8CA7AD)
        val primary = Teal
        val onPrimary = Color(0xFFFFFFFF)
        val accent = Aqua
        val onAccent = Navy
        val focus = Color(0xFF086A78)
        val success = Color(0xFF176B50)
        val warning = Color(0xFF805500)
        val danger = Color(0xFFB3263B)
        val info = Color(0xFF245DA8)
    }

    object Dark {
        val background = Deep
        val surface = Color(0xFF14313A)
        val surfaceSubtle = Color(0xFF1A3E48)
        val text = Mist
        val textMuted = Color(0xFFADC7CE)
        val border = Color(0xFF668992)
        val primary = Aqua
        val onPrimary = Deep
        val accent = Color(0xFF35B7B8)
        val onAccent = Deep
        val focus = Aqua
        val success = Color(0xFF87DEB4)
        val warning = Color(0xFFF5CB73)
        val danger = Color(0xFFFF9AAA)
        val info = Color(0xFF9DC4FF)
    }

    val SpaceXs = 4.dp
    val SpaceSm = 8.dp
    val SpaceMd = 16.dp
    val SpaceLg = 24.dp
    val SpaceXl = 32.dp
    val SpaceXxl = 48.dp
    val ControlRadius = 12.dp
    val CardRadius = 20.dp
    val PillRadius = 999.dp
    val TouchTarget = 48.dp
    const val MotionFastMs = 120
    const val MotionNormalMs = 200
    const val MotionReducedMs = 0
}
