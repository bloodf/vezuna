package app.vezuna

import app.vezuna.data.VaultBackedRepository
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileVitals
import app.vezuna.domain.StepsDay
import app.vezuna.domain.VitalsBatch
import app.vezuna.security.CipherEnvelope
import app.vezuna.security.SecureVault
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A device vitals commit writes the vitals file first and pins the health link afterwards
 * ([VaultBackedRepository.ingestDeviceVitalsAndCapture]). The pin is a separate write and can fail on
 * its own, after the data is already durable. The two failures must never be confused:
 *
 * - a storage write failure stores nothing. The cursor stays where it was, the captured context stays
 *   usable and the very same window can be retried.
 * - a link bookkeeping failure stores the data, moves the cursor with it and makes the captured context
 *   stale. The commit reports no successor, so the caller has to capture again before the next sync.
 *
 * In both cases the stored vitals stay with the profile that was linked, and no other profile is ever
 * written to.
 */
class VitalsSyncBookkeepingFailureTest {
    private val zone = TimeZone.UTC
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val later = Instant.parse("2026-11-02T12:00:00Z")
    private val firstStep = StepsDay(LocalDate(2026, 10, 31), 100)
    private val secondStep = StepsDay(LocalDate(2026, 11, 1), 200)
    private val batch = VitalsBatch(LocalDate(2026, 10, 25), steps = listOf(firstStep))

    /**
     * In-memory vault with toy crypto that can fail one write at a time and records every write attempt
     * in order. A write can fail before it stores anything (the storage failure) or after it stored the
     * bytes (the uncertain write, which the repository has to survive elsewhere too).
     */
    private class FaultInjectingVault : SecureVault {
    override val supportsAcknowledgedWrites: Boolean = true
        val files = mutableMapOf<String, ByteArray>()

        /** Every write attempt, in order, including the ones that failed. */
        val writeAttempts = mutableListOf<String>()

        /** Writes that fail before storing anything. */
        val failBeforeStore = mutableSetOf<String>()

        /** Writes that store their bytes and only then fail. */
        val failAfterStore = mutableSetOf<String>()

        override suspend fun encrypt(plainText: ByteArray, associatedData: ByteArray) =
            CipherEnvelope("test-xor", "test-key", ByteArray(0), plainText.xorMask(), associatedData)

        override suspend fun decrypt(envelope: CipherEnvelope): ByteArray {
            if (envelope.algorithm != "test-xor") error("Unexpected envelope")
            return envelope.cipherText.xorMask()
        }

        override suspend fun write(key: String, ciphertext: ByteArray) {
            writeAttempts += key
            if (key in failBeforeStore) error("Vault write failed for $key")
            files[key] = ciphertext.copyOf()
            if (key in failAfterStore) error("Vault write failed for $key")
        }

        override suspend fun writeAcknowledged(key: String, ciphertext: ByteArray): app.vezuna.security.VaultWriteResult {
            if (key in failBeforeStore) {
                writeAttempts += key
                return app.vezuna.security.VaultWriteResult.Rejected(IllegalStateException("before store"))
            }
            return try { write(key, ciphertext); app.vezuna.security.VaultWriteResult.Acknowledged }
            catch (error: Exception) { app.vezuna.security.VaultWriteResult.Indeterminate(error) }
        }

        override suspend fun read(key: String): ByteArray? = files[key]?.copyOf()

        override suspend fun delete(key: String) {
            files.remove(key)
        }

        private fun ByteArray.xorMask(): ByteArray {
            val masked = ByteArray(size)
            for (index in masked.indices) masked[index] = (this[index].toInt() xor 0x5a).toByte()
            return masked
        }
    }

    /** Two profiles; the link is not set yet, so it defaults to the oldest one. */
    private suspend fun open(vault: SecureVault): VaultBackedRepository {
        val repository = VaultBackedRepository(vault)
        repository.load()
        repository.createProfile(Profile("a", "Ana", createdAt = Instant.fromEpochSeconds(1)))
        repository.createProfile(Profile("b", "Bia", createdAt = Instant.fromEpochSeconds(2)))
        return repository
    }

    private suspend fun reopen(vault: SecureVault): VaultBackedRepository =
        VaultBackedRepository(vault).also { it.load() }

    /** The control: nothing fails, so the commit stores the data and pins the link. */
    @Test
    fun durableSaveControlStoresTheVitalsAndPinsTheLinkAfterwards() = runTest {
        val vault = FaultInjectingVault()
        val repository = open(vault)
        val context = repository.captureVitalsSync(zone)
        assertEquals("a", context.profileId)
        assertNull(context.lastSync)

        val commit = repository.ingestDeviceVitalsAndCapture(context, batch, now)

        assertEquals(1, commit.storedCount)
        assertEquals("a", commit.context.profileId)
        assertEquals(context.generation + 1, commit.context.generation)
        assertEquals(now, commit.context.lastSync)
        // The data is durable before the link is pinned: that is the order the failure below depends on.
        assertTrue(vault.writeAttempts.indexOf(VITALS_KEY) < vault.writeAttempts.indexOf(LINK_KEY))
        assertEquals("a", repository.explicitHealthLinkId())
        assertEquals("a", repository.linkedHealthProfileId())
        assertEquals(now, repository.peekProfile("a").vitals.lastSync)
        assertEquals(ProfileVitals(), repository.peekProfile("b").vitals)

        val reopened = reopen(vault)
        assertEquals("a", reopened.explicitHealthLinkId())
        assertEquals(now, reopened.peekProfile("a").vitals.lastSync)
        assertEquals(ProfileVitals(), reopened.peekProfile("b").vitals)
    }

    /** The gap: the pin fails after the vitals file is already durable. */
    @Test
    fun linkBookkeepingFailureAfterTheDurableSaveKeepsTheDataAndReportsNoSuccessor() = runTest {
        val vault = FaultInjectingVault()
        val repository = open(vault)
        val context = repository.captureVitalsSync(zone)
        vault.failBeforeStore += LINK_KEY

        assertFailsWith<IllegalStateException> { repository.ingestDeviceVitalsAndCapture(context, batch, now) }

        // The data write happened before the failing bookkeeping write.
        assertTrue(vault.writeAttempts.contains(VITALS_KEY))
        assertTrue(vault.writeAttempts.indexOf(VITALS_KEY) < vault.writeAttempts.indexOf(LINK_KEY))
        // The durable data is kept and the cursor moved with it: never ahead of the data, never behind it.
        assertEquals(listOf(firstStep), repository.peekProfile("a").vitals.steps)
        assertEquals(now, repository.peekProfile("a").vitals.lastSync)
        // The generation moved with the data, so the captured context can no longer be used.
        assertFailsWith<IllegalStateException> { repository.captureVitalsSync(zone) }
        assertFailsWith<IllegalStateException> { repository.ingestDeviceVitalsAndCapture(context, batch, now) }
        // A committed-but-unpinned outcome blocks new sync until authoritative reload.
        assertFailsWith<IllegalStateException> { repository.captureVitalsSync(zone) }
        // Nothing was routed to another profile.
        assertEquals("a", repository.linkedHealthProfileId())
        assertEquals(ProfileVitals(), repository.peekProfile("b").vitals)
        // The link outcome is truthful: the pin never became durable.
        assertNull(repository.explicitHealthLinkId())

        val reopened = reopen(vault)
        assertEquals(listOf(firstStep), reopened.peekProfile("a").vitals.steps)
        assertEquals(now, reopened.peekProfile("a").vitals.lastSync)
        assertEquals(ProfileVitals(), reopened.peekProfile("b").vitals)
        assertEquals("a", reopened.linkedHealthProfileId())
        assertNull(reopened.explicitHealthLinkId())
    }

    /** The failed bookkeeping is recoverable: the next sync targets the same profile and pins the link. */
    @Test
    fun nextSyncAfterTheBookkeepingFailureWritesToTheSameProfileAndPinsTheLink() = runTest {
        val vault = FaultInjectingVault()
        val repository = open(vault)
        vault.failBeforeStore += LINK_KEY
        assertFailsWith<IllegalStateException> {
            repository.ingestDeviceVitalsAndCapture(repository.captureVitalsSync(zone), batch, now)
        }
        vault.failBeforeStore.clear()

        val reloaded = reopen(vault)
        val next = reloaded.captureVitalsSync(zone)
        assertEquals("a", next.profileId)
        // The cursor already moved with the durable data, so the next sync reads the trailing window only.
        assertEquals(now, next.lastSync)

        // The platform re-reads the same window; the stored day is replaced, never counted twice.
        val commit = reloaded.ingestDeviceVitalsAndCapture(next, VitalsBatch(LocalDate(2026, 10, 25), steps = listOf(firstStep, secondStep)), later)

        assertEquals(2, commit.storedCount)
        assertEquals("a", commit.context.profileId)
        assertEquals(later, commit.context.lastSync)
        assertEquals(listOf(firstStep, secondStep), reloaded.peekProfile("a").vitals.steps)
        assertEquals("a", reloaded.explicitHealthLinkId())
        assertEquals(ProfileVitals(), repository.peekProfile("b").vitals)
    }

    /** The other failure: the data never reached the vault, so nothing moved and the context still works. */
    @Test
    fun storageWriteFailureStoresNothingAndKeepsTheCapturedContextRetryable() = runTest {
        val vault = FaultInjectingVault()
        val repository = open(vault)
        val context = repository.captureVitalsSync(zone)
        vault.failBeforeStore += VITALS_KEY

        assertFailsWith<IllegalStateException> { repository.ingestDeviceVitalsAndCapture(context, batch, now) }

        // Nothing durable: no data, no cursor, no generation move.
        assertEquals(ProfileVitals(), repository.peekProfile("a").vitals)
        assertNull(repository.peekProfile("a").vitals.lastSync)
        assertEquals(context.generation, repository.captureVitalsSync(zone).generation)
        // The link bookkeeping never ran.
        assertTrue(vault.writeAttempts.none { it == LINK_KEY })
        assertNull(repository.explicitHealthLinkId())
        assertEquals(ProfileVitals(), repository.peekProfile("b").vitals)

        vault.failBeforeStore.clear()
        val reopened = reopen(vault)
        assertEquals(ProfileVitals(), reopened.peekProfile("a").vitals)
        assertNull(reopened.peekProfile("a").vitals.lastSync)

        // The very same captured context still commits once the vault is healthy again.
        val commit = repository.ingestDeviceVitalsAndCapture(context, batch, now)
        assertEquals(1, commit.storedCount)
        assertEquals(now, commit.context.lastSync)
        assertEquals(listOf(firstStep), repository.peekProfile("a").vitals.steps)
        assertEquals("a", repository.explicitHealthLinkId())
    }

    /** A vault that stores the bytes and only then fails: the pin may or may not be durable, the target may not move. */
    @Test
    fun linkWriteThatStoresBeforeFailingStillLeavesTheVitalsWithTheLinkedProfile() = runTest {
        val vault = FaultInjectingVault()
        val repository = open(vault)
        val context = repository.captureVitalsSync(zone)
        vault.failAfterStore += LINK_KEY

        assertFailsWith<IllegalStateException> { repository.ingestDeviceVitalsAndCapture(context, batch, now) }

        assertEquals(listOf(firstStep), repository.peekProfile("a").vitals.steps)
        assertEquals(now, repository.peekProfile("a").vitals.lastSync)
        assertEquals("a", repository.linkedHealthProfileId())
        assertEquals(ProfileVitals(), repository.peekProfile("b").vitals)

        val reopened = reopen(vault)
        assertEquals(listOf(firstStep), reopened.peekProfile("a").vitals.steps)
        assertEquals(now, reopened.peekProfile("a").vitals.lastSync)
        assertEquals(ProfileVitals(), reopened.peekProfile("b").vitals)
        assertEquals("a", reopened.linkedHealthProfileId())
    }

    private companion object {
        const val VITALS_KEY = "repository-vitals-a-v1"
        const val LINK_KEY = "repository-health-link-v1"
    }
}
