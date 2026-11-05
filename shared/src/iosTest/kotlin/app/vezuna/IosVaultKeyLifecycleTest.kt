package app.vezuna

import app.vezuna.security.*
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class IosVaultKeyLifecycleTest {
    private class MemoryKeyStore : VaultKeyStore {
        var key: ByteArray? = null
        var writes = 0
        val archived = mutableListOf<ByteArray>()
        override fun read() = key?.let { KeyStoreRead(KeyStoreStatus.FOUND, it) } ?: KeyStoreRead(KeyStoreStatus.MISSING)
        override fun write(key: ByteArray): Boolean {
            writes++
            if (this.key != null) return false
            this.key = key.copyOf()
            return true
        }
        override fun archive(key: ByteArray): Boolean { archived.add(key.copyOf()); return true }
        override fun delete(): Boolean { key = null; return true }
    }

    @Test
    fun createsAndReusesKeyAcrossVaultInstances() = runTest {
        val directory = NSTemporaryDirectory() + NSUUID().UUIDString
        val store = MemoryKeyStore()
        try {
            val first = IosSecureVault(directory, store)
            val envelope = first.encrypt("private".encodeToByteArray(), byteArrayOf())
            assertContentEquals("private".encodeToByteArray(), IosSecureVault(directory, store).decrypt(envelope))
            assertEquals(1, store.writes)
        } finally { NSFileManager.defaultManager.removeItemAtPath(directory, null) }
    }

    @Test
    fun missingKeyWithExistingVaultDoesNotMintReplacement() = runTest {
        val directory = NSTemporaryDirectory() + NSUUID().UUIDString
        val store = MemoryKeyStore()
        try {
            val first = IosSecureVault(directory, store)
            val envelope = first.encrypt("private".encodeToByteArray(), byteArrayOf())
            first.write("record", CipherEnvelopeCodec.encode(envelope))
            store.key = null
            val reopened = IosSecureVault(directory, store)
            val error = assertFailsWith<VaultUnreadableException> { reopened.encrypt("new".encodeToByteArray(), byteArrayOf()) }
            assertEquals(VaultFailure.KEY_UNAVAILABLE, error.failure)
            assertEquals(1, store.writes)
            assertContentEquals(CipherEnvelopeCodec.encode(envelope), reopened.read("record"))
        } finally { NSFileManager.defaultManager.removeItemAtPath(directory, null) }
    }

    @Test
    fun quarantineArchivesKeyAndMovesExistingVaultData() = runTest {
        val directory = NSTemporaryDirectory() + NSUUID().UUIDString
        val store = MemoryKeyStore()
        try {
            val vault = IosSecureVault(directory, store)
            val envelope = vault.encrypt("private".encodeToByteArray(), byteArrayOf())
            vault.write("record", CipherEnvelopeCodec.encode(envelope))
            val originalKey = store.key!!.copyOf()
            assertTrue(vault.quarantineAll())
            assertTrue(store.archived.any { it.contentEquals(originalKey) })
            assertEquals(null, store.key)
            assertEquals(null, vault.read("record"))
            val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, null) as List<*>
            val quarantine = names.filterIsInstance<String>().single { it.startsWith("unreadable-") }
            val quarantinedVault = IosSecureVault("$directory/$quarantine", store)
            assertContentEquals(CipherEnvelopeCodec.encode(envelope), quarantinedVault.read("record"))
        } finally { NSFileManager.defaultManager.removeItemAtPath(directory, null) }
    }
}
