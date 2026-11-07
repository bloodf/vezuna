package app.vezuna

import app.vezuna.data.*
import app.vezuna.domain.*
import app.vezuna.health.*
import app.vezuna.security.*
import java.io.File
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.*
import kotlin.time.Instant

/** Real filesystem generations and AES-GCM, with synthetic in-memory key material. Not Android Keystore/device proof. */
class WeightEnvelopeRecoveryTest {
    private class FileVault(val directory: File) : SecureVault {
        val files = GenerationalFileStore(directory)
        private val secret = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        override val supportsAcknowledgedWrites = true
        override suspend fun encrypt(plainText: ByteArray, associatedData: ByteArray): CipherEnvelope {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret)
            cipher.updateAAD(associatedData)
            return CipherEnvelope("test-AES-GCM", "synthetic", cipher.iv, cipher.doFinal(plainText), associatedData)
        }
        override suspend fun decrypt(envelope: CipherEnvelope): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, envelope.iv))
            cipher.updateAAD(envelope.associatedData)
            return cipher.doFinal(envelope.cipherText)
        }
        override suspend fun write(key: String, ciphertext: ByteArray) = files.write(key, ciphertext)
        override suspend fun writeAcknowledged(key: String, ciphertext: ByteArray) = files.writeAcknowledged(key, ciphertext)
        override suspend fun acknowledgeCurrent(key: String) = files.acknowledgeCurrent(key)
        override suspend fun read(key: String) = files.read(key)
        override suspend fun readGeneration(key: String) = files.readGeneration(key)
        override suspend fun readPrevious(key: String) = files.readPrevious(key)
        override suspend fun listKeys() = files.names()
        override suspend fun delete(key: String) = files.delete(key)
        override suspend fun quarantineCurrent(key: String) = files.quarantineCurrent(key, "test-evidence")
    }

    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val key = "repository-vitals-p-v1"
    private val source = WeightSource(WeightProvider.HEALTH_CONNECT, "synthetic-live", "weight-only")
    private val claim = SourceClaim(source, SourceContinuityEvidence(source, "synthetic live provider"))
    private val w = WeightReading("W", now, 70.0)
    private suspend fun open(vault: SecureVault) = VaultBackedRepository(vault).also { it.load() }
    private suspend fun seed(vault: FileVault): Pair<VaultBackedRepository, VitalsReadState> {
        val repository = open(vault)
        repository.createProfile(Profile("p", "Synthetic", createdAt = now))
        val result = assertIs<VitalsWriteOutcome.Committed>(repository.commitWeightPage(repository.readVitalsState(repository.captureVitalsSync(TimeZone.UTC)),
            WeightPage(claim, listOf(WeightMutation.Upsert(w)), WeightContinuation.Steady(source, "T0"))))
        return repository to assertNotNull(result.next)
    }

    @Test fun actualEncryptedGenerationRecoveryNeverCombinesOldWeightWithNewCursor() = runTest {
        val directory = Files.createTempDirectory("vezuna-owned-weight-recovery-").toFile()
        try {
            val vault = FileVault(directory)
            val (repository, state) = seed(vault)
            val previous = File(directory, key).readBytes()
            repository.commitWeightPage(state, WeightPage(claim, listOf(WeightMutation.Upsert(w.copy(kg = 72.0))), WeightContinuation.Steady(source, "T1")))
            File(directory, key).writeText("corrupt-current")
            val recovered = open(vault)
            assertTrue(recovered.openedFromPreviousGeneration.value)
            assertEquals(listOf(w), recovered.peekProfile("p").vitals.weights)
            val saved = recovered.readVitalsState(recovered.captureVitalsSync(TimeZone.UTC))
            assertEquals(WeightContinuation.Steady(source, "T0"), saved.local.continuation)
            assertContentEquals(previous, File(directory, "$key.prev").readBytes())
            assertEquals("corrupt-current", File(directory, "unreadable-test-evidence/$key").readText())
            assertIs<VitalsWriteOutcome.Committed>(recovered.commitWeightPage(saved, WeightPage(claim, emptyList(), WeightContinuation.Steady(source, "T2"))))
            assertContentEquals(previous, File(directory, "$key.prev").readBytes())
        } finally { directory.deleteRecursively() }
    }

    @Test fun futureCurrentOrPreviousRefusesLoadExportReplacementAndSweepWithoutAnyByteChange() = runTest {
        for (previous in listOf(false, true)) {
            val directory = Files.createTempDirectory("vezuna-owned-weight-future-").toFile()
            try {
                val vault = FileVault(directory)
                val (repository, _) = seed(vault)
                val future = CipherEnvelopeCodec.encode(vault.encrypt("""{"vitalsFormat":88,"future":"retain"}""".encodeToByteArray(), "Vezuna vitals v1:p".encodeToByteArray()))
                File(directory, if (previous) "$key.prev" else key).writeBytes(future)
                val before = directory.listFiles()!!.associate { it.name to it.readBytes().toList() }
                assertEquals(VaultFailure.SCHEMA_NEWER, assertFailsWith<VaultUnreadableException> { open(vault) }.failure)
                assertFailsWith<StorageSchemaTooNewException> { repository.peekProfile("p") }
                assertFailsWith<StorageSchemaTooNewException> { repository.replaceVitals("p", ProfileVitals()) }
                assertFailsWith<StorageSchemaTooNewException> { VitalsStore(vault, Json { encodeDefaults = true }).sweep(emptySet()) }
                assertEquals(before, directory.listFiles()!!.associate { it.name to it.readBytes().toList() })
            } finally { directory.deleteRecursively() }
        }
    }
}
