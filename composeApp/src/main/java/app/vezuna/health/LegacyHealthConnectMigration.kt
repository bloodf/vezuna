package app.vezuna.health

import app.vezuna.data.VitalsSyncContext
import app.vezuna.data.VezunaRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import app.vezuna.domain.VitalsBatch
import app.vezuna.domain.WeightReading
import app.vezuna.security.CipherEnvelope
import app.vezuna.security.SecureVault
import kotlinx.coroutines.CancellationException
import kotlin.time.Instant
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

    suspend fun run(context: VitalsSyncContext): VitalsSyncContext {
        repository.withValidatedVitalsSync(context) {}
        val stored = vault.read(KEY) ?: return context
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
        currentCoroutineContext().ensureActive()
        val successor = if (weights.isNotEmpty()) {
            // Merge only: nothing already stored, including today's data, is replaced.
            repository.ingestDeviceVitalsAndCapture(context, VitalsBatch(replaceFrom = VitalsBatch.MERGE_ONLY, weights = weights, markSynced = false), clock()).context
        } else context
        // A relink or cancellation after durable ingest preserves the source for a safe retry.
        repository.withValidatedVitalsSync(successor) { vault.delete(KEY) }
        return successor
    }

    internal companion object {
        const val KEY = "health-connect-v1"
        private val logger: Logger = Logger.getLogger("LegacyHealthConnectMigration")
    }
}
