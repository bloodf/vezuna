package app.vezuna.health

import app.vezuna.data.VezunaRepository
import app.vezuna.domain.VitalsBatch
import app.vezuna.domain.WeightReading
import app.vezuna.security.CipherEnvelope
import app.vezuna.security.SecureVault
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Moves weights from the old global `health-connect-v1` vault file into the linked profile, then deletes the file.
 * Raw steps and heart rate in it are dropped (they may overlap across sources); the next sync re-reads them as aggregates.
 * An unreadable file is logged and deleted so it can never block a sync.
 */
class LegacyHealthConnectMigration(
    private val vault: SecureVault,
    private val repository: VezunaRepository,
    private val clock: () -> Instant,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true },
) {
    @Serializable
    private data class Sample(val recordId: String, val metric: String, val value: Double, val startTime: String)

    @Serializable
    private data class State(val samples: Map<String, Sample> = emptyMap())

    suspend fun run() {
        val stored = vault.read(KEY) ?: return
        val weights = try {
            val envelope = json.decodeFromString(CipherEnvelope.serializer(), stored.decodeToString())
            json.decodeFromString(State.serializer(), vault.decrypt(envelope).decodeToString()).samples.values
                .filter { it.metric == "WEIGHT_KG" }
                .mapNotNull { WeightReading.ofOrNull(it.recordId, Instant.parse(it.startTime), it.value) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.log(Level.WARNING, "Discarding unreadable legacy Health Connect data", error)
            emptyList()
        }
        if (weights.isNotEmpty()) {
            val today = clock().toLocalDateTime(TimeZone.currentSystemDefault()).date
            // Replacement starts today, so nothing already stored is removed; only the legacy weights are added.
            repository.ingestDeviceVitals(VitalsBatch(replaceFrom = today, weights = weights, markSynced = false), clock())
        }
        vault.delete(KEY)
    }

    internal companion object {
        const val KEY = "health-connect-v1"
        private val logger: Logger = Logger.getLogger("LegacyHealthConnectMigration")
    }
}
