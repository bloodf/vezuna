package app.vezuna

import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.Recurrence
import app.vezuna.notifications.NotificationScheduleLogic
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import java.time.Instant
import java.time.ZoneId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationScheduleLogicTest {
    private val utc: ZoneId = ZoneId.of("UTC")

    @Test
    fun dailyTimesUseTheNextFutureLocalOccurrence() {
        val schedule = schedule(Recurrence.DAILY, times = listOf("08:00", "20:00"))
        val next = NotificationScheduleLogic.nextOccurrences(schedule, Instant.parse("2026-10-29T08:30:00Z"), utc)
        assertEquals(listOf("2026-10-29T20:00:00Z", "2026-10-30T08:00:00Z"), next.map { it.toInstant().toString() })
    }

    @Test
    fun weeklyAndIntervalRecurrencesRespectTheirAnchors() {
        val weekly = schedule(Recurrence.WEEKLY, days = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))
        assertEquals(
            "2026-10-30T08:00:00Z",
            NotificationScheduleLogic.nextOccurrences(weekly, Instant.parse("2026-10-29T07:00:00Z"), utc).single().toInstant().toString(),
        )
        val interval = schedule(Recurrence.EVERY_N_DAYS, intervalDays = 3)
        assertEquals(
            "2026-10-04T08:00:00Z",
            NotificationScheduleLogic.nextOccurrences(interval, Instant.parse("2026-10-02T07:00:00Z"), utc).single().toInstant().toString(),
        )
    }

    @Test
    fun endedSchedulesProduceNoAlarmAndDstUsesValidLocalTime() {
        val ended = schedule(Recurrence.DAILY).copy(endDate = LocalDate(2026, 10, 1))
        assertTrue(NotificationScheduleLogic.nextOccurrences(ended, Instant.parse("2026-10-29T07:00:00Z"), utc).isEmpty())
        val dst = schedule(Recurrence.DAILY, start = LocalDate(2026, 3, 8)).copy(timesLocal = listOf("02:30"))
        val next = NotificationScheduleLogic.nextOccurrences(dst, Instant.parse("2026-03-08T06:00:00Z"), ZoneId.of("America/New_York")).single()
        assertEquals("2026-03-08T07:30:00Z", next.toInstant().toString())
    }

    private fun schedule(
        recurrence: Recurrence,
        intervalDays: Int = 1,
        days: Set<DayOfWeek> = emptySet(),
        start: LocalDate = LocalDate(2026, 10, 1),
        times: List<String> = listOf("08:00"),
    ) = DoseSchedule("p", "s", "m", recurrence, intervalDays, days, start, timesLocal = times)
}
