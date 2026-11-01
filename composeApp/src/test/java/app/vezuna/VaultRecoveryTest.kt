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
private class FileVault(private val directory: File, var keyInvalidated: Boolean = false) : SecureVault {
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
