package app.vezuna

import app.vezuna.data.PersistentVezunaRepository
import app.vezuna.domain.Profile
import app.vezuna.security.CipherEnvelope
import app.vezuna.security.GenerationalFileStore
import app.vezuna.security.SecureVault
import app.vezuna.security.VaultFailure
import app.vezuna.security.VaultUnreadableException
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

/** File-backed vault with toy crypto so recovery paths run on the JVM without an Android Keystore. */
private class FileVault(private val directory: File, var keyInvalidated: Boolean = false, var quarantineFailures: Int = 0) : SecureVault {
    private val files = GenerationalFileStore(directory)

    override suspend fun encrypt(plainText: ByteArray, associatedData: ByteArray) =
        CipherEnvelope("test-xor", "test-key", byteArrayOf(7), plainText.xor(), associatedData)

    override suspend fun decrypt(envelope: CipherEnvelope): ByteArray {
        if (keyInvalidated) throw VaultUnreadableException(VaultFailure.KEY_INVALIDATED)
        return envelope.cipherText.xor()
    }

    override suspend fun write(key: String, ciphertext: ByteArray) = files.write(key, ciphertext)
    override suspend fun read(key: String) = files.read(key)
    override suspend fun readPrevious(key: String) = files.readPrevious(key)
    override suspend fun delete(key: String) = files.delete(key)
    override suspend fun quarantineCurrent(key: String): Boolean {
        if (quarantineFailures > 0) { quarantineFailures--; error("disk busy") }
        return files.quarantineCurrent(key, "cur")
    }
    override suspend fun quarantineAll(): Boolean { files.quarantineAll("1"); return true }
    override suspend fun discardUnusableKey() { keyInvalidated = false }

    private fun ByteArray.xor() = ByteArray(size) { (this[it].toInt() xor 0x5a).toByte() }
}

class VaultRecoveryTest {
    private val directory: File = Files.createTempDirectory("vault-recovery").toFile()
    private val vault = FileVault(directory)
    private val key = "repository-v1"

    private suspend fun seed(vararg names: String) {
        val repository = PersistentVezunaRepository(vault)
        repository.load()
        names.forEach { repository.createProfile(Profile("id-$it", it, createdAt = Instant.fromEpochMilliseconds(1))) }
    }

    @Test
    fun corruptCurrentSnapshotFallsBackToPreviousGeneration() = runTest {
        seed("Ana", "Bia")
        File(directory, key).writeBytes("{truncated".encodeToByteArray())

        val restored = PersistentVezunaRepository(vault)
        restored.load()

        assertEquals(listOf("Ana"), restored.profiles.value.map { it.displayName })
    }

    @Test
    fun fallbackQuarantinesCorruptCurrentSoLaterWritesCannotOverwriteGoodPrevious() = runTest {
        seed("Ana", "Bia")
        val broken = "{truncated".encodeToByteArray()
        File(directory, key).writeBytes(broken)

        val restored = PersistentVezunaRepository(vault)
        restored.load()

        assertTrue(restored.openedFromPreviousGeneration.value)
        val kept = directory.listFiles { file -> file.isDirectory }.orEmpty().single()
        assertContentEquals(broken, File(kept, key).readBytes())
        restored.createProfile(Profile("id-Cy", "Cy", createdAt = Instant.fromEpochMilliseconds(3)))
        // The good previous generation is still the pre-corruption snapshot, not the corrupt bytes.
        val previous = PersistentVezunaRepository(FileVault(directory)).also { it.load() }
        assertEquals(listOf("Ana", "Cy"), previous.profiles.value.map { it.displayName })
        restored.acknowledgePreviousGeneration()
        assertEquals(false, restored.openedFromPreviousGeneration.value)
    }

    @Test
    fun failedQuarantineStillOpensAndIsRetriedBeforeTheFirstWrite() = runTest {
        seed("Ana", "Bia")
        File(directory, key).writeBytes("{truncated".encodeToByteArray())
        val flaky = FileVault(directory, quarantineFailures = 1)

        val restored = PersistentVezunaRepository(flaky)
        restored.load()
        assertEquals(listOf("Ana"), restored.profiles.value.map { it.displayName })
        restored.createProfile(Profile("id-Cy", "Cy", createdAt = Instant.fromEpochMilliseconds(3)))

        assertEquals(listOf("Ana", "Cy"), PersistentVezunaRepository(vault).also { it.load() }.profiles.value.map { it.displayName })
        assertEquals("{truncated", directory.listFiles { f -> f.isDirectory }.orEmpty().single().resolve(key).readText())
    }

    @Test
    fun writeIsRefusedRatherThanRotatingCorruptDataOverPrevious() = runTest {
        seed("Ana", "Bia")
        File(directory, key).writeBytes("{truncated".encodeToByteArray())
        val stuck = FileVault(directory, quarantineFailures = 2)
        val restored = PersistentVezunaRepository(stuck)
        restored.load()

        assertFailsWith<IllegalStateException> {
            restored.createProfile(Profile("id-Cy", "Cy", createdAt = Instant.fromEpochMilliseconds(3)))
        }

        assertEquals("{truncated", File(directory, key).readText())
        assertTrue(File(directory, "$key.prev").length() > 0)
    }

    @Test
    fun unreadableCurrentAndPreviousReportCorruptInsteadOfCrashing() = runTest {
        seed("Ana", "Bia")
        File(directory, key).writeBytes("garbage".encodeToByteArray())
        File(directory, "$key.prev").writeBytes("garbage".encodeToByteArray())

        val failure = assertFailsWith<VaultUnreadableException> { PersistentVezunaRepository(vault).load() }

        assertEquals(VaultFailure.CORRUPT, failure.failure)
    }

    @Test
    fun invalidatedKeyIsReportedAndNeverMasqueradesAsCorruption() = runTest {
        seed("Ana", "Bia")
        vault.keyInvalidated = true

        val failure = assertFailsWith<VaultUnreadableException> { PersistentVezunaRepository(vault).load() }

        assertEquals(VaultFailure.KEY_INVALIDATED, failure.failure)
    }

    @Test
    fun resetKeepsUnreadableFilesAndStartsEmpty() = runTest {
        seed("Ana")
        val broken = "garbage".encodeToByteArray()
        File(directory, key).writeBytes(broken)
        File(directory, "$key.prev").delete()
        val repository = PersistentVezunaRepository(vault)
        assertFailsWith<VaultUnreadableException> { repository.load() }

        repository.resetAfterFailure()

        assertTrue(repository.profiles.value.isEmpty())
        val kept = directory.listFiles { file -> file.isDirectory }.orEmpty().single()
        assertContentEquals(broken, File(kept, key).readBytes())
        repository.createProfile(Profile("fresh", "Fresh", createdAt = Instant.fromEpochMilliseconds(2)))
        assertEquals(listOf("Fresh"), PersistentVezunaRepository(vault).also { it.load() }.profiles.value.map { it.displayName })
    }
}
