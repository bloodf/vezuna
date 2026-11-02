package app.vezuna

import app.vezuna.security.JvmSecureVault
import app.vezuna.security.VaultFailure
import app.vezuna.security.VaultUnreadableException
import app.vezuna.security.createPlatformVault
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class JvmSecureVaultTest {
    private fun dir() = Files.createTempDirectory("vault").toFile().also { it.deleteOnExit() }
    private val aad = "profile:1".encodeToByteArray()

    @Test
    fun envelopeRoundTripsAndUsesFreshIv() = runTest {
        val vault = JvmSecureVault(dir())
        val plain = "synthetic payload".encodeToByteArray()
        val a = vault.encrypt(plain, aad)
        val b = vault.encrypt(plain, aad)
        assertContentEquals(plain, vault.decrypt(a))
        assertFalse(a.iv.contentEquals(b.iv))
        assertFalse(a.cipherText.contentEquals(plain))
    }

    @Test
    fun tamperedCiphertextOrAssociatedDataIsRejected() = runTest {
        val vault = JvmSecureVault(dir())
        val sealed = vault.encrypt("secret".encodeToByteArray(), aad)
        val flipped = sealed.cipherText.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFailsWith<AEADBadTagException> { vault.decrypt(sealed.copy(cipherText = flipped)) }
        assertFailsWith<AEADBadTagException> { vault.decrypt(sealed.copy(associatedData = "profile:2".encodeToByteArray())) }
        assertFailsWith<IllegalArgumentException> { vault.decrypt(sealed.copy(keyId = "other")) }
    }

    @Test
    fun keyPersistsAcrossInstancesButNotAcrossDirectories() = runTest {
        val d = dir()
        val sealed = JvmSecureVault(d).encrypt("x".encodeToByteArray(), aad)
        assertContentEquals("x".encodeToByteArray(), JvmSecureVault(d).decrypt(sealed))
        assertFailsWith<AEADBadTagException> { JvmSecureVault(dir()).decrypt(sealed) }
    }

    @Test
    fun writeReadDeleteAndDefaults() = runTest {
        val vault = createPlatformVault(dir().path)
        assertNull(vault.read("a/b"))
        vault.write("a/b", byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), vault.read("a/b"))
        vault.delete("a/b")
        assertNull(vault.read("a/b"))
        assertNull(vault.readPrevious("a/b"))
        assertFalse(vault.quarantineAll())
        assertFalse(vault.quarantineCurrent("a/b"))
        vault.discardUnusableKey()
    }

    @Test
    fun unreadableExceptionCarriesFailure() {
        val cause = IllegalStateException("boom")
        val e = VaultUnreadableException(VaultFailure.KEY_INVALIDATED, cause)
        assertEquals(VaultFailure.KEY_INVALIDATED, e.failure)
        assertEquals(cause, e.cause)
    }
}

class JvmSecureVaultHardeningTest {
    private fun dir() = Files.createTempDirectory("vault").toFile().also { it.deleteOnExit() }

    @Test
    fun mismatchedExpectedAssociatedDataIsRejectedBeforeAnyKeyIsCreated() = runTest {
        val sealed = JvmSecureVault(dir()).encrypt("x".encodeToByteArray(), "a".encodeToByteArray())
        val untouched = dir()
        val failure = assertFailsWith<VaultUnreadableException> { JvmSecureVault(untouched).decrypt(sealed, "b".encodeToByteArray()) }
        assertEquals(VaultFailure.CORRUPT, failure.failure)
        assertEquals(0, untouched.listFiles().orEmpty().size, "no key file was created for a rejected envelope")
    }

    @Test
    fun concurrentFirstUseCreatesOneKey() {
        val d = dir()
        val sealed = java.util.concurrent.ConcurrentLinkedQueue<app.vezuna.security.CipherEnvelope>()
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = List(16) { n ->
            Thread {
                start.await()
                sealed += kotlinx.coroutines.runBlocking { JvmSecureVault(d).encrypt("v$n".encodeToByteArray(), "aad".encodeToByteArray()) }
            }.also { it.start() }
        }
        start.countDown()
        threads.forEach { it.join() }
        val reader = JvmSecureVault(d)
        assertEquals(16, sealed.size)
        sealed.forEach { kotlinx.coroutines.runBlocking { reader.decrypt(it, "aad".encodeToByteArray()) } }
    }

    @Test
    fun listKeysReturnsStoredKeysOnly() = runTest {
        val vault = JvmSecureVault(dir())
        vault.encrypt(byteArrayOf(1), byteArrayOf())
        vault.write("repository-profile-a-v1", byteArrayOf(1))
        vault.write("other", byteArrayOf(2))
        assertEquals(setOf("repository-profile-a-v1", "other"), vault.listKeys().toSet())
    }
}
