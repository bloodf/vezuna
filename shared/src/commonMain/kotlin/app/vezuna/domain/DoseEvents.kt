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
     * Rule: the latest user action (TAKEN or SKIPPED) wins, so a user can correct SKIPPED to TAKEN and back.
     * MISSED is a system inference: it never overrides a user action and is replaced by one.
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
            event.status == DoseEventStatus.MISSED -> existing
            else -> existing.map { if (it === current) event else it }
        }
    }

    /** Collapses legacy stored events (older ID schemes) to one terminal event per dose, oldest write first. */
    fun collapse(events: List<DoseEvent>): List<DoseEvent> =
        events.sortedBy { it.recordedAt?.epochSeconds ?: Long.MIN_VALUE }.fold(emptyList()) { acc, event ->
            if (!isTerminal(event.status) && acc.any { it.eventId == event.eventId }) acc else merge(acc, event)
        }
}
