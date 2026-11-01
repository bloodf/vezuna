package app.vezuna

import app.vezuna.data.PersistentVezunaRepository
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileVitals
import app.vezuna.domain.StepsDay
import app.vezuna.domain.VitalsBatch
import app.vezuna.domain.WeightReading
import app.vezuna.health.VitalsStore
import app.vezuna.security.CipherEnvelope
import app.vezuna.security.SecureVault
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VitalsStoreSafetyTest {
    private val json = Json { encodeDefaults = true }
    private val now = Instant.parse("2026-11-01T00:00:00Z")

    /** Vault whose quarantine can preserve files (or not) and whose writes can be made to fail. */
    private class ControlledVault(private val canQuarantine: Boolean) : SecureVault {
        val inner = FakeSecureVault()
        val quarantined = mutableMapOf<String, ByteArray>()
        var failWrites = false
        override suspend fun encrypt(plainText: ByteArray, associatedData: ByteArray) = inner.encrypt(plainText, associatedData)
        override suspend fun decrypt(envelope: CipherEnvelope) = inner.decrypt(envelope)
        override suspend fun write(key: String, ciphertext: ByteArray) { if (failWrites) error("disk full"); inner.write(key, ciphertext) }
        override suspend fun read(key: String) = inner.read(key)
        override suspend fun delete(key: String) = inner.delete(key)
        override suspend fun quarantineCurrent(key: String): Boolean {
            if (!canQuarantine) return false
            inner.read(key)?.let { quarantined[key] = it }
            inner.delete(key)
            return true
        }
    }

    private val weight = WeightReading("w", Instant.parse("2026-08-01T00:00:00Z"), 70.0)

    @Test
    fun unreadableVitalsAreQuarantinedNotOverwritten() = runTest {
        val vault = ControlledVault(canQuarantine = true)
        vault.write("repository-vitals-p-v1", "garbage".encodeToByteArray())
        val store = VitalsStore(vault, json)
        var reported: Throwable? = null
        assertNull(store.load("p") { reported = it })
        assertNotNull(reported)
        assertEquals("garbage", vault.quarantined.getValue("repository-vitals-p-v1").decodeToString())
        store.save("p", ProfileVitals(weights = listOf(weight)))
        assertEquals(listOf(weight), store.load("p")?.weights)
    }

    @Test
    fun unreadableVitalsThatCannotBePreservedBlockSaving() = runTest {
        val vault = ControlledVault(canQuarantine = false)
        vault.write("repository-vitals-p-v1", "garbage".encodeToByteArray())
        val store = VitalsStore(vault, json)
        assertNull(store.load("p"))
        assertFailsWith<IllegalStateException> { store.save("p", ProfileVitals()) }
        assertEquals("garbage", vault.read("repository-vitals-p-v1")?.decodeToString())
    }

    @Test
    fun failedProfileDeleteKeepsVitalsAndOrphansAreSweptOnLoad() = runTest {
        val vault = ControlledVault(canQuarantine = true)
        val repository = PersistentVezunaRepository(vault)
        repository.load()
        repository.createProfile(Profile("a", "A", createdAt = Instant.fromEpochMilliseconds(1)))
        repository.createProfile(Profile("b", "B", createdAt = Instant.fromEpochMilliseconds(2)))
        repository.setLinkedHealthProfile("b")
        repository.ingestDeviceVitals(VitalsBatch(LocalDate(2026, 10, 30), weights = listOf(weight)), now)

        vault.failWrites = true
        assertFailsWith<IllegalStateException> { repository.deleteProfile("b") }
        vault.failWrites = false
        assertEquals(listOf(weight), repository.readProfile("b").vitals.weights)
        assertNotNull(vault.read("repository-vitals-b-v1"))
    }

    @Test
    fun replaceVitalsWritesTheFileAndKeepsLastSync() = runTest {
        val vault = ControlledVault(canQuarantine = true)
        val repository = PersistentVezunaRepository(vault)
        repository.load()
        repository.createProfile(Profile("a", "A", createdAt = Instant.fromEpochMilliseconds(1)))
        val restoredVitals = ProfileVitals(steps = listOf(StepsDay(LocalDate(2026, 10, 1), 5)), weights = listOf(weight), lastSync = now)
        repository.replaceVitals("a", restoredVitals)

        val reopened = PersistentVezunaRepository(vault)
        reopened.load()
        assertEquals(restoredVitals, reopened.readProfile("a").vitals)
        assertFailsWith<IllegalArgumentException> { repository.replaceVitals("missing", restoredVitals) }
    }

    @Test
    fun orphanFileIsSweptWhenItsProfileIsGone() = runTest {
        val vault = ControlledVault(canQuarantine = true)
        val store = VitalsStore(vault, json)
        store.save("gone", ProfileVitals(weights = listOf(weight)))
        store.save("kept", ProfileVitals(weights = listOf(weight)))
        store.sweep(setOf("kept"))
        assertNull(vault.read("repository-vitals-gone-v1"))
        assertTrue(vault.read("repository-vitals-kept-v1") != null)
    }
}
