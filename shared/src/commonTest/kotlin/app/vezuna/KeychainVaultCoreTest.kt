package app.vezuna

import app.vezuna.security.CipherEnvelope
import app.vezuna.security.KeyFileVaultCore
import app.vezuna.security.KeyStoreRead
import app.vezuna.security.KeyStoreStatus
import app.vezuna.security.VaultFailure
import app.vezuna.security.VaultFileOps
import app.vezuna.security.VaultKeyStore
import app.vezuna.security.VaultUnreadableException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeKeyFiles : VaultFileOps {
    val data = mutableMapOf<String, ByteArray?>()
    val quarantined = mutableListOf<String>()
    override fun exists(path: String) = data.containsKey(path)
    override fun read(path: String) = data[path]
    override fun write(path: String, bytes: ByteArray) { data[path] = bytes }
    override fun quarantine(path: String): Boolean { quarantined += path; data.remove(path); return true }
}

private class FakeKeyStore : VaultKeyStore {
    var key: ByteArray? = null
    var locked = false
    var failWrites = false
    var reads = 0
    var deletes = 0
    override fun read(): KeyStoreRead {
        reads++
        return when {
            locked -> KeyStoreRead(KeyStoreStatus.UNAVAILABLE)
            key == null -> KeyStoreRead(KeyStoreStatus.MISSING)
            else -> KeyStoreRead(KeyStoreStatus.FOUND, key)
        }
    }
    override fun write(key: ByteArray): Boolean { if (failWrites) return false; this.key = key; return true }
    override fun delete(): Boolean { deletes++; key = null; return true }
}

class KeychainVaultCoreTest {
    private val files = FakeKeyFiles()
    private val store = FakeKeyStore()
    private var generated = 0
    private val fileKey = ByteArray(32) { 7 }
    private val core = KeyFileVaultCore(
        files = files, keyPath = "key", algorithm = "ALG", keyId = "ID",
        randomKey = { generated++; ByteArray(32) { 9 } },
        seal = { key, plain, aad -> CipherEnvelope("x", "x", byteArrayOf(1), plain + key.take(1), aad) },
        open = { _, e -> e.cipherText },
        keyStore = store,
    )

    @Test
    fun migratesFileKeyToStoreThenQuarantinesFile() {
        files.data["key"] = fileKey

        core.encrypt(byteArrayOf(1), byteArrayOf())

        assertContentEquals(fileKey, store.key)
        assertEquals(listOf("key"), files.quarantined)
        assertFalse(files.exists("key"))
        assertEquals(0, generated)
    }

    @Test
    fun failedStoreWriteKeepsFileAndStillUsesItsKey() {
        files.data["key"] = fileKey
        store.failWrites = true

        core.encrypt(byteArrayOf(1), byteArrayOf())

        assertTrue(files.quarantined.isEmpty())
        assertContentEquals(fileKey, files.data["key"])
        assertEquals(0, generated)
        // next launch retries
        store.failWrites = false
        val next = KeyFileVaultCore(files, "key", "ALG", "ID", { generated++; ByteArray(32) }, { _, p, a -> CipherEnvelope("x", "x", byteArrayOf(), p, a) }, { _, e -> e.cipherText }, store)
        next.encrypt(byteArrayOf(1), byteArrayOf())
        assertContentEquals(fileKey, store.key)
        assertEquals(listOf("key"), files.quarantined)
    }

    @Test
    fun lockedStoreIsKeyUnavailableAndNeverRegenerates() {
        store.locked = true
        listOf(true, false).forEach { withFile ->
            if (withFile) files.data["key"] = fileKey
            assertEquals(VaultFailure.KEY_UNAVAILABLE, assertFailsWith<VaultUnreadableException> { core.encrypt(byteArrayOf(1), byteArrayOf()) }.failure)
            assertEquals(VaultFailure.KEY_UNAVAILABLE, assertFailsWith<VaultUnreadableException> {
                core.decrypt(CipherEnvelope("ALG", "ID", byteArrayOf(), byteArrayOf(1), byteArrayOf()))
            }.failure)
        }
        assertEquals(0, generated)
        assertEquals(null, store.key)
        assertTrue(files.quarantined.isEmpty())
    }

    @Test
    fun unreadableLegacyFileWithEmptyStoreIsKeyUnavailable() {
        files.data["key"] = null

        assertEquals(VaultFailure.KEY_UNAVAILABLE, assertFailsWith<VaultUnreadableException> { core.encrypt(byteArrayOf(1), byteArrayOf()) }.failure)
        assertEquals(0, generated)
        assertEquals(null, store.key)
    }

    @Test
    fun freshInstallCreatesKeyInStoreOnlyAndCachesIt() {
        core.encrypt(byteArrayOf(1), byteArrayOf())
        core.encrypt(byteArrayOf(1), byteArrayOf())

        assertEquals(1, generated)
        assertContentEquals(ByteArray(32) { 9 }, store.key)
        assertTrue(files.data.isEmpty())
        assertEquals(2, store.reads) // one lookup + one migration/creation read-back, then cached
    }

    @Test
    fun freshInstallStoreWriteFailureFallsBackToFile() {
        store.failWrites = true

        core.encrypt(byteArrayOf(1), byteArrayOf())

        assertContentEquals(ByteArray(32) { 9 }, files.data["key"])
    }

    @Test
    fun decryptNeverCreatesKey() {
        assertEquals(VaultFailure.KEY_UNAVAILABLE, assertFailsWith<VaultUnreadableException> {
            core.decrypt(CipherEnvelope("ALG", "ID", byteArrayOf(), byteArrayOf(1), byteArrayOf()))
        }.failure)
        assertEquals(0, generated)
    }

    @Test
    fun discardKeyArchivesThenDeletesAndLockedLeavesItAlone() {
        core.encrypt(byteArrayOf(1), byteArrayOf())
        var archived: ByteArray? = null

        store.locked = true
        assertFalse(core.discardKey { archived = it })
        assertEquals(0, store.deletes)

        store.locked = false
        assertTrue(core.discardKey { archived = it })
        assertContentEquals(ByteArray(32) { 9 }, archived)
        assertEquals(1, store.deletes)
        assertEquals(null, store.key)
    }

    @Test
    fun staleLegacyFileEqualToStoreKeyIsRetired() {
        store.key = fileKey
        files.data["key"] = fileKey

        core.encrypt(byteArrayOf(1), byteArrayOf())

        assertEquals(listOf("key"), files.quarantined)
        assertEquals(0, generated)
    }
}
