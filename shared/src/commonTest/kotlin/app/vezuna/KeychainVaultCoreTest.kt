package app.vezuna

import app.vezuna.security.CipherEnvelope
import app.vezuna.security.KeyFileVaultCore
import app.vezuna.security.KeyStoreRead
import app.vezuna.security.KeyStoreStatus
import app.vezuna.security.VaultFailure
import app.vezuna.security.VaultFileOps
import app.vezuna.security.VaultKeyStore
import app.vezuna.security.VaultUnreadableException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeKeyFiles : VaultFileOps {
    val data = mutableMapOf<String, ByteArray?>()
    val deleted = mutableListOf<String>()
    override fun exists(path: String) = data.containsKey(path)
    override fun read(path: String) = data[path]
    override fun write(path: String, bytes: ByteArray) { data[path] = bytes }
    override fun delete(path: String): Boolean { deleted += path; data.remove(path); return true }
    override fun dataFiles() = data.keys.filter { it != "key" }
}

private class FakeKeyStore : VaultKeyStore {
    var key: ByteArray? = null
    var locked = false
    var failWrites = false
    var raceKey: ByteArray? = null // another creator wins the add
    val archived = mutableListOf<ByteArray>()
    var deletes = 0
    override fun read(): KeyStoreRead = when {
        locked -> KeyStoreRead(KeyStoreStatus.UNAVAILABLE)
        key == null -> KeyStoreRead(KeyStoreStatus.MISSING)
        else -> KeyStoreRead(KeyStoreStatus.FOUND, key)
    }
    override fun write(key: ByteArray): Boolean {
        raceKey?.let { this.key = it; raceKey = null }
        if (failWrites || this.key != null) return false
        this.key = key
        return true
    }
    override fun archive(key: ByteArray): Boolean { archived += key; return true }
    override fun delete(): Boolean { deletes++; key = null; return true }
}

class KeychainVaultCoreTest {
    private val files = FakeKeyFiles()
    private val store = FakeKeyStore()
    private var generated = 0
    private val fileKey = ByteArray(32) { 7 }
    private val newKey = ByteArray(32) { 9 }
    private val otherKey = ByteArray(32) { 3 }
    private val json = Json

    private fun core(s: FakeKeyStore = store) = KeyFileVaultCore(
        files = files, keyPath = "key", algorithm = "ALG", keyId = "ID",
        randomKey = { generated++; newKey },
        seal = { key, plain, aad -> CipherEnvelope("x", "x", byteArrayOf(1), byteArrayOf(key[0]) + plain, aad) },
        open = { key, e ->
            if (e.cipherText[0] != key[0]) throw RuntimeException("auth failed")
            e.cipherText.copyOfRange(1, e.cipherText.size)
        },
        keyStore = s,
    )

    private val core = core()

    private fun envelopeFile(key: ByteArray) =
        json.encodeToString(CipherEnvelope.serializer(), CipherEnvelope("ALG", "ID", byteArrayOf(1), byteArrayOf(key[0], 5), byteArrayOf())).encodeToByteArray()

    private fun encrypt(c: KeyFileVaultCore = core) = c.encrypt(byteArrayOf(1), byteArrayOf())
    private fun failure(action: () -> Unit) = assertFailsWith<VaultUnreadableException> { action() }.failure

    @Test
    fun migrationDeletesFileOnlyAfterDataDecryptsWithStoredKey() {
        files.data["key"] = fileKey
        files.data["data"] = envelopeFile(fileKey)

        encrypt()

        assertContentEquals(fileKey, store.key)
        assertEquals(listOf("key"), files.deleted)
        assertEquals(0, generated)
    }

    @Test
    fun migrationKeepsFileWhenNothingProvesTheKey() {
        files.data["key"] = fileKey

        encrypt()

        assertContentEquals(fileKey, store.key)
        assertTrue(files.deleted.isEmpty())
        assertTrue(files.exists("key"))
    }

    @Test
    fun failedStoreWriteKeepsFileAndRetriesNextLaunch() {
        files.data["key"] = fileKey
        files.data["data"] = envelopeFile(fileKey)
        store.failWrites = true

        encrypt()

        assertTrue(files.deleted.isEmpty())
        assertEquals(null, store.key)
        store.failWrites = false
        encrypt(core())
        assertContentEquals(fileKey, store.key)
        assertEquals(listOf("key"), files.deleted)
    }

    @Test
    fun lockedStoreIsKeyUnavailableAndNeverRegenerates() {
        store.locked = true
        repeat(2) {
            assertEquals(VaultFailure.KEY_UNAVAILABLE, failure { encrypt() })
            assertEquals(VaultFailure.KEY_UNAVAILABLE, failure { core.decrypt(CipherEnvelope("ALG", "ID", byteArrayOf(), byteArrayOf(1), byteArrayOf())) })
            files.data["key"] = fileKey
        }
        assertEquals(0, generated)
        assertNull(store.key)
        assertTrue(files.deleted.isEmpty())
    }

    @Test
    fun unreadableLegacyFileWithEmptyStoreIsKeyUnavailable() {
        files.data["key"] = null

        assertEquals(VaultFailure.KEY_UNAVAILABLE, failure { encrypt() })
        assertEquals(0, generated)
        assertNull(store.key)
    }

    @Test
    fun wrongSizeStoredKeyIsKeyUnavailableWithoutNewKey() {
        store.key = ByteArray(5)

        assertEquals(VaultFailure.KEY_UNAVAILABLE, failure { encrypt() })
        assertEquals(0, generated)
        assertEquals(5, store.key?.size)
    }

    @Test
    fun freshInstallCreatesKeyInStoreOnly() {
        encrypt()
        encrypt()

        assertEquals(1, generated)
        assertContentEquals(newKey, store.key)
        assertTrue(files.data.none { it.key == "key" })
    }

    @Test
    fun existingDataWithoutAnyKeyNeverCreatesOne() {
        files.data["data"] = envelopeFile(fileKey)

        assertEquals(VaultFailure.KEY_UNAVAILABLE, failure { encrypt() })
        assertEquals(0, generated)
        assertNull(store.key)
    }

    @Test
    fun concurrentFirstUseAdoptsTheKeyAnotherCreatorStored() {
        store.raceKey = otherKey

        val envelope = encrypt()

        assertContentEquals(otherKey, store.key)
        assertEquals(otherKey[0], envelope.cipherText[0])
        assertTrue(files.data.none { it.key == "key" })
    }

    @Test
    fun twoCoresOnOneStoreConvergeOnOneKey() {
        val a = encrypt(core())
        val b = encrypt(core())

        assertEquals(1, generated)
        assertEquals(a.cipherText[0], b.cipherText[0])
    }

    @Test
    fun storeWriteFailureOnFreshInstallFallsBackToFileWhenStoreIsEmpty() {
        store.failWrites = true

        encrypt()

        assertContentEquals(newKey, files.data["key"])
    }

    @Test
    fun mismatchedKeysKeepWhicheverDecryptsData() {
        store.key = otherKey
        files.data["key"] = fileKey
        files.data["data"] = envelopeFile(fileKey)

        val envelope = encrypt()

        assertEquals(fileKey[0], envelope.cipherText[0])
        assertTrue(files.exists("key"))
        assertContentEquals(otherKey, store.key)
        assertTrue(files.deleted.isEmpty())
    }

    @Test
    fun mismatchedKeysPreferStoreWhenItDecrypts() {
        store.key = otherKey
        files.data["key"] = fileKey
        files.data["data"] = envelopeFile(otherKey)

        assertEquals(otherKey[0], encrypt().cipherText[0])
        assertTrue(files.exists("key"))
    }

    @Test
    fun staleLegacyFileEqualToStoredKeyIsDeletedOnlyWhenProven() {
        store.key = fileKey
        files.data["key"] = fileKey
        encrypt()
        assertTrue(files.deleted.isEmpty())

        files.data["data"] = envelopeFile(fileKey)
        encrypt(core())
        assertEquals(listOf("key"), files.deleted)
    }

    @Test
    fun resetArchivesBeforeDeleteAndLockedChangesNothing() {
        encrypt()

        store.locked = true
        assertFalse(core.archiveKey())
        assertTrue(store.archived.isEmpty())
        assertEquals(0, store.deletes)

        store.locked = false
        assertTrue(core.archiveKey())
        assertContentEquals(newKey, store.archived.single())
        assertEquals(0, store.deletes)
        assertTrue(core.deleteKey())
        assertNull(store.key)
    }
}
