package app.vezuna

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.vezuna.security.AndroidSecureVault
import app.vezuna.security.CipherEnvelope
import app.vezuna.security.VaultFailure
import app.vezuna.security.VaultUnreadableException
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AndroidSecureVault against the real Android Keystore. Each test owns a UUID alias and directory.
 * Explicit deletion covers missing-key recovery, not hardware invalidation. The production alias is never used.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSecureVaultInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testId = UUID.randomUUID().toString()
    private val keyAlias = "app.vezuna.vault.test.$testId"
    private lateinit var dir: File
    private lateinit var keyStore: KeyStore
    private lateinit var vault: AndroidSecureVault
    private var concurrentWorkersTerminated = true
    private val plain = "synthetic hemoglobin 13.5 g/dL".encodeToByteArray()
    private val aad = "profile-a".encodeToByteArray()

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "vault-test-$testId")
        keyStore = loadedKeyStore()
        assertFalse(keyStore.containsAlias(keyAlias))
        vault = AndroidSecureVault(dir, keyStore, keyAlias)
    }

    @After
    fun tearDown() {
        assertTrue("Workers may still own $keyAlias; retaining the alias and directory", concurrentWorkersTerminated)
        try {
            if (::keyStore.isInitialized) {
                keyStore.deleteEntry(keyAlias)
                assertFalse(keyStore.containsAlias(keyAlias))
            }
        } finally {
            if (::dir.isInitialized && dir.exists()) assertTrue(dir.deleteRecursively())
        }
    }

    private fun loadedKeyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun reopenVault() = AndroidSecureVault(dir, loadedKeyStore(), keyAlias)

    private fun fileFor(key: String) = File(dir, Base64.encodeToString(key.encodeToByteArray(), Base64.URL_SAFE or Base64.NO_WRAP))

    private fun failureOf(block: suspend () -> Unit): VaultFailure? = try {
        runBlocking { block() }
        null
    } catch (error: VaultUnreadableException) {
        error.failure
    }

    @Test
    fun encryptThenDecryptReturnsTheOriginalBytes() = runBlocking {
        val envelope = vault.encrypt(plain, aad)
        assertFalse(envelope.cipherText.contentEquals(plain))
        assertArrayEquals(plain, vault.decrypt(envelope, aad))
    }

    @Test
    fun everyEncryptionUsesAFreshNonce() = runBlocking {
        val a = vault.encrypt(plain, aad)
        val b = vault.encrypt(plain, aad)
        assertFalse(a.iv.contentEquals(b.iv))
    }

    @Test
    fun flippedCiphertextByteIsRejectedAsCorrupt() {
        val envelope = runBlocking { vault.encrypt(plain, aad) }
        val tampered = envelope.cipherText.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertEquals(VaultFailure.CORRUPT, failureOf { vault.decrypt(envelope.copy(cipherText = tampered)) })
    }

    @Test
    fun flippedAuthenticationTagByteIsRejectedAsCorrupt() {
        val envelope = runBlocking { vault.encrypt(plain, aad) }
        val tampered = envelope.cipherText.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertEquals(VaultFailure.CORRUPT, failureOf { vault.decrypt(envelope.copy(cipherText = tampered)) })
    }

    @Test
    fun truncatedCiphertextIsRejectedAsCorrupt() {
        val envelope = runBlocking { vault.encrypt(plain, aad) }
        assertEquals(VaultFailure.CORRUPT, failureOf { vault.decrypt(envelope.copy(cipherText = envelope.cipherText.copyOf(8))) })
    }

    @Test
    fun envelopeWithADifferentAssociatedDataIsRejectedByTheCipher() {
        val envelope = runBlocking { vault.encrypt(plain, aad) }
        assertEquals(VaultFailure.CORRUPT, failureOf { vault.decrypt(envelope.copy(associatedData = "profile-b".encodeToByteArray())) })
    }

    @Test
    fun expectedAssociatedDataThatDiffersFromTheStoredOneIsRejected() {
        val envelope = runBlocking { vault.encrypt(plain, aad) }
        assertEquals(VaultFailure.CORRUPT, failureOf { vault.decrypt(envelope, "profile-b".encodeToByteArray()) })
    }

    @Test
    fun foreignAlgorithmOrKeyIdIsRejectedAsCorrupt() {
        val envelope = runBlocking { vault.encrypt(plain, aad) }
        assertEquals(VaultFailure.CORRUPT, failureOf { vault.decrypt(envelope.copy(algorithm = "AES-128-CBC")) })
        assertEquals(VaultFailure.CORRUPT, failureOf { vault.decrypt(envelope.copy(keyId = "someone-else")) })
    }

    @Test
    fun keySurvivesANewVaultInstance() = runBlocking {
        val envelope = vault.encrypt(plain, aad)
        assertArrayEquals(plain, reopenVault().decrypt(envelope, aad))
    }

    @Test
    fun deletedOwnedAliasIsInvalidatedWithoutRemintUntilEncryptionAndReopens() = runBlocking {
        val original = vault.encrypt(plain, aad)
        assertEquals(keyAlias, original.keyId)
        assertTrue(keyStore.containsAlias(keyAlias))
        assertArrayEquals(plain, vault.decrypt(original, aad))

        // Deliberate deletion of this test's entry, not simulated or hardware invalidation.
        keyStore.deleteEntry(keyAlias)
        assertFalse(keyStore.containsAlias(keyAlias))
        repeat(2) {
            assertEquals(VaultFailure.KEY_INVALIDATED, failureOf { vault.decrypt(original, aad) })
            assertFalse(keyStore.containsAlias(keyAlias))
        }
        vault.discardUnusableKey()
        assertFalse(keyStore.containsAlias(keyAlias))

        val fresh = vault.encrypt(plain, aad)
        assertEquals(keyAlias, fresh.keyId)
        assertTrue(keyStore.containsAlias(keyAlias))
        assertArrayEquals(plain, vault.decrypt(fresh, aad))
        assertArrayEquals(plain, reopenVault().decrypt(fresh, aad))
    }

    @Test
    fun concurrentUseOfOneKeyFromManyInstancesAlwaysDecrypts() {
        val threads = 16
        concurrentWorkersTerminated = false
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        try {
            val futures = (0 until threads).map {
                pool.submit<CipherEnvelope> {
                    val own = reopenVault()
                    ready.countDown(); go.await()
                    runBlocking { own.encrypt(plain, aad) }
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            go.countDown()
            val envelopes = futures.map { it.get(30, TimeUnit.SECONDS) }
            // The owned alias starts absent, so first-use calls exercise the process-wide creation lock.
            envelopes.forEach { assertArrayEquals(plain, runBlocking { vault.decrypt(it, aad) }) }
        } finally {
            pool.shutdownNow()
            concurrentWorkersTerminated = pool.awaitTermination(30, TimeUnit.SECONDS)
            assertTrue("Workers did not terminate; retaining $keyAlias and its directory", concurrentWorkersTerminated)
        }
    }

    @Test
    fun writtenBytesAreReadBackAndListed() = runBlocking {
        vault.write("profiles/a", byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), vault.read("profiles/a"))
        assertEquals(listOf("profiles/a"), vault.listKeys())
        assertNull(vault.read("missing"))
    }

    @Test
    fun overwriteKeepsThePreviousGeneration() = runBlocking {
        vault.write("k", byteArrayOf(1))
        vault.write("k", byteArrayOf(2))
        assertArrayEquals(byteArrayOf(2), vault.read("k"))
        assertArrayEquals(byteArrayOf(1), vault.readPrevious("k"))
    }

    @Test
    fun emptyCurrentFileIsReturnedAsIsSoTheCallerSeesCorruptionAndPreviousStillHoldsGoodData() = runBlocking {
        vault.write("k", byteArrayOf(1, 1, 1))
        vault.write("k", byteArrayOf(2, 2, 2))
        fileFor("k").writeBytes(ByteArray(0))
        assertEquals(0, vault.read("k")!!.size)
        assertArrayEquals(byteArrayOf(1, 1, 1), vault.readPrevious("k"))
    }

    @Test
    fun quarantiningACorruptCurrentFileFallsBackToThePreviousGeneration() = runBlocking {
        vault.write("k", byteArrayOf(1, 1, 1))
        vault.write("k", byteArrayOf(2, 2, 2))
        fileFor("k").writeBytes(byteArrayOf(9))
        assertTrue(vault.quarantineCurrent("k"))
        assertArrayEquals(byteArrayOf(1, 1, 1), vault.read("k"))
        assertTrue(dir.listFiles { f -> f.isDirectory && f.name.startsWith("unreadable-") }.orEmpty().isNotEmpty())
    }

    @Test
    fun missingCurrentFileFallsBackToThePreviousGeneration() = runBlocking {
        vault.write("k", byteArrayOf(1))
        vault.write("k", byteArrayOf(2))
        assertTrue(fileFor("k").delete())
        assertArrayEquals(byteArrayOf(1), vault.read("k"))
    }

    @Test
    fun deleteRemovesCurrentAndPreviousGenerations() = runBlocking {
        vault.write("k", byteArrayOf(1))
        vault.write("k", byteArrayOf(2))
        vault.delete("k")
        assertNull(vault.read("k"))
        assertNull(vault.readPrevious("k"))
        assertTrue(vault.listKeys()!!.isEmpty())
    }

    @Test
    fun quarantineAllMovesEveryFileAsideAndLeavesTheVaultEmpty() = runBlocking {
        vault.write("a", byteArrayOf(1))
        vault.write("b", byteArrayOf(2))
        assertTrue(vault.quarantineAll())
        assertNull(vault.read("a"))
        assertNull(vault.read("b"))
        assertNotNull(dir.listFiles { f -> f.isDirectory }?.firstOrNull())
    }

    // Platform-invalidated entries and hardware invalidation still require separate evidence for #106.
    @Test
    fun healthyKeyIsNotDiscarded() = runBlocking {
        val envelope = vault.encrypt(plain, aad)
        vault.discardUnusableKey()
        assertArrayEquals(plain, vault.decrypt(envelope, aad))
    }
}
