package app.vezuna.health

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.vezuna.domain.DeviceVitals
import app.vezuna.domain.VitalMetric
import app.vezuna.domain.VitalSample
import app.vezuna.domain.VitalsBatch
import java.time.Instant
import java.time.temporal.ChronoUnit

val VezunaHealthPermissions: Set<String> = setOf(
    HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthPermission.getReadPermission(StepsRecord::class),
    HealthPermission.getReadPermission(WeightRecord::class),
)

class AndroidHealthConnectImporter(private val context: Context) : DeviceVitalsSource {
    private val client: HealthConnectClient get() = HealthConnectClient.getOrCreate(context)

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    suspend fun hasPermissions(): Boolean =
        sdkStatus() == HealthConnectClient.SDK_AVAILABLE && client.permissionController.getGrantedPermissions().containsAll(VezunaHealthPermissions)

    private suspend fun requireReady() {
        check(sdkStatus() == HealthConnectClient.SDK_AVAILABLE) { "Health Connect is not available on this device" }
        check(hasPermissions()) { "Read-only Health Connect permissions have not been granted" }
    }

    override suspend fun initial(): VitalsFetch {
        requireReady()
        // Token first: anything changing while the history is read is then re-delivered, never lost.
        val token = client.getChangesToken(ChangesTokenRequest(setOf(HeartRateRecord::class, StepsRecord::class, WeightRecord::class)))
        val end = Instant.now()
        val recent = TimeRangeFilter.between(end.minus(RECENT_DAYS, ChronoUnit.DAYS), end)
        val weightHistory = TimeRangeFilter.between(end.minus(DeviceVitals.RETENTION_DAYS.toLong(), ChronoUnit.DAYS), end)
        val samples = buildList {
            readAll(recent, HeartRateRecord::class).forEach { addAll(it.toSamples()) }
            readAll(recent, StepsRecord::class).forEach { addAll(it.toSamples()) }
            readAll(weightHistory, WeightRecord::class).forEach { addAll(it.toSamples()) }
        }
        return VitalsFetch(VitalsBatch(samples), token)
    }

    override suspend fun changes(cursor: String): VitalsChangesPage {
        requireReady()
        val response = client.getChanges(cursor)
        if (response.changesTokenExpired) return VitalsChangesPage(VitalsBatch(emptyList()), cursor, hasMore = false, cursorExpired = true)
        val samples = mutableListOf<VitalSample>()
        val deleted = mutableListOf<String>()
        response.changes.forEach { change ->
            when (change) {
                is UpsertionChange -> samples += change.record.toSamples()
                is DeletionChange -> deleted += change.recordId
            }
        }
        return VitalsChangesPage(VitalsBatch(samples, deleted), response.nextChangesToken, response.hasMore)
    }

    private suspend fun <T : Record> readAll(filter: TimeRangeFilter, type: kotlin.reflect.KClass<T>): List<T> {
        val records = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(type, filter, pageToken = pageToken))
            records += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        return records
    }

    private fun Record.toSamples(): List<VitalSample> = when (this) {
        is HeartRateRecord -> samples.map { sample ->
            VitalSample(metadata.id, VitalMetric.HEART_RATE_BPM, sample.beatsPerMinute.toDouble(), sample.time.toKotlin(), sample.time.toKotlin())
        }
        is StepsRecord -> listOf(VitalSample(metadata.id, VitalMetric.STEPS, count.toDouble(), startTime.toKotlin(), endTime.toKotlin()))
        is WeightRecord -> listOf(VitalSample(metadata.id, VitalMetric.WEIGHT_KG, weight.inKilograms, time.toKotlin(), time.toKotlin()))
        else -> emptyList()
    }

    private fun Instant.toKotlin() = kotlinx.datetime.Instant.fromEpochMilliseconds(toEpochMilli())

    private companion object {
        const val RECENT_DAYS = 30L
    }
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
