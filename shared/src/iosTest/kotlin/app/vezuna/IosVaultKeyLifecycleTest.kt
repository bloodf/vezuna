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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class IosVaultKeyLifecycleTest {
    private class MemoryKeyStore : VaultKeyStore {
        var key: ByteArray? = null
        var writes = 0
        var failArchive = false
        var failDelete = false
        val archived = mutableListOf<ByteArray>()
        override fun read() = key?.let { KeyStoreRead(KeyStoreStatus.FOUND, it) } ?: KeyStoreRead(KeyStoreStatus.MISSING)
        override fun write(key: ByteArray): Boolean {
            writes++
            if (this.key != null) return false
            this.key = key.copyOf()
            return true
        }
        override fun archive(key: ByteArray): Boolean { if (failArchive) return false; archived.add(key.copyOf()); return true }
        override fun delete(): Boolean { if (failDelete) return false; key = null; return true }
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

    @Test
    fun interruptedQuarantineBlocksReopenAndRetryPreservesAllFilesAndKey() = runTest {
        val directory = NSTemporaryDirectory() + NSUUID().UUIDString
        val manager = NSFileManager.defaultManager
        val store = MemoryKeyStore()
        try {
            var moves = 0
            val vault = IosSecureVault(directory, store) { from, to ->
                moves++
                if (moves == 2) false else manager.moveItemAtPath(from, to, null)
            }
            val envelope = vault.encrypt("private".encodeToByteArray(), byteArrayOf())
            val bytes = CipherEnvelopeCodec.encode(envelope)
            vault.write("one", bytes)
            vault.write("two", bytes)
            val key = store.key!!.copyOf()
            assertFalse(vault.quarantineAll())
            assertContentEquals(key, store.key)
            val reopened = IosSecureVault(directory, store)
            assertEquals(VaultFailure.UNAVAILABLE, assertFailsWith<VaultUnreadableException> { reopened.read("one") }.failure)
            assertTrue(reopened.quarantineAll())
            assertEquals(null, store.key)
            assertTrue(store.archived.any { it.contentEquals(key) })
            val names = manager.contentsOfDirectoryAtPath(directory, null) as List<*>
            val quarantine = names.filterIsInstance<String>().single { it.startsWith("unreadable-") }
            val saved = IosSecureVault("$directory/$quarantine", store)
            assertContentEquals(bytes, saved.read("one"))
            assertContentEquals(bytes, saved.read("two"))
        } finally { manager.removeItemAtPath(directory, null) }
    }

    @Test
    fun archiveOrDeleteFailureDoesNotExposePartiallyQuarantinedVault() = runTest {
        val directory = NSTemporaryDirectory() + NSUUID().UUIDString
        val manager = NSFileManager.defaultManager
        val store = MemoryKeyStore()
        try {
            val vault = IosSecureVault(directory, store)
            val bytes = CipherEnvelopeCodec.encode(vault.encrypt("private".encodeToByteArray(), byteArrayOf()))
            vault.write("record", bytes)
            val key = store.key!!.copyOf()
            store.failArchive = true
            assertFalse(vault.quarantineAll())
            assertContentEquals(bytes, vault.read("record"))
            store.failArchive = false
            store.failDelete = true
            assertFalse(vault.quarantineAll())
            assertContentEquals(key, store.key)
            val reopened = IosSecureVault(directory, store)
            assertFailsWith<VaultUnreadableException> { reopened.read("record") }
            store.failDelete = false
            assertTrue(reopened.quarantineAll())
            val names = manager.contentsOfDirectoryAtPath(directory, null) as List<*>
            val quarantine = names.filterIsInstance<String>().single { it.startsWith("unreadable-") }
            assertContentEquals(bytes, IosSecureVault("$directory/$quarantine", store).read("record"))
            assertTrue(store.archived.any { it.contentEquals(key) })
        } finally { manager.removeItemAtPath(directory, null) }
    }
}
