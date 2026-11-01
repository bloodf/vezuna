package app.vezuna.export

import kotlin.test.Test
import kotlin.test.assertEquals

class DoctorChartFormattingTest {
    @Test
    fun chartLabelsAvoidFloatingPointNoise() {
        assertEquals("0.112", formatDoctorChartValue(0.11199999999999999))
        assertEquals("147.4", formatDoctorChartValue(147.40000000000003))
        assertEquals("133", formatDoctorChartValue(133.0))
    }
}
