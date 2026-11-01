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
import app.vezuna.security.CipherEnvelope
import app.vezuna.security.SecureVault
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

val VezunaHealthPermissions: Set<String> = setOf(
    HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthPermission.getReadPermission(StepsRecord::class),
    HealthPermission.getReadPermission(WeightRecord::class),
)

enum class HealthMetric { HEART_RATE_BPM, STEPS, WEIGHT_KG }

@Serializable
data class HealthMetricSample(
    val recordId: String,
    val metric: HealthMetric,
    val value: Double,
    val startTime: String,
    val endTime: String,
)

data class HealthConnectBatch(
    val samples: List<HealthMetricSample>,
    val deletedRecordIds: List<String>,
    val nextCursor: String,
)

interface IncrementalHealthConnectImporter {
    suspend fun import(cursor: String?): HealthConnectBatch
}

class AndroidHealthConnectImporter(private val context: Context) : IncrementalHealthConnectImporter {
    private val client: HealthConnectClient get() = HealthConnectClient.getOrCreate(context)

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    suspend fun hasPermissions(): Boolean =
        sdkStatus() == HealthConnectClient.SDK_AVAILABLE && client.permissionController.getGrantedPermissions().containsAll(VezunaHealthPermissions)

    override suspend fun import(cursor: String?): HealthConnectBatch {
        check(sdkStatus() == HealthConnectClient.SDK_AVAILABLE) { "Health Connect is not available on this device" }
        check(hasPermissions()) { "Read-only Health Connect permissions have not been granted" }
        return if (cursor == null) initialImport() else changesImport(cursor)
    }

    private suspend fun initialImport(): HealthConnectBatch {
        val end = Instant.now()
        val filter = TimeRangeFilter.between(end.minusSeconds(30 * 24 * 60 * 60), end)
        val samples = buildList {
            readAll(filter, HeartRateRecord::class).forEach { record -> addAll(record.toSamples()) }
            readAll(filter, StepsRecord::class).forEach { record -> addAll(record.toSamples()) }
            readAll(filter, WeightRecord::class).forEach { record -> addAll(record.toSamples()) }
        }
        val token = client.getChangesToken(ChangesTokenRequest(setOf(HeartRateRecord::class, StepsRecord::class, WeightRecord::class)))
        return HealthConnectBatch(samples, emptyList(), token)
    }

    private suspend fun changesImport(cursor: String): HealthConnectBatch {
        val response = client.getChanges(cursor)
        val samples = mutableListOf<HealthMetricSample>()
        val deleted = mutableListOf<String>()
        response.changes.forEach { change ->
            when (change) {
                is UpsertionChange -> samples += change.record.toSamples()
                is DeletionChange -> deleted += change.recordId
            }
        }
        return HealthConnectBatch(samples, deleted, response.nextChangesToken)
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

    private fun Record.toSamples(): List<HealthMetricSample> = when (this) {
        is HeartRateRecord -> samples.map { sample ->
            HealthMetricSample(metadata.id, HealthMetric.HEART_RATE_BPM, sample.beatsPerMinute.toDouble(), sample.time.toString(), sample.time.toString())
        }
        is StepsRecord -> listOf(HealthMetricSample(metadata.id, HealthMetric.STEPS, count.toDouble(), startTime.toString(), endTime.toString()))
        is WeightRecord -> listOf(HealthMetricSample(metadata.id, HealthMetric.WEIGHT_KG, weight.inKilograms, time.toString(), time.toString()))
        else -> emptyList()
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

/** Commits the cursor only after the encrypted sample snapshot has been saved. */
class HealthConnectSyncCoordinator(
    private val importer: IncrementalHealthConnectImporter,
    private val vault: SecureVault,
    private val json: Json = Json { encodeDefaults = true },
) {
    @Serializable
    private data class State(val cursor: String? = null, val samples: Map<String, HealthMetricSample> = emptyMap())

    private val mutex = Mutex()

    suspend fun synchronize(): Int = mutex.withLock {
        val previous = readState()
        val batch = try {
            importer.import(previous.cursor)
        } catch (error: IllegalArgumentException) {
            // Health Connect change tokens can expire; rebuild a token without exposing partial writes.
            if (previous.cursor == null) throw error else importer.import(null)
        }
        val updatedSamples = previous.samples.toMutableMap()
        batch.deletedRecordIds.forEach { recordId ->
            updatedSamples.keys.removeAll { key -> key.startsWith("$recordId|") }
        }
        batch.samples.forEach { sample ->
            updatedSamples["${sample.recordId}|${sample.metric}|${sample.startTime}"] = sample
        }
        val updated = State(batch.nextCursor, updatedSamples)
        writeState(updated)
        batch.samples.size
    }

    private suspend fun readState(): State {
        val bytes = vault.read(STORAGE_KEY) ?: return State()
        val envelope = json.decodeFromString(CipherEnvelope.serializer(), bytes.decodeToString())
        return json.decodeFromString(State.serializer(), vault.decrypt(envelope).decodeToString())
    }

    private suspend fun writeState(state: State) {
        val envelope = vault.encrypt(json.encodeToString(State.serializer(), state).encodeToByteArray(), AAD)
        vault.write(STORAGE_KEY, json.encodeToString(CipherEnvelope.serializer(), envelope).encodeToByteArray())
    }

    private companion object {
        const val STORAGE_KEY = "health-connect-v1"
        val AAD = "Vezuna Health Connect v1".encodeToByteArray()
    }
}
