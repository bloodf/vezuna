package app.vezuna

import app.vezuna.localization.formatUiDate
import kotlinx.datetime.Instant
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertTrue

class CollectionDateFormatTest {
    private val noonUtc = Instant.parse("2026-03-10T12:00:00Z")

    private fun withZone(id: String, block: () -> Unit) {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(id))
            block()
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun calendarDayIsStableInUtcMinusThreeAndUtcPlusThirteen() {
        listOf("America/Sao_Paulo", "Pacific/Apia", "Pacific/Auckland", "UTC").forEach { zone ->
            withZone(zone) {
                val text = formatUiDate(noonUtc, "en-US")
                assertTrue(text.contains("Mar 10, 2026"), "$zone -> $text")
            }
        }
    }
}
