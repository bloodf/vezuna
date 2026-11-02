package app.vezuna

import app.vezuna.domain.ProfileVitals
import app.vezuna.health.VitalsStore
import app.vezuna.security.CipherEnvelope
import app.vezuna.security.SecureVault
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VitalsStoreTest {
    private class FakeVault(private val canQuarantine: Boolean = true) : SecureVault {
        val files = mutableMapOf<String, ByteArray>()
        val quarantined = mutableListOf<String>()
        override suspend fun encrypt(plainText: ByteArray, associatedData: ByteArray) =
            CipherEnvelope("test", "k", ByteArray(0), plainText, associatedData)
        override suspend fun decrypt(envelope: CipherEnvelope) = envelope.cipherText
        override suspend fun write(key: String, ciphertext: ByteArray) { files[key] = ciphertext }
        override suspend fun read(key: String) = files[key]
        override suspend fun delete(key: String) { files.remove(key) }
        override suspend fun quarantineCurrent(key: String): Boolean {
            if (!canQuarantine) return false
            files.remove(key)?.let { quarantined += key }
            return true
        }
    }

    private val json = Json { encodeDefaults = true }
    private val vitals = ProfileVitals(lastSync = Instant.parse("2026-11-01T12:00:00Z"))

    @Test
    fun saveThenLoadRoundTrips() = runTest {
        val store = VitalsStore(FakeVault(), json)
        assertNull(store.load("p1"))
        store.save("p1", vitals)
        assertEquals(vitals.lastSync, store.load("p1")?.lastSync)
    }

    @Test
    fun unreadableFileIsQuarantinedAndReported() = runTest {
        val vault = FakeVault()
        vault.files["repository-vitals-p1-v1"] = "not json".encodeToByteArray()
        var cause: Throwable? = null
        assertNull(VitalsStore(vault, json).load("p1") { cause = it })
        assertNotNull(cause)
        assertEquals(listOf("repository-vitals-p1-v1"), vault.quarantined)
    }

    @Test
    fun saveRefusedWhenUnreadableFileCannotBePreserved() = runTest {
        val vault = FakeVault(canQuarantine = false)
        vault.files["repository-vitals-p1-v1"] = "not json".encodeToByteArray()
        val store = VitalsStore(vault, json)
        assertNull(store.load("p1"))
        assertFailsWith<IllegalStateException> { store.save("p1", vitals) }
        assertEquals("not json", vault.files["repository-vitals-p1-v1"]?.decodeToString())
        store.delete("p1")
        store.save("p1", vitals)
    }

    @Test
    fun sweepRemovesOnlyOrphans() = runTest {
        val vault = FakeVault()
        val store = VitalsStore(vault, json)
        store.save("keep", vitals)
        store.save("orphan", vitals)
        store.sweep(setOf("keep"))
        assertNotNull(store.load("keep"))
        assertTrue("repository-vitals-orphan-v1" !in vault.files)
    }

    @Test
    fun corruptIndexOnlyMeansNoOrphansSwept() = runTest {
        val vault = FakeVault()
        vault.files["repository-vitals-index-v1"] = "garbage".encodeToByteArray()
        val store = VitalsStore(vault, json)
        store.sweep(emptySet())
        store.save("p1", vitals)
        assertNotNull(store.load("p1"))
    }
}
