package app.vezuna

import app.vezuna.security.AndroidSecureVault
import app.vezuna.security.CipherEnvelope
import app.vezuna.security.CipherEnvelopeCodec
import app.vezuna.security.VaultFailure
import app.vezuna.security.VaultUnreadableException
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidSecureVaultMissingAliasTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun emptyStore(): KeyStore =
        KeyStore.getInstance("JCEKS").apply { load(null, null) }

    private fun envelope() = CipherEnvelope(
        "AES-256-GCM",
        "app.vezuna.vault.v1",
        ByteArray(12) { it.toByte() },
        ByteArray(32) { (it + 1).toByte() },
        "synthetic-profile".encodeToByteArray(),
    )

    @Test
    fun missingAliasIsInvalidatedWithoutChangingKeysOrStoredBytes() = runBlocking {
        val directory = temporary.newFolder()
        val current = directory.resolve("synthetic-current")
        val previous = directory.resolve("synthetic-current.prev")
        val bytes = CipherEnvelopeCodec.encode(envelope())
        val previousBytes = bytes.copyOf().also { it[it.lastIndex] = 42 }
        current.writeBytes(bytes)
        previous.writeBytes(previousBytes)
        val store = emptyStore()
        val vault = AndroidSecureVault(directory, store)

        repeat(2) {
            val failure = assertFailsWith<VaultUnreadableException> {
                vault.decrypt(CipherEnvelopeCodec.decode(current.readBytes()), envelope().associatedData)
            }
            assertEquals(VaultFailure.KEY_INVALIDATED, failure.failure)
            assertTrue(store.aliases().toList().isEmpty())
            assertContentEquals(bytes, current.readBytes())
            assertContentEquals(previousBytes, previous.readBytes())
            assertEquals(
                setOf(current.name, previous.name),
                directory.listFiles()!!.map { it.name }.toSet(),
            )
        }
    }

    @Test
    fun discardingAnAbsentKeyDoesNotCreateAnEntry() = runBlocking {
        val store = emptyStore()
        val vault = AndroidSecureVault(temporary.newFolder(), store)
        vault.discardUnusableKey()
        assertTrue(store.aliases().toList().isEmpty())
        assertEquals(VaultFailure.KEY_INVALIDATED, assertFailsWith<VaultUnreadableException> {
            vault.decrypt(envelope())
        }.failure)
    }

    @Test
    fun foreignHeadersAndExpectedAadMismatchRemainCorruptWithNoKey() = runBlocking {
        val store = emptyStore()
        val vault = AndroidSecureVault(temporary.newFolder(), store)
        val original = envelope()
        for (foreign in listOf(
            original.copy(algorithm = "foreign"),
            original.copy(keyId = "foreign"),
        )) {
            assertEquals(VaultFailure.CORRUPT, assertFailsWith<VaultUnreadableException> {
                vault.decrypt(foreign)
            }.failure)
        }
        assertEquals(VaultFailure.CORRUPT, assertFailsWith<VaultUnreadableException> {
            vault.decrypt(original, "another-profile".encodeToByteArray())
        }.failure)
        assertTrue(store.aliases().toList().isEmpty())
    }
}
