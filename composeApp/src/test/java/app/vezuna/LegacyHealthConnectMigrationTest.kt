package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.domain.Profile
import app.vezuna.health.LegacyHealthConnectMigration
import app.vezuna.security.CipherEnvelope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LegacyHealthConnectMigrationTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val json = Json { encodeDefaults = true }

    private suspend fun FakeSecureVault.putLegacy(plain: String) {
        val envelope = encrypt(plain.encodeToByteArray(), "Vezuna Health Connect v1".encodeToByteArray())
        write("health-connect-v1", json.encodeToString(CipherEnvelope.serializer(), envelope).encodeToByteArray())
    }

    private suspend fun repo() = InMemoryVezunaRepository().also {
        it.createProfile(Profile("a", "A", createdAt = Instant.fromEpochSeconds(1)))
        it.createProfile(Profile("b", "B", createdAt = Instant.fromEpochSeconds(2)))
    }

    @Test
    fun legacyWeightsMoveToLinkedProfileOnceWithoutMarkingSynced() = runTest {
        val vault = FakeSecureVault()
        vault.putLegacy(
            """{"cursor":"t","samples":{"w|WEIGHT_KG|x":{"recordId":"w","metric":"WEIGHT_KG","value":70.0,"startTime":"2026-10-30T00:00:00Z","endTime":"2026-10-30T00:00:00Z"},
            "s|STEPS|x":{"recordId":"s","metric":"STEPS","value":9.0,"startTime":"2026-10-30T00:00:00Z","endTime":"2026-10-30T01:00:00Z"}}}""",
        )
        val repository = repo()
        LegacyHealthConnectMigration(vault, repository, { now }).run()
        val vitals = repository.readProfile("a").vitals
        assertEquals(listOf("w"), vitals.weights.map { it.recordId })
        assertEquals(emptyList(), vitals.steps)
        assertNull(vitals.lastSync)
        assertEquals(emptyList(), repository.readProfile("b").vitals.weights)
        assertNull(vault.read("health-connect-v1"))
    }

    @Test
    fun corruptLegacyFileIsDeletedAndDoesNotThrow() = runTest {
        val vault = FakeSecureVault()
        vault.write("health-connect-v1", "not json".encodeToByteArray())
        LegacyHealthConnectMigration(vault, repo(), { now }).run()
        assertNull(vault.read("health-connect-v1"))
    }
}
