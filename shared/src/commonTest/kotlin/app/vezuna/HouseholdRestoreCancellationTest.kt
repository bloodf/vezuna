package app.vezuna

import app.vezuna.data.VaultBackedRepository
import app.vezuna.data.VaultSourceFileStore
import app.vezuna.data.restoreMembers
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.ProfileVitals
import app.vezuna.domain.SourceDocument
import app.vezuna.security.BackupMember
import app.vezuna.ui.RestoreMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.time.Instant

class HouseholdRestoreCancellationTest {
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
        val vault = GenerationalMemoryVault()
        val repository = VaultBackedRepository(vault).also { it.load() }
        val store = VaultSourceFileStore(vault)
        val sentinel = seedSentinel(repository, store)
        val cancellation = CancellationException("cancel after durable record restore")
        var reachedVitals = false
        val cancelling = object : VezunaRepositoryDelegate(repository) {
            override suspend fun replaceVitals(profileId: String, vitals: ProfileVitals) {
                reachedVitals = true
                val durable = VaultBackedRepository(vault).also { it.load() }.readProfile(profileId)
                assertEquals(listOf("scan"), durable.documents.map { it.documentId })
                assertPages(store, durable)
                throw cancellation
            }
        }

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
            override suspend fun restoreProfile(data: ProfileData) {
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
