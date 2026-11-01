package app.vezuna

import app.vezuna.security.CipherEnvelope
import app.vezuna.security.KeyFileVaultCore
import app.vezuna.security.VaultFailure
import app.vezuna.security.VaultFileOps
import app.vezuna.security.VaultUnreadableException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeFiles : VaultFileOps {
    val data = mutableMapOf<String, ByteArray?>()
    var writes = 0
    override fun exists(path: String) = data.containsKey(path)
    override fun read(path: String) = data[path]
    override fun write(path: String, bytes: ByteArray) { writes++; data[path] = bytes }
}

class KeyFileVaultCoreTest {
    private val files = FakeFiles()
    private var generated = 0
    private val core = KeyFileVaultCore(
        files = files,
        keyPath = "key",
        algorithm = "ALG",
        keyId = "ID",
        randomKey = { generated++; ByteArray(32) { 9 } },
        seal = { key, plain, aad -> CipherEnvelope("x", "x", byteArrayOf(1), plain + key.take(1), aad) },
        open = { _, envelope ->
            check(envelope.cipherText.isNotEmpty()) { "not installed" }
            if (envelope.cipherText.first() == 0.toByte()) throw RuntimeException("auth failed")
            envelope.cipherText
        },
    )

    @Test
    fun encryptCreatesKeyOnlyWhenAbsent() {
        val envelope = core.encrypt(byteArrayOf(5), byteArrayOf())

        assertEquals(1, generated)
        assertEquals("ALG", envelope.algorithm)
        assertEquals("ID", envelope.keyId)
        core.encrypt(byteArrayOf(5), byteArrayOf())
        assertEquals(1, generated)
    }

    @Test
    fun unreadableKeyFileThrowsAndIsNeverRegenerated() {
        files.data["key"] = null

        listOf({ core.encrypt(byteArrayOf(1), byteArrayOf()) }, { core.decrypt(CipherEnvelope("ALG", "ID", byteArrayOf(), byteArrayOf(1), byteArrayOf())) })
            .forEach { action ->
                assertEquals(VaultFailure.KEY_UNAVAILABLE, assertFailsWith<VaultUnreadableException> { action() }.failure)
            }
        assertEquals(0, generated)
        assertEquals(0, files.writes)
    }

    @Test
    fun wrongSizeKeyIsRejectedWithoutOverwriting() {
        files.data["key"] = ByteArray(5)

        assertEquals(VaultFailure.KEY_UNAVAILABLE, assertFailsWith<VaultUnreadableException> { core.encrypt(byteArrayOf(1), byteArrayOf()) }.failure)
        assertEquals(0, files.writes)
        assertEquals(5, files.data["key"]?.size)
    }

    @Test
    fun decryptNeverCreatesAKey() {
        val envelope = CipherEnvelope("ALG", "ID", byteArrayOf(), byteArrayOf(1), byteArrayOf())

        assertEquals(VaultFailure.KEY_UNAVAILABLE, assertFailsWith<VaultUnreadableException> { core.decrypt(envelope) }.failure)
        assertEquals(0, generated)
        assertTrue(files.data.isEmpty())
    }

    @Test
    fun corruptEnvelopeIsRecoverableNotFatal() {
        files.data["key"] = ByteArray(32)

        val tampered = CipherEnvelope("ALG", "ID", byteArrayOf(), byteArrayOf(0), byteArrayOf())
        assertEquals(VaultFailure.CORRUPT, assertFailsWith<VaultUnreadableException> { core.decrypt(tampered) }.failure)
        val foreign = CipherEnvelope("OTHER", "ID", byteArrayOf(), byteArrayOf(1), byteArrayOf())
        assertEquals(VaultFailure.CORRUPT, assertFailsWith<VaultUnreadableException> { core.decrypt(foreign) }.failure)
        assertContentEquals(byteArrayOf(1), core.decrypt(CipherEnvelope("ALG", "ID", byteArrayOf(), byteArrayOf(1), byteArrayOf())))
    }

    @Test
    fun lockedDataFileThrowsInsteadOfReadingAsEmpty() {
        files.data["locked"] = null
        files.data["ok"] = byteArrayOf(3)

        assertEquals(VaultFailure.UNAVAILABLE, assertFailsWith<VaultUnreadableException> { core.readData("locked") }.failure)
        assertNull(core.readData("missing"))
        assertContentEquals(byteArrayOf(3), core.readData("ok"))
    }
}
