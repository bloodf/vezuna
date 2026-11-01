package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.domain.Profile
import app.vezuna.health.HealthConnectCursorStore
import app.vezuna.security.CipherEnvelope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HealthConnectCursorStoreTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val json = Json { encodeDefaults = true }

    private suspend fun FakeSecureVault.putLegacy(plain: String) {
        val envelope = encrypt(plain.encodeToByteArray(), "Vezuna Health Connect v1".encodeToByteArray())
        write("health-connect-v1", json.encodeToString(CipherEnvelope.serializer(), envelope).encodeToByteArray())
    }

    @Test
    fun legacyGlobalSamplesMoveIntoLinkedProfileOnceAndKeyIsDropped() = runTest {
        val vault = FakeSecureVault()
        vault.putLegacy(
            """{"cursor":"legacy-token","samples":{"r1|WEIGHT_KG|2026-10-30T00:00:00Z":{"recordId":"r1","metric":"WEIGHT_KG","value":70.0,"startTime":"2026-10-30T00:00:00Z","endTime":"2026-10-30T00:00:00Z"}}}""",
        )
        val repository = InMemoryVezunaRepository()
        repository.createProfile(Profile("a", "A", createdAt = Instant.fromEpochSeconds(1)))
        repository.createProfile(Profile("b", "B", createdAt = Instant.fromEpochSeconds(2)))
        val store = HealthConnectCursorStore(vault, repository, { now })

        assertEquals("legacy-token", store.read())
        assertEquals(listOf("r1"), repository.readProfile("a").vitals.samples.map { it.recordId })
        assertEquals(emptyList(), repository.readProfile("b").vitals.samples)
        assertNull(vault.read("health-connect-v1"))

        assertEquals("legacy-token", store.read()) // second read migrates nothing further
        store.write("next")
        assertEquals("next", store.read())
    }

    @Test
    fun missingCursorReadsAsNull() = runTest {
        val repository = InMemoryVezunaRepository()
        assertNull(HealthConnectCursorStore(FakeSecureVault(), repository, { now }).read())
    }
}
