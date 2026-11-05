package app.vezuna

import app.vezuna.data.VaultBackedRepository
import app.vezuna.data.VaultSourceFileStore
import app.vezuna.data.restoreMembers
import app.vezuna.domain.ProfileVitals
import app.vezuna.security.BackupMember
import app.vezuna.ui.RestoreMode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HouseholdPartialRestoreTest {
    private fun member(prefix: String): BackupMember {
        val data = sampleProfileData(prefix)
        val doc = data.documents.single().copy(pageVaultKeys = listOf("backup-page-2", "backup-page-3"))
        return BackupMember(data.copy(documents = listOf(doc)), mapOf(
            "$prefix-doc" to byteArrayOf(prefix.first().code.toByte(), 1),
            "$prefix-doc-page-2" to byteArrayOf(prefix.first().code.toByte(), 2),
            "$prefix-doc-page-3" to byteArrayOf(prefix.first().code.toByte(), 3),
        ))
    }

    private suspend fun assertStored(repository: VaultBackedRepository, store: VaultSourceFileStore, member: BackupMember, vitals: ProfileVitals) {
        val actual = repository.readProfile(member.data.profile.profileId)
        val doc = actual.documents.single()
        val expectedDoc = member.data.documents.single().copy(
            vaultKey = "source-document-${doc.documentId}",
            pageVaultKeys = listOf("source-document-${doc.documentId}-page-2", "source-document-${doc.documentId}-page-3"),
        )
        assertEquals(member.data.copy(documents = listOf(expectedDoc), vitals = vitals), actual)
        val keys = listOf(assertNotNull(doc.vaultKey)) + doc.pageVaultKeys
        assertEquals(3, keys.size)
        keys.forEachIndexed { index, key ->
            val id = doc.documentId + if (index == 0) "" else "-page-${index + 1}"
            assertContentEquals(member.files.getValue(id), store.load(actual.profile.profileId, id, key))
        }
    }

    @Test
    fun laterMemberFailureKeepsEarlierMemberAndRespectsWriteBoundary() = runTest {
        for (failedKey in listOf("repository-profile-B-profile-v1", "source-document-B-doc-page-3")) {
            val vault = GenerationalMemoryVault()
            val repository = VaultBackedRepository(vault).also { it.load() }
            val store = VaultSourceFileStore(vault)
            val third = member("C")
            repository.restoreMembers(listOf(third), RestoreMode.REPLACE_EXISTING, store) { error("unexpected remap") }
            val before = repository.readProfile("C-profile")
            val a = member("A")
            val b = member("B")
            vault.failingWrites += failedKey

            assertFailsWith<IllegalStateException> {
                repository.restoreMembers(listOf(a, b), RestoreMode.REPLACE_EXISTING, store) { error("unexpected remap") }
            }

            vault.failingWrites.clear()
            val reopened = VaultBackedRepository(vault).also { it.load() }
            assertStored(reopened, store, a, a.data.vitals.copy(lastSync = null))
            assertEquals(before, reopened.readProfile("C-profile"))
            assertStored(reopened, store, third, third.data.vitals.copy(lastSync = null))
            assertEquals(setOf("A-profile", "C-profile"), reopened.profiles.value.map { it.profileId }.toSet())
            listOf("B-doc", "B-doc-page-2", "B-doc-page-3").forEach { id ->
                val key = "source-document-$id"
                if (failedKey == "repository-profile-B-profile-v1") {
                    assertContentEquals(b.files.getValue(id), store.load("B-profile", id, key))
                } else {
                    assertNull(vault.read(key))
                }
            }
            assertNull(vault.read("repository-profile-B-profile-v1"))
        }
    }

    @Test
    fun failedVitalsForFirstMemberDoesNotPreventSecondMemberRestore() = runTest {
        val vault = GenerationalMemoryVault()
        val repository = VaultBackedRepository(vault).also { it.load() }
        val store = VaultSourceFileStore(vault)
        val a = member("A")
        val b = member("B")
        val oldVitals = sampleProfileData("old").vitals
        repository.restoreProfile(a.data.copy(documents = a.data.documents.map { it.copy(vaultKey = null, pageVaultKeys = emptyList()) }, vitals = oldVitals))
        repository.replaceVitals("A-profile", oldVitals)
        val failing = object : VezunaRepositoryDelegate(repository) {
            override suspend fun replaceVitals(profileId: String, vitals: ProfileVitals) {
                if (profileId == "A-profile") error("disk full")
                repository.replaceVitals(profileId, vitals)
            }
        }

        val outcome = failing.restoreMembers(listOf(a, b), RestoreMode.REPLACE_EXISTING, store) { error("unexpected remap") }

        assertFalse(outcome.allVitalsRestored)
        assertEquals(listOf("A-profile", "B-profile"), outcome.profileIds)
        val reopened = VaultBackedRepository(vault).also { it.load() }
        assertStored(reopened, store, a, oldVitals)
        assertStored(reopened, store, b, b.data.vitals.copy(lastSync = null))
    }
}
