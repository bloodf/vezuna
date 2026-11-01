package app.vezuna.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.days

@Serializable
enum class VitalMetric { HEART_RATE_BPM, STEPS, WEIGHT_KG }

@Serializable
data class VitalSample(
    val recordId: String,
    val metric: VitalMetric,
    val value: Double,
    val start: Instant,
    val end: Instant,
) {
    init {
        require(recordId.isNotBlank() && value.isFinite() && end >= start)
    }

    val key: String get() = "$recordId|$metric|$start"
}

/** Heart rate: mean bpm of [count] samples. Steps: total of [count] records. */
@Serializable
data class VitalDailySummary(val metric: VitalMetric, val date: LocalDate, val value: Double, val count: Int)

@Serializable
data class ProfileVitals(
    val samples: List<VitalSample> = emptyList(),
    val dailySummaries: List<VitalDailySummary> = emptyList(),
)

data class VitalsBatch(val samples: List<VitalSample>, val deletedRecordIds: List<String> = emptyList())

/** Device health data belongs to one linked profile (default: the first created); others never receive it. */
object HealthLink {
    fun resolve(linkedId: String?, profiles: List<Profile>): String? =
        profiles.firstOrNull { it.profileId == linkedId }?.profileId
            ?: profiles.minWithOrNull(compareBy({ it.createdAt }, { it.profileId }))?.profileId
}

object DeviceVitals {
    const val RAW_DAYS = 31
    const val RETENTION_DAYS = 365

    fun ingest(current: ProfileVitals, batch: VitalsBatch, now: Instant, zone: TimeZone): ProfileVitals {
        val rawCutoff = now - RAW_DAYS.days
        val deleted = batch.deletedRecordIds.toSet()
        // Incoming heart rate/steps older than the raw window would be rolled up again on every re-import.
        val incoming = batch.samples.filter { rolledUp(it.metric).not() || it.start >= rawCutoff }
        val merged = (current.samples.filter { it.recordId !in deleted }.associateBy { it.key } +
            incoming.filter { it.recordId !in deleted }.associateBy { it.key }).values
        return retain(ProfileVitals(merged.toList(), current.dailySummaries), now, zone)
    }

    /** Chart points (epoch seconds, value): daily average/total for heart rate and steps, every reading for weight. */
    fun series(vitals: ProfileVitals, metric: VitalMetric, zone: TimeZone): List<Pair<Long, Double>> {
        if (!rolledUp(metric)) {
            return vitals.samples.filter { it.metric == metric }.sortedBy { it.start }.map { it.start.epochSeconds to it.value }
        }
        val raw = vitals.samples.filter { it.metric == metric }.groupBy { it.start.toLocalDateTime(zone).date }
            .map { (date, items) -> VitalDailySummary(metric, date, summarize(metric, items), items.size) }
        return (vitals.dailySummaries.filter { it.metric == metric } + raw).groupBy { it.date }
            .map { (date, items) -> combine(metric, date, items) }
            .sortedBy { it.date }
            .map { it.date.atStartOfDayIn(zone).epochSeconds to it.value }
    }

    private fun rolledUp(metric: VitalMetric) = metric != VitalMetric.WEIGHT_KG

    private fun retain(vitals: ProfileVitals, now: Instant, zone: TimeZone): ProfileVitals {
        val rawCutoff = now - RAW_DAYS.days
        val keepFrom = now.toLocalDateTime(zone).date.minus(RETENTION_DAYS, DateTimeUnit.DAY)
        val (stale, fresh) = vitals.samples.partition { rolledUp(it.metric) && it.start < rawCutoff }
        val summaries = (vitals.dailySummaries + stale.groupBy { it.metric to it.start.toLocalDateTime(zone).date }.map { (group, items) ->
            VitalDailySummary(group.first, group.second, summarize(group.first, items), items.size)
        }).groupBy { it.metric to it.date }.map { (group, items) -> combine(group.first, group.second, items) }
        return ProfileVitals(
            samples = fresh.filter { it.start.toLocalDateTime(zone).date >= keepFrom }.sortedBy { it.start },
            dailySummaries = summaries.filter { it.date >= keepFrom }.sortedWith(compareBy({ it.date }, { it.metric })),
        )
    }

    private fun summarize(metric: VitalMetric, items: List<VitalSample>): Double =
        if (metric == VitalMetric.STEPS) items.sumOf { it.value } else items.sumOf { it.value } / items.size

    private fun combine(metric: VitalMetric, date: LocalDate, items: List<VitalDailySummary>): VitalDailySummary {
        val count = items.sumOf { it.count }
        val total = items.sumOf { if (metric == VitalMetric.STEPS) it.value else it.value * it.count }
        return VitalDailySummary(metric, date, if (metric == VitalMetric.STEPS) total else total / count, count)
    }
}
