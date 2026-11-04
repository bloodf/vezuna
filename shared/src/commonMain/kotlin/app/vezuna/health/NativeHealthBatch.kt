package app.vezuna.health

import app.vezuna.data.VitalsSyncContext
import app.vezuna.domain.HeartRateBucket
import app.vezuna.domain.StepsDay
import app.vezuna.domain.VitalsBatch
import app.vezuna.domain.WeightReading
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** HealthKit payload. Identity and zone are mandatory values captured before the platform read. */
@Serializable
internal class NativeHealthBatch(
    val profileId: String,
    val generation: Long,
    val zoneId: String,
    val replaceFromEpochSeconds: Long,
    val steps: List<NativeSteps> = emptyList(),
    val heartRate: List<NativeHeartRate> = emptyList(),
    val weights: List<NativeWeight> = emptyList(),
) {
    fun context(): VitalsSyncContext = VitalsSyncContext(profileId, generation, null, TimeZone.of(zoneId))

    fun toBatch(): VitalsBatch {
        val zone = TimeZone.of(zoneId)
        val steps = steps.map { StepsDay.ofOrNull(Instant.fromEpochSeconds(it.dayStartEpochSeconds).toLocalDateTime(zone).date, it.count.toLong()) }
        val heart = heartRate.map { HeartRateBucket.ofOrNull(Instant.fromEpochSeconds(it.startEpochSeconds), it.min, it.avg, it.max) }
        val weights = weights.map { WeightReading.ofOrNull(it.recordId, Instant.fromEpochSeconds(it.epochSeconds), it.kg) }
        return VitalsBatch(
            replaceFrom = Instant.fromEpochSeconds(replaceFromEpochSeconds).toLocalDateTime(zone).date,
            steps = steps.filterNotNull(), heartRate = heart.filterNotNull(), weights = weights.filterNotNull(),
            skipped = (steps + heart + weights).count { it == null },
        )
    }
}

@Serializable
internal class NativeSteps(val dayStartEpochSeconds: Long, val count: Double)
@Serializable
internal class NativeHeartRate(val startEpochSeconds: Long, val min: Double, val avg: Double, val max: Double)
@Serializable
internal class NativeWeight(val recordId: String, val epochSeconds: Long, val kg: Double)
