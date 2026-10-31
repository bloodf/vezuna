package com.bloodf.vitahearth

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bloodf.vitahearth.ui.VezunaTokens
import kotlin.test.Test
import kotlin.test.assertEquals

class VezunaBrandTest {
    @Test
    fun suppliedPrimitivesAndActionRolesMatchTheKit() {
        assertEquals(Color(0xFF123B46), VezunaTokens.Navy)
        assertEquals(Color(0xFF087F8C), VezunaTokens.Light.primary)
        assertEquals(Color(0xFFFFFFFF), VezunaTokens.Light.onPrimary)
        assertEquals(Color(0xFF71E2CC), VezunaTokens.Dark.primary)
        assertEquals(Color(0xFF0C2027), VezunaTokens.Dark.onPrimary)
        assertEquals(Color(0xFF152E35), VezunaTokens.Light.text)
        assertEquals(Color(0xFFE7F5F2), VezunaTokens.Dark.text)
    }

    @Test
    fun spacingShapeMotionAndTouchTokensMatchTheKit() {
        assertEquals(4.dp, VezunaTokens.SpaceXs)
        assertEquals(8.dp, VezunaTokens.SpaceSm)
        assertEquals(16.dp, VezunaTokens.SpaceMd)
        assertEquals(24.dp, VezunaTokens.SpaceLg)
        assertEquals(32.dp, VezunaTokens.SpaceXl)
        assertEquals(48.dp, VezunaTokens.SpaceXxl)
        assertEquals(12.dp, VezunaTokens.ControlRadius)
        assertEquals(20.dp, VezunaTokens.CardRadius)
        assertEquals(999.dp, VezunaTokens.PillRadius)
        assertEquals(48.dp, VezunaTokens.TouchTarget)
        assertEquals(120, VezunaTokens.MotionFastMs)
        assertEquals(200, VezunaTokens.MotionNormalMs)
        assertEquals(0, VezunaTokens.MotionReducedMs)
    }
}
