package app.vezuna.domain

import kotlinx.datetime.Instant

/**
 * Single source of truth for dose event identity and the terminal-status rule.
 *
 * Every path (in-app, Android notification action, iOS notification action, iOS missed
 * reconciliation) must derive the ID with [idFor] so the same dose can never be stored twice.
 */
object DoseEvents {
    fun idFor(scheduleId: String, scheduledFor: Instant): String = "dose-$scheduleId-${scheduledFor.epochSeconds}"

    /** Snooze events are non-terminal markers; they must not share the ID of the dose they create. */
    fun snoozeIdFor(scheduleId: String, snoozedFor: Instant): String = "${idFor(scheduleId, snoozedFor)}-snooze"

    fun isTerminal(status: DoseEventStatus): Boolean = status != DoseEventStatus.SCHEDULED

    /**
     * At most one terminal status per dose (scheduleId + scheduledFor).
     * Rule: the first user action (TAKEN or SKIPPED) wins and later conflicting writes are ignored;
     * MISSED is a system inference, so it never overrides a user action and is replaced by one.
     */
    fun merge(existing: List<DoseEvent>, event: DoseEvent): List<DoseEvent> {
        if (!isTerminal(event.status)) {
            require(existing.none { it.eventId == event.eventId })
            return existing + event
        }
        val current = existing.firstOrNull {
            isTerminal(it.status) && it.scheduleId == event.scheduleId && it.scheduledFor.epochSeconds == event.scheduledFor.epochSeconds
        }
        return when {
            current == null -> {
                require(existing.none { it.eventId == event.eventId })
                existing + event
            }
            current.status == DoseEventStatus.MISSED && event.status != DoseEventStatus.MISSED ->
                existing.map { if (it === current) event else it }
            else -> existing
        }
    }
}
