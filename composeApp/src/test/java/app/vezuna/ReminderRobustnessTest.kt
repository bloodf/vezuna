package app.vezuna

import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.Recurrence
import app.vezuna.notifications.DoseAction
import app.vezuna.notifications.NotificationScheduleLogic
import kotlinx.datetime.LocalDate
import java.time.Instant
import java.time.ZoneId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReminderRobustnessTest {
    private val schedule = DoseSchedule("p", "s", "m", Recurrence.DAILY, startDate = LocalDate(2026, 10, 1), timesLocal = listOf("08:00"))
    private val now = Instant.parse("2026-10-29T07:00:00Z")

    private fun armedKeys(zone: String): Set<String> =
        NotificationScheduleLogic.nextOccurrences(schedule, now, ZoneId.of(zone)).take(1)
            .map { NotificationScheduleLogic.armedKey("m", NotificationScheduleLogic.alarmId(schedule.scheduleId, it.toInstant())) }.toSet()

    @Test
    fun timezoneChangeLeavesExactlyOneArmedAlarmAndStalesTheOldOne() {
        val before = armedKeys("UTC")
        val after = armedKeys("Asia/Tokyo")
        assertEquals(1, before.size)
        assertEquals(1, after.size)
        // The local 08:00 moved to a different instant, so the old alarm must be cancelled, not left to fire.
        assertEquals(before, NotificationScheduleLogic.staleArmedKeys(before, after))
        assertEquals(emptySet(), NotificationScheduleLogic.staleArmedKeys(after, after))
    }

    @Test
    fun armedKeyRoundTripsEvenWhenIdsContainTheSeparator() {
        val key = NotificationScheduleLogic.armedKey("med", "sched:1")
        assertEquals("med" to "sched:1", NotificationScheduleLogic.splitArmedKey(key))
        assertNull(NotificationScheduleLogic.splitArmedKey("garbage"))
    }

    @Test
    fun unknownOrMissingActionsAreRejectedWithoutThrowing() {
        assertEquals(DoseAction.TAKEN, DoseAction.parse("TAKEN"))
        assertEquals(DoseAction.SNOOZE, DoseAction.parse("SNOOZE"))
        assertNull(DoseAction.parse("android.intent.action.BOOT_COMPLETED"))
        assertNull(DoseAction.parse(null))
        assertNull(DoseAction.parse(""))
    }
}
