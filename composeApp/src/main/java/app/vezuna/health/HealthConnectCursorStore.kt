package app.vezuna.health

import app.vezuna.data.VezunaRepository
import app.vezuna.domain.VitalMetric
import app.vezuna.domain.VitalSample
import app.vezuna.domain.VitalsBatch
import app.vezuna.security.CipherEnvelope
import app.vezuna.security.SecureVault
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Keeps the Health Connect change token in the encrypted vault. The first read also moves samples left in the
 * old global `health-connect-v1` key into the linked profile, then deletes that key, so it is used only once.
 */
class HealthConnectCursorStore(
    private val vault: SecureVault,
    private val repository: VezunaRepository,
    private val clock: () -> Instant,
    private val json: Json = Json { encodeDefaults = true },
) : VitalsCursorStore {
    @Serializable
    private data class LegacySample(val recordId: String, val metric: VitalMetric, val value: Double, val startTime: String, val endTime: String)

    @Serializable
    private data class LegacyState(val cursor: String? = null, val samples: Map<String, LegacySample> = emptyMap())

    override suspend fun read(): String? {
        migrateLegacy()
        return readEncrypted(CURSOR_KEY)?.decodeToString()
    }

    override suspend fun write(cursor: String) = writeEncrypted(CURSOR_KEY, cursor.encodeToByteArray())

    private suspend fun migrateLegacy() {
        val legacy = readEncrypted(LEGACY_KEY)?.let { json.decodeFromString(LegacyState.serializer(), it.decodeToString()) } ?: return
        val samples = legacy.samples.values.map {
            VitalSample(it.recordId, it.metric, it.value, Instant.parse(it.startTime), Instant.parse(it.endTime))
        }
        // Order matters: samples persisted, then the cursor, then the old key is dropped; a crash in between just repeats.
        repository.ingestDeviceVitals(VitalsBatch(samples), clock())
        if (legacy.cursor != null && readEncrypted(CURSOR_KEY) == null) write(legacy.cursor)
        vault.delete(LEGACY_KEY)
    }

    private suspend fun readEncrypted(key: String): ByteArray? {
        val bytes = vault.read(key) ?: return null
        return vault.decrypt(json.decodeFromString(CipherEnvelope.serializer(), bytes.decodeToString()))
    }

    private suspend fun writeEncrypted(key: String, plain: ByteArray) {
        val envelope = vault.encrypt(plain, if (key == CURSOR_KEY) CURSOR_AAD else LEGACY_AAD)
        vault.write(key, json.encodeToString(CipherEnvelope.serializer(), envelope).encodeToByteArray())
    }

    internal companion object {
        const val LEGACY_KEY = "health-connect-v1"
        const val CURSOR_KEY = "health-connect-cursor-v1"
        private val LEGACY_AAD = "Vezuna Health Connect v1".encodeToByteArray()
        private val CURSOR_AAD = "Vezuna Health Connect cursor v1".encodeToByteArray()
    }
}
