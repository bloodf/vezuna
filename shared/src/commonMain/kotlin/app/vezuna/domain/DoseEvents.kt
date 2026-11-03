package app.vezuna.domain

import kotlin.time.Instant

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
     * When a 10-minute snooze from [now] fires. Schedule times are whole minutes, so a time off the minute can only be
     * a snooze target; keeping every new snooze off the minute lets [merge] and [perDose] prove the relationship.
     */
    fun snoozeUntil(now: Instant): Instant {
        val at = now.epochSeconds + SNOOZE_SECONDS
        return Instant.fromEpochSeconds(if (at % 60 == 0L) at + 1 else at)
    }

    /**
     * The second to store a snooze of the dose shown at [replaces] at: [at] unless another dose of the schedule already
     * snoozed to that second (taps a second apart can land there), then the next free one, still off the minute.
     * The same snooze repeated keeps its second, so a double tap stays one snooze.
     */
    fun snoozeSlot(events: List<DoseEvent>, scheduleId: String, replaces: Instant, at: Instant): Instant {
        val taken = events.filter { it.scheduleId == scheduleId && it.status == DoseEventStatus.SCHEDULED && it.replacesScheduledFor?.epochSeconds != replaces.epochSeconds }
            .map { it.scheduledFor.epochSeconds }.toSet()
        return Instant.fromEpochSeconds(generateSequence(at.epochSeconds) { it + 1 }.first { it % 60 != 0L && it !in taken })
    }

    /**
     * Follows the snooze markers back: the time a dose at (schedule, time) was first due. No marker, same time.
     * Stops on reaching a whole minute: that time may be a real dose that an old marker also happens to name, and going
     * on would tie this dose to another one.
     */
    fun originals(events: List<DoseEvent>): (String, Instant) -> Instant = walk(events, stopOnWholeMinute = true)

    /**
     * [originals] without the stop, for the supply count only: what it has always counted for old markers, so a stored
     * history keeps its count. See MedicationSupplies for the known limit.
     */
    internal fun oldCountOriginals(events: List<DoseEvent>): (String, Instant) -> Instant = walk(events, stopOnWholeMinute = false)

    private fun walk(events: List<DoseEvent>, stopOnWholeMinute: Boolean): (String, Instant) -> Instant {
        val replaced = events.filter { it.status == DoseEventStatus.SCHEDULED && it.replacesScheduledFor != null }
            .associate { (it.scheduleId to it.scheduledFor.epochSeconds) to it.replacesScheduledFor!! }
        return { scheduleId, at ->
            var original = at
            val seen = mutableSetOf(at.epochSeconds)
            while (true) {
                original = replaced[scheduleId to original.epochSeconds]?.takeIf { seen.add(it.epochSeconds) } ?: break
                if (stopOnWholeMinute && original.epochSeconds % 60 == 0L) break
            }
            original
        }
    }

    /**
     * Where a terminal event at [at] belongs. On a whole minute it could be a real dose of the schedule that happens to
     * share a snooze target's second, and old events do not say which, so it stays put: separate doses never merge.
     */
    private fun provenOriginal(original: (String, Instant) -> Instant, scheduleId: String, at: Instant): Instant =
        if (at.epochSeconds % 60 == 0L) at else original(scheduleId, at)

    /** Is this snooze still waiting? Not once it was snoozed again or its dose, at either time, has been recorded. */
    fun isOpen(events: List<DoseEvent>, snooze: DoseEvent): Boolean {
        val times = setOf(snooze.scheduledFor.epochSeconds, originals(events)(snooze.scheduleId, snooze.scheduledFor).epochSeconds)
        return events.none {
            it.scheduleId == snooze.scheduleId && if (isTerminal(it.status)) it.scheduledFor.epochSeconds in times else it.replacesScheduledFor?.epochSeconds == snooze.scheduledFor.epochSeconds
        }
    }

    /**
     * Records [event], a new write. A snoozed dose stays tied to its original time, whichever path reports it and
     * however old its notification payload is. Stored events are never moved; [perDose] merges them for reading.
     * Repeating the status the person sees is a no-op, so the first recorded time stays, and so is MISSED over any record.
     * A different status always writes, even if an older duplicate of this dose stored that status, so a row stays
     * correctable. A repeated snooze marker is a no-op too.
     */
    fun merge(existing: List<DoseEvent>, event: DoseEvent): List<DoseEvent> {
        if (!isTerminal(event.status)) return if (existing.any { it.eventId == event.eventId }) existing else mergeStored(existing, event)
        val at = provenOriginal(originals(existing), event.scheduleId, event.scheduledFor)
        val rooted = if (at.epochSeconds == event.scheduledFor.epochSeconds) event else event.copy(scheduledFor = at, eventId = idFor(event.scheduleId, at))
        val shown = perDose(existing.filter { it.scheduleId == rooted.scheduleId }).firstOrNull {
            isTerminal(it.status) && it.scheduledFor.epochSeconds == rooted.scheduledFor.epochSeconds
        }
        val noOp = shown != null && (shown.status == rooted.status || rooted.status == DoseEventStatus.MISSED)
        return if (noOp) existing else mergeStored(existing, rooted)
    }

    /**
     * At most one terminal status per dose (scheduleId + scheduledFor).
     * Rule: the latest user action (TAKEN or SKIPPED) wins, so a user can correct SKIPPED to TAKEN and back.
     * MISSED is a system inference: it never overrides a user action and is replaced by one.
     */
    private fun mergeStored(existing: List<DoseEvent>, event: DoseEvent, keepFirstRepeat: Boolean = false): List<DoseEvent> {
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
            event.status == DoseEventStatus.MISSED || (keepFirstRepeat && event.status == current.status) -> existing
            else -> existing.map { if (it === current) event else it }
        }
    }

    /** Collapses legacy stored events (older ID schemes) to one terminal event per dose, oldest write first. */
    fun collapse(events: List<DoseEvent>): List<DoseEvent> = collapse(events, keepFirstRepeat = false)

    private fun collapse(events: List<DoseEvent>, keepFirstRepeat: Boolean): List<DoseEvent> =
        events.sortedBy { it.recordedAt?.epochSeconds ?: Long.MIN_VALUE }.fold(emptyList()) { acc, event ->
            if (!isTerminal(event.status) && acc.any { it.eventId == event.eventId }) acc else mergeStored(acc, event, keepFirstRepeat)
        }

    /**
     * What to read and show, never to store: [collapse], with terminal events older builds kept at a snoozed time moved
     * to the original time, so a dose recorded both ways is one row. See [provenOriginal] for what cannot be merged.
     * Consecutive taps of one status show the first, the real time; [keepFirstRepeat] false keeps the last, which only
     * the supply count wants (see MedicationSupplies).
     */
    fun perDose(events: List<DoseEvent>, keepFirstRepeat: Boolean = true): List<DoseEvent> {
        val original = originals(events)
        return collapse(
            events.map { event ->
                if (isTerminal(event.status)) event.copy(scheduledFor = provenOriginal(original, event.scheduleId, event.scheduledFor)) else event
            },
            keepFirstRepeat,
        )
    }

    private const val SNOOZE_SECONDS = 600L
}
