package app.vezuna

import app.vezuna.data.VaultBackedRepository
import app.vezuna.data.VaultSourceFileStore
import app.vezuna.data.restoreMembers
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.SourceDocument
import app.vezuna.security.BackupMember
import app.vezuna.ui.RestoreMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.time.Instant

class HouseholdRestoreCancellationTest {
    private val storedJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val time = Instant.parse("2026-01-01T00:00:00Z")
    private val firstPage = byteArrayOf(11, 12, -13)
    private val secondPage = byteArrayOf(21, -22, 23, 24)
    private val sentinelBytes = byteArrayOf(-31, 32)

    private fun member() = BackupMember(
        ProfileData(
            Profile("restored", "Restored", createdAt = time),
            documents = listOf(
                SourceDocument(
                    "restored", "scan", "scan.jpg", "image/jpeg", "scan-hash", time,
                    vaultKey = "backup-first", pageVaultKeys = listOf("backup-second"),
                ),
            ),
        ),
        mapOf("scan" to firstPage, "scan-page-2" to secondPage),
    )

    private suspend fun seedSentinel(repository: VaultBackedRepository, store: VaultSourceFileStore): ProfileData {
        repository.createProfile(Profile("sentinel", "Sentinel", createdAt = time))
        val key = store.save("sentinel", "sentinel-doc", sentinelBytes)
        repository.addDocument(
            SourceDocument("sentinel", "sentinel-doc", "sentinel.pdf", "application/pdf", "sentinel-hash", time, vaultKey = key),
        )
        return repository.readProfile("sentinel")
    }

    @Test
    fun cancellationDuringVitalsAfterRecordsCommitKeepsEveryReferencedPageOnReopen() = runTest {
        val memory = GenerationalMemoryVault()
        var beforeVitals: suspend () -> Unit = {}
        val vault = object : app.vezuna.security.SecureVault by memory {
            override suspend fun writeAcknowledged(key: String, ciphertext: ByteArray): app.vezuna.security.VaultWriteResult {
                if (key == "repository-vitals-restored-v1") beforeVitals()
                return memory.writeAcknowledged(key, ciphertext)
            }
        }
        val repository = VaultBackedRepository(vault).also { it.load() }
        val store = VaultSourceFileStore(vault)
        val sentinel = seedSentinel(repository, store)
        val cancellation = CancellationException("cancel after durable record restore")
        var reachedVitals = false
        beforeVitals = {
            reachedVitals = true
            val durable = VaultBackedRepository(vault).also { it.load() }.readProfile("restored")
            assertEquals(listOf("scan"), durable.documents.map { it.documentId })
            assertPages(store, durable)
            throw cancellation
        }
        val cancelling = repository

        assertSame(cancellation, assertFailsWith<CancellationException> {
            cancelling.restoreMembers(listOf(member()), RestoreMode.REPLACE_EXISTING, store) { error("unexpected remap") }
        })

        assertEquals(true, reachedVitals, "cancellation must occur after the real restoreProfile commits")
        val reopened = VaultBackedRepository(vault).also { it.load() }
        val reopenedStore = VaultSourceFileStore(vault)
        assertEquals(setOf("restored", "sentinel"), reopened.profiles.value.map { it.profileId }.toSet())
        assertEquals(sentinel, reopened.readProfile("sentinel"))
        assertEquals(sentinelBytes.toList(), reopenedStore.load("sentinel", "sentinel-doc", assertNotNull(sentinel.documents.single().vaultKey))?.toList())
        assertPages(reopenedStore, reopened.readProfile("restored"))
    }

    @Test
    fun cancellationBeforeRecordCommitDiscardsStagedPagesAndPreservesSentinelOnReopen() = runTest {
        val vault = GenerationalMemoryVault()
        val repository = VaultBackedRepository(vault).also { it.load() }
        val store = VaultSourceFileStore(vault)
        val sentinel = seedSentinel(repository, store)
        val keysBefore = vault.current.keys.toSet()
        val cancellation = CancellationException("cancel before record commit")
        var reachedRecords = false
        val cancelling = object : VezunaRepositoryDelegate(repository) {
            override suspend fun restoreProfileAndVitals(data: ProfileData): Boolean {
                reachedRecords = true
                assertPages(store, data)
                throw cancellation
            }
        }

        assertSame(cancellation, assertFailsWith<CancellationException> {
            cancelling.restoreMembers(listOf(member()), RestoreMode.REPLACE_EXISTING, store) { error("unexpected remap") }
        })

        assertEquals(true, reachedRecords, "both pages must be staged before cancellation")
        assertEquals(keysBefore, vault.current.keys.toSet(), "uncommitted staged files must be discarded")
        val reopened = VaultBackedRepository(vault).also { it.load() }
        assertEquals(listOf("sentinel"), reopened.profiles.value.map { it.profileId })
        assertEquals(sentinel, reopened.readProfile("sentinel"))
        assertEquals(sentinelBytes.toList(), VaultSourceFileStore(vault).load("sentinel", "sentinel-doc", assertNotNull(sentinel.documents.single().vaultKey))?.toList())
    }

    @Test
    fun indexFailureKeepsPagesForNewAndExistingProfiles() = runTest {
        for (existing in listOf(false, true)) {
            for (cancel in listOf(false, true)) {
                val memory = GenerationalMemoryVault()
                val failure = if (cancel) CancellationException("index write") else IllegalStateException("index write")
                var armed = false
                val vault = object : app.vezuna.security.SecureVault by memory {
                    override suspend fun write(key: String, ciphertext: ByteArray) {
                        if (armed && key == "repository-profile-index-v1") throw failure
                        memory.write(key, ciphertext)
                    }
                }
                val repository = VaultBackedRepository(vault).also { it.load() }
                val store = VaultSourceFileStore(vault)
                val sentinel = seedSentinel(repository, store)
                if (existing) repository.createProfile(member().data.profile)
                armed = true
                if (cancel || !existing) {
                    assertSame(failure, assertFailsWith<Exception> {
                        repository.restoreMembers(listOf(member()), RestoreMode.REPLACE_EXISTING, store) { error("remap") }
                    })
                } else {
                    assertEquals(listOf("restored"), repository.restoreMembers(listOf(member()), RestoreMode.REPLACE_EXISTING, store) { error("remap") }.profileIds)
                }
                armed = false
                val reopened = VaultBackedRepository(vault).also { it.load() }
                assertPages(store, if (existing) reopened.readProfile("restored") else durableUnindexedProfile(memory))
                assertEquals(sentinel, reopened.readProfile("sentinel"))
            }
        }
    }

    @Test
    fun profileWriteThatThrowsAfterPersistenceKeepsPagesForNewAndExistingProfiles() = runTest {
        for (existing in listOf(false, true)) {
            for (cancel in listOf(false, true)) {
                val memory = GenerationalMemoryVault()
                val failure = if (cancel) CancellationException("postwrite") else IllegalStateException("postwrite")
                var armed = false
                var reachedWrite = false
                val vault = object : app.vezuna.security.SecureVault by memory {
                    override suspend fun write(key: String, ciphertext: ByteArray) {
                        memory.write(key, ciphertext)
                        if (armed && key == "repository-profile-restored-v1") {
                            reachedWrite = true
                            throw failure
                        }
                    }
                }
                val repository = VaultBackedRepository(vault).also { it.load() }
                val store = VaultSourceFileStore(vault)
                val sentinel = seedSentinel(repository, store)
                if (existing) repository.createProfile(member().data.profile)
                armed = true
                assertSame(failure, assertFailsWith<Exception> {
                    repository.restoreMembers(listOf(member()), RestoreMode.REPLACE_EXISTING, store) { error("remap") }
                })
                assertEquals(true, reachedWrite, "failure must follow the actual profile write")
                armed = false
                val reopened = VaultBackedRepository(vault).also { it.load() }
                assertPages(store, if (existing) reopened.readProfile("restored") else durableUnindexedProfile(memory))
                assertEquals(sentinel, reopened.readProfile("sentinel"))
                assertEquals(sentinelBytes.toList(), store.load("sentinel", "sentinel-doc", assertNotNull(sentinel.documents.single().vaultKey))?.toList())
            }
        }
    }

    @Test
    fun orphanDeletionCancellationKeepsCommittedReplacementPages() = runTest {
        val memory = GenerationalMemoryVault()
        val cancellation = CancellationException("orphan deletion")
        var orphan: String? = null
        val vault = object : app.vezuna.security.SecureVault by memory {
            override suspend fun delete(key: String) {
                if (key == orphan) throw cancellation
                memory.delete(key)
            }
        }
        val repository = VaultBackedRepository(vault).also { it.load() }
        val store = VaultSourceFileStore(vault)
        repository.createProfile(member().data.profile)
        orphan = store.save("restored", "old", sentinelBytes)
        repository.addDocument(SourceDocument("restored", "old", "old.jpg", "image/jpeg", "old-hash", time, vaultKey = orphan))
        assertSame(cancellation, assertFailsWith<CancellationException> {
            repository.restoreMembers(listOf(member()), RestoreMode.REPLACE_EXISTING, store) { error("remap") }
        })
        orphan = null
        assertPages(store, VaultBackedRepository(vault).also { it.load() }.readProfile("restored"))
    }

    @Test
    fun secondPageFailureAndProfileEncryptionFailureCleanOnlyStagedPages() = runTest {
        for (stage in listOf("second-page", "profile-encryption")) {
            for (cancel in listOf(false, true)) {
                val memory = GenerationalMemoryVault()
                val failure = if (cancel) CancellationException(stage) else IllegalStateException(stage)
                var armed = false
                val vault = object : app.vezuna.security.SecureVault by memory {
                    override suspend fun encrypt(plainText: ByteArray, associatedData: ByteArray): app.vezuna.security.CipherEnvelope {
                        if (armed && stage == "profile-encryption" && associatedData.decodeToString() == "Vezuna repository profile v1:restored") throw failure
                        return memory.encrypt(plainText, associatedData)
                    }
                }
                val repository = VaultBackedRepository(vault).also { it.load() }
                val store = VaultSourceFileStore(vault)
                val sentinel = seedSentinel(repository, store)
                val before = memory.current.keys.toSet()
                val failingStore = object : app.vezuna.ui.SourceFileStore by store {
                    override suspend fun save(profileId: String, documentId: String, bytes: ByteArray): String {
                        if (stage == "second-page" && documentId == "scan-page-2") throw failure
                        return store.save(profileId, documentId, bytes)
                    }
                }
                armed = true
                assertSame(failure, assertFailsWith<Exception> {
                    repository.restoreMembers(listOf(member()), RestoreMode.REPLACE_EXISTING, failingStore) { error("remap") }
                })
                val intent = "repository-replacement-intent-restored-v1"
                assertEquals(before, memory.current.keys.toSet() - intent)
                if (stage == "profile-encryption") assertEquals(listOf<Byte>(1), memory.current[intent]?.toList())
                armed = false
                val reopened = VaultBackedRepository(vault).also { it.load() }
                assertEquals(listOf("sentinel"), reopened.profiles.value.map { it.profileId })
                assertEquals(sentinel, reopened.readProfile("sentinel"))
                assertEquals(sentinelBytes.toList(), store.load("sentinel", "sentinel-doc", assertNotNull(sentinel.documents.single().vaultKey))?.toList())
            }
        }
    }

    @Test
    fun cancellationBeforeReplacementRecordWritePreservesOldPagesAndSentinel() = runTest {
        val memory = GenerationalMemoryVault()
        val cancellation = CancellationException("replacement profile encryption")
        var armed = false
        var reachedEncryption = false
        val vault = object : app.vezuna.security.SecureVault by memory {
            override suspend fun encrypt(plainText: ByteArray, associatedData: ByteArray): app.vezuna.security.CipherEnvelope {
                if (armed && associatedData.decodeToString() == "Vezuna repository profile v1:restored") {
                    reachedEncryption = true
                    throw cancellation
                }
                return memory.encrypt(plainText, associatedData)
            }
        }
        val repository = VaultBackedRepository(vault).also { it.load() }
        val store = VaultSourceFileStore(vault)
        val sentinel = seedSentinel(repository, store)
        repository.createProfile(member().data.profile)
        val oldKey = store.save("restored", "old", sentinelBytes)
        repository.addDocument(SourceDocument("restored", "old", "old.jpg", "image/jpeg", "old-hash", time, vaultKey = oldKey))
        val old = repository.readProfile("restored")
        val keysBefore = memory.current.keys.toSet()
        armed = true

        assertSame(cancellation, assertFailsWith<CancellationException> {
            repository.restoreMembers(listOf(member()), RestoreMode.REPLACE_EXISTING, store) { error("unexpected remap") }
        })

        assertEquals(true, reachedEncryption, "the atomic restore must reach profile encryption")
        armed = false
        // Replacement intent remains fail-closed even though the profile write never started.
        val intent = "repository-replacement-intent-restored-v1"
        assertEquals(listOf<Byte>(1), memory.current[intent]?.toList())
        assertEquals(keysBefore, memory.current.keys.toSet() - intent)
        val reopened = VaultBackedRepository(vault).also { it.load() }
        assertEquals(old, reopened.readProfile("restored"))
        assertEquals(sentinelBytes.toList(), store.load("restored", "old", oldKey)?.toList())
        assertEquals(sentinel, reopened.readProfile("sentinel"))
        assertEquals(sentinelBytes.toList(), store.load("sentinel", "sentinel-doc", assertNotNull(sentinel.documents.single().vaultKey))?.toList())
    }

    @Test
    fun cancellingSuspendedRestoreAfterDurableWritePreservesPages() = runTest {
        for (stage in listOf("profile-write", "vitals")) {
            val memory = GenerationalMemoryVault()
            val reached = CompletableDeferred<Unit>()
            val vault = object : app.vezuna.security.SecureVault by memory {
                override suspend fun writeAcknowledged(key: String, ciphertext: ByteArray): app.vezuna.security.VaultWriteResult {
                    if (stage == "vitals" && key == "repository-vitals-restored-v1") {
                        reached.complete(Unit)
                        awaitCancellation()
                    }
                    return memory.writeAcknowledged(key, ciphertext)
                }
                override suspend fun write(key: String, ciphertext: ByteArray) {
                    memory.write(key, ciphertext)
                    if (stage == "profile-write" && key == "repository-profile-restored-v1") {
                        reached.complete(Unit)
                        awaitCancellation()
                    }
                }
            }
            val repository = VaultBackedRepository(vault).also { it.load() }
            val store = VaultSourceFileStore(vault)
            val sentinel = seedSentinel(repository, store)
            val restoring = repository
            val job = launch {
                restoring.restoreMembers(listOf(member()), RestoreMode.REPLACE_EXISTING, store) { error("remap") }
            }
            withTimeout(5_000) { reached.await() }
            // The parent cancels a genuinely suspended child after observing durable storage.
            assertPages(store, if (stage == "profile-write") durableUnindexedProfile(memory) else VaultBackedRepository(vault).also { it.load() }.readProfile("restored"))
            job.cancelAndJoin()
            assertEquals(true, job.isCancelled)
            val reopened = VaultBackedRepository(vault).also { it.load() }
            assertPages(store, if (stage == "profile-write") durableUnindexedProfile(memory) else reopened.readProfile("restored"))
            assertEquals(sentinel, reopened.readProfile("sentinel"))
        }
    }

    // A new profile whose index write never completed is durable but unlisted on an ordinary reopen.
    // Inspect its actual record bytes, rather than claiming this fix changes index recovery policy.
    private suspend fun durableUnindexedProfile(vault: GenerationalMemoryVault): ProfileData {
        val envelope = app.vezuna.security.CipherEnvelopeCodec.decode(vault.current.getValue("repository-profile-restored-v1"))
        val plain = vault.decrypt(envelope, "Vezuna repository profile v1:restored".encodeToByteArray())
        return storedJson.decodeFromString(ProfileData.serializer(), plain.decodeToString())
    }

    private suspend fun assertPages(store: VaultSourceFileStore, data: ProfileData) {
        val document = data.documents.single()
        assertEquals("restored", data.profile.profileId)
        assertEquals("scan", document.documentId)
        assertEquals(1, document.pageVaultKeys.size)
        val actual = listOf(
            store.load("restored", "scan", assertNotNull(document.vaultKey))?.toList(),
            store.load("restored", "scan-page-2", document.pageVaultKeys.single())?.toList(),
        )
        assertEquals(listOf(firstPage.toList(), secondPage.toList()), actual, "all durable document references must retain their exact page bytes")
    }
}
