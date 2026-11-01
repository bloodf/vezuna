package app.vezuna.health

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.vezuna.domain.HeartRateBucket
import app.vezuna.domain.StepsDay
import app.vezuna.domain.VitalsBatch
import app.vezuna.domain.WeightReading
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId

val VezunaHealthPermissions: Set<String> = setOf(
    HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthPermission.getReadPermission(StepsRecord::class),
    HealthPermission.getReadPermission(WeightRecord::class),
)

/**
 * Reads aggregates, not raw records, for steps and heart rate: Health Connect de-duplicates overlapping sources
 * itself, and a year of per-minute heart rate would not fit in the encrypted snapshot. Weight is sparse and read raw.
 * Without READ_HEALTH_DATA_HISTORY (deliberately not requested) data older than 30 days before the first grant is
 * unavailable, so older weights simply do not appear.
 */
class AndroidHealthConnectImporter(private val context: Context) : DeviceVitalsSource {
    private val client: HealthConnectClient get() = HealthConnectClient.getOrCreate(context)

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    suspend fun hasPermissions(): Boolean =
        sdkStatus() == HealthConnectClient.SDK_AVAILABLE && client.permissionController.getGrantedPermissions().containsAll(VezunaHealthPermissions)

    override suspend fun read(request: VitalsReadRequest): VitalsBatch {
        check(sdkStatus() == HealthConnectClient.SDK_AVAILABLE) { "Health Connect is not available on this device" }
        check(hasPermissions()) { "Read-only Health Connect permissions have not been granted" }
        val zone = ZoneId.of(request.zone.id)
        val now = Instant.now()
        val windowStart = request.replaceFrom.toJava().atStartOfDay(zone).toInstant()
        val steps = readSteps(request, zone, now)
        val heart = readHeartRate(windowStart, now)
        val weights = readWeights(request.weightsFrom.toJava().atStartOfDay(zone).toInstant(), now)
        return VitalsBatch(
            replaceFrom = request.replaceFrom,
            steps = steps.items,
            heartRate = heart.items,
            weights = weights.items,
            skipped = steps.skipped + heart.skipped + weights.skipped,
        )
    }

    private class Mapped<T>(val items: List<T>, val skipped: Int)

    private suspend fun readSteps(request: VitalsReadRequest, zone: ZoneId, now: Instant): Mapped<StepsDay> {
        val start = request.replaceFrom.toJava().atStartOfDay()
        val end = LocalDateTime.ofInstant(now, zone)
        val groups = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, end), Period.ofDays(1)),
        )
        val mapped = groups.mapNotNull { group ->
            val total = group.result[StepsRecord.COUNT_TOTAL] ?: return@mapNotNull null
            StepsDay.ofOrNull(group.startTime.toLocalDate().toKotlin(), total)
        }
        val present = groups.count { it.result[StepsRecord.COUNT_TOTAL] != null }
        return Mapped(mapped, present - mapped.size)
    }

    private suspend fun readHeartRate(start: Instant, end: Instant): Mapped<HeartRateBucket> {
        val metrics = setOf(HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX, HeartRateRecord.MEASUREMENTS_COUNT)
        val groups = client.aggregateGroupByDuration(
            AggregateGroupByDurationRequest(metrics, TimeRangeFilter.between(start, end), Duration.ofHours(1)),
        ).filter { it.result[HeartRateRecord.MEASUREMENTS_COUNT] != null }
        val mapped = groups.mapNotNull { group ->
            HeartRateBucket.ofOrNull(
                group.startTime.toKotlin(),
                group.result[HeartRateRecord.BPM_MIN]?.toDouble() ?: return@mapNotNull null,
                group.result[HeartRateRecord.BPM_AVG]?.toDouble() ?: return@mapNotNull null,
                group.result[HeartRateRecord.BPM_MAX]?.toDouble() ?: return@mapNotNull null,
                (group.result[HeartRateRecord.MEASUREMENTS_COUNT] ?: 0L).toInt(),
            )
        }
        return Mapped(mapped, groups.size - mapped.size)
    }

    private suspend fun readWeights(start: Instant, end: Instant): Mapped<WeightReading> {
        val records = mutableListOf<WeightRecord>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.between(start, end), pageToken = pageToken))
            records += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        val mapped = records.mapNotNull { WeightReading.ofOrNull(it.metadata.id, it.time.toKotlin(), it.weight.inKilograms) }
        return Mapped(mapped, records.size - mapped.size)
    }

    private fun Instant.toKotlin() = kotlinx.datetime.Instant.fromEpochMilliseconds(toEpochMilli())
    private fun LocalDate.toKotlin() = kotlinx.datetime.LocalDate(year, monthValue, dayOfMonth)
    private fun kotlinx.datetime.LocalDate.toJava(): LocalDate = LocalDate.of(year, monthNumber, dayOfMonth)
}

class HealthConnectPermissionFlow(
    activity: ComponentActivity,
    private val onComplete: (Boolean) -> Unit,
) {
    private val launcher = activity.registerForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract(),
    ) { granted -> onComplete(granted.containsAll(VezunaHealthPermissions)) }

    fun request() = launcher.launch(VezunaHealthPermissions)
}
