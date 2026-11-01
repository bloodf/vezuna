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

/** Steps for one local day, as aggregated (and de-duplicated across sources) by the platform. */
@Serializable
data class StepsDay(val date: LocalDate, val count: Long) {
    companion object {
        fun ofOrNull(date: LocalDate, count: Long): StepsDay? = if (count in 0..MAX_STEPS) StepsDay(date, count) else null
        private const val MAX_STEPS = 1_000_000L
    }
}

/** Heart-rate statistics for one hour starting at [start]. */
@Serializable
data class HeartRateBucket(val start: Instant, val min: Double, val avg: Double, val max: Double, val count: Int) {
    companion object {
        fun ofOrNull(start: Instant, min: Double, avg: Double, max: Double, count: Int): HeartRateBucket? =
            if (validRate(min, avg, max) && count > 0) HeartRateBucket(start, min, avg, max, count) else null
    }
}

/** Heart-rate statistics for a whole local day (hourly buckets older than [DeviceVitals.RAW_DAYS] roll up into this). */
@Serializable
data class HeartRateDay(val date: LocalDate, val min: Double, val avg: Double, val max: Double, val count: Int)

@Serializable
data class WeightReading(val recordId: String, val time: Instant, val kg: Double) {
    companion object {
        fun ofOrNull(recordId: String, time: Instant, kg: Double): WeightReading? =
            if (recordId.isNotBlank() && kg.isFinite() && kg in 1.0..700.0) WeightReading(recordId, time, kg) else null
    }
}

private fun validRate(min: Double, avg: Double, max: Double) =
    min.isFinite() && avg.isFinite() && max.isFinite() && min >= 20 && max <= 300 && min <= avg && avg <= max

/** Raw sample of the first (never released) vitals format; only decoded so it can be migrated. */
@Serializable
data class VitalSample(val recordId: String, val metric: VitalMetric, val value: Double, val start: Instant, val end: Instant)

@Serializable
data class VitalDailySummary(val metric: VitalMetric, val date: LocalDate, val value: Double, val count: Int)

@Serializable
data class ProfileVitals(
    val steps: List<StepsDay> = emptyList(),
    val heartRateHourly: List<HeartRateBucket> = emptyList(),
    val heartRateDaily: List<HeartRateDay> = emptyList(),
    val weights: List<WeightReading> = emptyList(),
    val lastSync: Instant? = null,
    val samples: List<VitalSample> = emptyList(),
    val dailySummaries: List<VitalDailySummary> = emptyList(),
) {
    val isEmpty: Boolean get() = steps.isEmpty() && heartRateHourly.isEmpty() && heartRateDaily.isEmpty() && weights.isEmpty() && samples.isEmpty()

    /**
     * Moves the raw first-format samples into the current representation. Raw steps and heart rate are dropped:
     * they could contain overlapping sources and the next sync re-reads the recent window from the platform.
     */
    fun migrated(): ProfileVitals = if (samples.isEmpty() && dailySummaries.isEmpty()) this else copy(
        weights = (weights + samples.filter { it.metric == VitalMetric.WEIGHT_KG }.mapNotNull { WeightReading.ofOrNull(it.recordId, it.start, it.value) })
            .distinctBy { it.recordId },
        samples = emptyList(),
        dailySummaries = emptyList(),
    )
}

/**
 * Everything read from the platform for one sync. Steps, heart rate and weights dated on or after [replaceFrom]
 * REPLACE what is stored for that window (so deletions reconcile); older weights are only added by record id.
 * [skipped] counts platform values rejected at the mapping boundary.
 */
data class VitalsBatch(
    val replaceFrom: LocalDate,
    val steps: List<StepsDay> = emptyList(),
    val heartRate: List<HeartRateBucket> = emptyList(),
    val weights: List<WeightReading> = emptyList(),
    val deletedWeightIds: List<String> = emptyList(),
    val skipped: Int = 0,
    /** False for data that was not read from the platform (migrations), so the next sync still reads the full first window. */
    val markSynced: Boolean = true,
)

/** Device health data belongs to one linked profile (default: the first created); others never receive it. */
object HealthLink {
    fun resolve(linkedId: String?, profiles: List<Profile>): String? =
        profiles.firstOrNull { it.profileId == linkedId }?.profileId
            ?: profiles.minWithOrNull(compareBy({ it.createdAt }, { it.profileId }))?.profileId
}

/** Which days a sync re-reads. Late data and deletions inside the trailing window heal on the next sync. */
object VitalsWindow {
    const val TRAILING_DAYS = 7
    const val FIRST_DAYS = 30

    // Health Connect without READ_HEALTH_DATA_HISTORY only serves data from 30 days before the first grant,
    // so a one-year weight read returns what is available and nothing older.
    const val FIRST_WEIGHT_DAYS = 365

    fun replaceFrom(lastSync: Instant?, now: Instant, zone: TimeZone): LocalDate {
        val today = now.toLocalDateTime(zone).date
        val floor = today.minus(FIRST_DAYS, DateTimeUnit.DAY)
        if (lastSync == null) return floor
        return maxOf(lastSync.toLocalDateTime(zone).date.minus(TRAILING_DAYS, DateTimeUnit.DAY), floor)
    }

    fun weightsFrom(lastSync: Instant?, now: Instant, zone: TimeZone): LocalDate =
        if (lastSync == null) now.toLocalDateTime(zone).date.minus(FIRST_WEIGHT_DAYS, DateTimeUnit.DAY) else replaceFrom(lastSync, now, zone)
}

object DeviceVitals {
    const val RAW_DAYS = 31
    const val RETENTION_DAYS = 365

    fun ingest(stored: ProfileVitals, batch: VitalsBatch, now: Instant, zone: TimeZone): ProfileVitals {
        val current = stored.migrated()
        val from = batch.replaceFrom
        val hourCutoff = now - RAW_DAYS.days
        val steps = (current.steps.filter { it.date < from } + batch.steps).associateBy { it.date }.values
        // Hourly buckets older than the raw window would be rolled up again on every re-read, so they are not accepted.
        val hourly = (current.heartRateHourly.filter { it.start.toLocalDateTime(zone).date < from } + batch.heartRate.filter { it.start >= hourCutoff })
            .associateBy { it.start }.values
        val replacedIds = (batch.weights.map { it.recordId } + batch.deletedWeightIds).toSet()
        val weights = (current.weights.filter { it.recordId !in replacedIds && it.time.toLocalDateTime(zone).date < from } + batch.weights)
            .associateBy { it.recordId }.values
        return retain(current.copy(steps = steps.toList(), heartRateHourly = hourly.toList(), weights = weights.toList(), lastSync = if (batch.markSynced) now else current.lastSync), now, zone)
    }

    /** How many entries of [batch] are actually present in [result] (rejected or expired ones do not count). */
    fun storedCount(result: ProfileVitals, batch: VitalsBatch): Int {
        val steps = result.steps.map { it.date }.toSet()
        val hours = result.heartRateHourly.map { it.start }.toSet()
        val weights = result.weights.map { it.recordId }.toSet()
        return batch.steps.count { it.date in steps } + batch.heartRate.count { it.start in hours } + batch.weights.count { it.recordId in weights }
    }

    private fun retain(vitals: ProfileVitals, now: Instant, zone: TimeZone): ProfileVitals {
        val rawCutoff = now - RAW_DAYS.days
        val keepFrom = now.toLocalDateTime(zone).date.minus(RETENTION_DAYS, DateTimeUnit.DAY)
        val (stale, fresh) = vitals.heartRateHourly.partition { it.start < rawCutoff }
        val rolled = stale.groupBy { it.start.toLocalDateTime(zone).date }.map { (date, items) -> combine(date, items.map { it.toDay(date) }) }
        val daily = (vitals.heartRateDaily + rolled).groupBy { it.date }.map { (date, items) -> combine(date, items) }
        return vitals.copy(
            steps = vitals.steps.filter { it.date >= keepFrom }.sortedBy { it.date },
            heartRateHourly = fresh.sortedBy { it.start },
            heartRateDaily = daily.filter { it.date >= keepFrom }.sortedBy { it.date },
            weights = vitals.weights.filter { it.time.toLocalDateTime(zone).date >= keepFrom }.sortedBy { it.time },
        )
    }

    private fun HeartRateBucket.toDay(date: LocalDate) = HeartRateDay(date, min, avg, max, count)

    private fun combine(date: LocalDate, items: List<HeartRateDay>): HeartRateDay {
        val count = items.sumOf { it.count }
        return HeartRateDay(date, items.minOf { it.min }, items.sumOf { it.avg * it.count } / count, items.maxOf { it.max }, count)
    }

    /** Chart points (epoch seconds, value): daily steps, daily average heart rate, every weight reading. */
    fun series(vitals: ProfileVitals, metric: VitalMetric, zone: TimeZone): List<Pair<Long, Double>> = when (metric) {
        VitalMetric.STEPS -> vitals.steps.sortedBy { it.date }.map { it.date.atStartOfDayIn(zone).epochSeconds to it.count.toDouble() }
        VitalMetric.WEIGHT_KG -> vitals.weights.sortedBy { it.time }.map { it.time.epochSeconds to it.kg }
        VitalMetric.HEART_RATE_BPM -> {
            val hourly = vitals.heartRateHourly.groupBy { it.start.toLocalDateTime(zone).date }.map { (date, items) -> combine(date, items.map { it.toDay(date) }) }
            (vitals.heartRateDaily + hourly).groupBy { it.date }.map { (date, items) -> combine(date, items) }
                .sortedBy { it.date }.map { it.date.atStartOfDayIn(zone).epochSeconds to it.avg }
        }
    }
}
