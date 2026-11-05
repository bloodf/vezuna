package app.vezuna

import app.vezuna.data.VaultBackedRepository
import app.vezuna.data.VaultSourceFileStore
import app.vezuna.data.restoreMembers
import app.vezuna.domain.*
import app.vezuna.localization.L10nKey
import app.vezuna.localization.VitaMessageException
import app.vezuna.security.*
import app.vezuna.ui.RestoreMode
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Instant

/** Real portable crypto; local persistence deliberately reuses the synthetic test vault. */
class HouseholdGoldenBackupTest {
    private val t = Instant.parse("2026-01-01T00:00:00Z")
    private val sync = Instant.parse("2026-01-02T00:00:00Z")
    private val page1 = byteArrayOf(0, 1, 127, -128, -1)
    private val page2 = byteArrayOf(112, 97, 103, 101, 32, 50, 0, 13, 10)
    private val service = BackupService()

    // Independently authored constructors, never deserialized from app output or the fixture plaintext.
    private fun expected(schema: Int): List<ProfileData> {
        val a = SourceDocument("house-a", "a-doc", "Synthetic scan.bin", "application/octet-stream",
            "0150a92bb1212cd00516b65fde0704614760000963874fcbb11eaa734ee87809", t,
            "old-a-page-1", listOf("old-a-page-2"))
        val b = SourceDocument("house-b", "b-omitted", "Synthetic note.txt", "text/plain",
            "26fd516b2a65fd8ece29347b515badc6fbe409e8d986aa92fab9c7989a2b95d3", t,
            "old-b-page-1", listOf("old-b-page-2"))
        return listOf(
            ProfileData(Profile("house-a", "Synthetic Ana", createdAt = t), documents = listOf(a),
                reports = listOf(LabReport("house-a", "a-report", "a-doc", "Synthetic Lab", importedAt = t)), vitals = vitals(1)),
            ProfileData(Profile("house-b", "Synthetic Bia", createdAt = t),
                vaultDocuments = if (schema == 7) listOf(VaultDocument(b, listOf("Family", "Exame"))) else emptyList(), vitals = vitals(2)),
        )
    }

    private fun vitals(n: Int) = ProfileVitals(
        steps = listOf(StepsDay(LocalDate(2026, 1, 1), if (n == 1) 1234 else 4321)),
        heartRateHourly = listOf(HeartRateBucket(t, 60.0 + n, 70.0 + n, 80.0 + n)),
        heartRateDaily = listOf(HeartRateDay(LocalDate(2025, 12, 1), 55.0 + n, 65.0 + n, 85.0 + n, 3 + n)),
        weights = listOf(WeightReading("platform-weight-$n", t, if (n == 1) 61.25 else 82.5)), lastSync = sync,
    )

    private fun envelope(schema: Int) = Base64.decode(if (schema == 7) GoldenHouseholdBackup.V7_BASE64 else GoldenHouseholdBackup.V4_BASE64)
    private val passwords get() = listOf(GoldenHouseholdBackup.PASSPHRASE_NFC, GoldenHouseholdBackup.PASSPHRASE_NFD)

    @Test fun frozenSchemasOpenWithBothUnicodeForms() = runTest {
        assertNotEquals(passwords[0], passwords[1])
        for (schema in listOf(7, 4)) for (password in passwords) {
            val bytes = envelope(schema)
            val plain = if (schema == 7) GoldenHouseholdBackup.V7_PLAIN else GoldenHouseholdBackup.V4_PLAIN
            assertContentEquals(plain.encodeToByteArray(), PassphraseBackupCodec.open(bytes, password.toCharArray()))
            val opened = service.open(bytes, password.toCharArray())
            assertEquals(BackupKind.PORTABLE, opened.kind)
            assertEquals(expected(schema), opened.members.map { it.data })
            assertEquals(listOf("house-a", "house-b"), opened.members.map { it.data.profile.profileId })
            assertEquals(setOf("a-doc", "a-doc-page-2"), opened.members[0].files.keys)
            assertContentEquals(page1, opened.members[0].files.getValue("a-doc"))
            assertContentEquals(page2, opened.members[0].files.getValue("a-doc-page-2"))
            assertTrue(opened.members[1].files.isEmpty())
            assertTrue(opened.members[0].omitted.isEmpty())
            assertEquals(if (schema == 7) listOf(OmittedDocument("b-omitted", "Synthetic note.txt")) else emptyList(), opened.members[1].omitted)
        }
    }

    private fun persisted(data: ProfileData) = data.copy(
        documents = data.documents.map { it.copy(vaultKey = "source-document-a-doc", pageVaultKeys = listOf("source-document-a-doc-page-2")) },
        vaultDocuments = data.vaultDocuments.map { it.copy(source = it.source.copy(vaultKey = null, pageVaultKeys = emptyList())) },
        vitals = data.vitals.copy(lastSync = null),
    )

    private suspend fun assertPages(repository: VaultBackedRepository, store: VaultSourceFileStore, profile: String) {
        val doc = repository.peekProfile(profile).documents.single()
        assertContentEquals(page1, store.load(profile, doc.documentId, assertNotNull(doc.vaultKey)))
        assertEquals(1, doc.pageVaultKeys.size)
        assertContentEquals(page2, store.load(profile, "${doc.documentId}-page-2", doc.pageVaultKeys.single()))
    }

    @Test fun frozenSchemasRestoreAndReopenExactRecordsAndBytes() = runTest {
        for (schema in listOf(7, 4)) for (password in passwords) {
            val vault = HouseholdCountingVault()
            val repository = VaultBackedRepository(vault).also { it.load() }
            val store = VaultSourceFileStore(vault)
            val initial = repository.profiles.value.map { it.profileId }.toSet()
            val opened = service.open(envelope(schema), password.toCharArray())
            val outcome = repository.restoreMembers(opened.members, RestoreMode.REPLACE_EXISTING, store) { error("unexpected remap") }
            assertEquals(listOf("house-a", "house-b"), outcome.profileIds)
            assertTrue(outcome.allVitalsRestored)
            val reopened = VaultBackedRepository(vault).also { it.load() }
            assertEquals(initial + setOf("house-a", "house-b"), reopened.profiles.value.map { it.profileId }.toSet())
            for (data in expected(schema)) assertEquals(persisted(data), reopened.peekProfile(data.profile.profileId))
            assertPages(reopened, store, "house-a")
            assertTrue(vault.inner.current.keys.none { "b-omitted" in it || "old-" in it })
        }
    }

    @Test fun restoreAsNewPreservesOriginalsAndRemapsReferences() = runTest {
        val vault = HouseholdCountingVault()
        val repository = VaultBackedRepository(vault).also { it.load() }
        val store = VaultSourceFileStore(vault)
        val members = service.open(envelope(7), passwords[0].toCharArray()).members
        repository.restoreMembers(members, RestoreMode.REPLACE_EXISTING, store) { error("unexpected remap") }
        val originals = listOf(repository.peekProfile("house-a"), repository.peekProfile("house-b"))
        val allocated = mutableListOf<String>()
        val result = repository.restoreMembers(members, RestoreMode.RESTORE_AS_NEW, store) {
            "fresh-${allocated.size}".also { allocated += it }
        }
        assertTrue(result.allVitalsRestored)
        val reopened = VaultBackedRepository(vault).also { it.load() }
        assertEquals(4, reopened.profiles.value.size)
        assertEquals(originals, listOf(reopened.peekProfile("house-a"), reopened.peekProfile("house-b")))
        val a = reopened.peekProfile(result.profileIds[0])
        val b = reopened.peekProfile(result.profileIds[1])
        val newIds = listOf(a.profile.profileId, b.profile.profileId, a.documents.single().documentId, a.reports.single().reportId, b.vaultDocuments.single().source.documentId)
        assertEquals(5, newIds.toSet().size)
        assertEquals(allocated.toSet(), newIds.toSet())
        assertTrue(newIds.none { it in setOf("house-a", "house-b", "a-doc", "a-report", "b-omitted") })
        assertEquals(a.profile.profileId, a.documents.single().profileId)
        assertEquals(a.profile.profileId, a.reports.single().profileId)
        assertEquals(a.documents.single().documentId, a.reports.single().sourceDocumentId)
        assertEquals(persisted(expected(7)[0]).copy(
            profile = expected(7)[0].profile.copy(profileId = a.profile.profileId),
            documents = listOf(expected(7)[0].documents.single().copy(profileId = a.profile.profileId, documentId = a.documents.single().documentId,
                vaultKey = "source-document-${a.documents.single().documentId}", pageVaultKeys = listOf("source-document-${a.documents.single().documentId}-page-2"))),
            reports = listOf(expected(7)[0].reports.single().copy(profileId = a.profile.profileId, reportId = a.reports.single().reportId, sourceDocumentId = a.documents.single().documentId))), a)
        val expectedB = persisted(expected(7)[1])
        assertEquals(expectedB.copy(profile = expectedB.profile.copy(profileId = b.profile.profileId),
            vaultDocuments = listOf(expectedB.vaultDocuments.single().let { it.copy(source = it.source.copy(profileId = b.profile.profileId, documentId = b.vaultDocuments.single().source.documentId)) })), b)
        assertPages(reopened, store, "house-a")
        assertPages(reopened, store, a.profile.profileId)
    }

    @Test fun authenticationFailuresNeverEnterRestoreOrMutateVault() = runTest {
        val vault = HouseholdCountingVault()
        val repository = VaultBackedRepository(vault).also { it.load() }
        repository.createProfile(Profile("sentinel", "Synthetic sentinel", createdAt = t))
        val store = VaultSourceFileStore(vault)
        var entered = 0
        suspend fun consume(file: ByteArray, password: String) {
            val opened = service.open(file, password.toCharArray())
            entered++
            repository.restoreMembers(opened.members, RestoreMode.REPLACE_EXISTING, store) { error("unexpected remap") }
        }
        val before = repository.profiles.value.map { repository.peekProfile(it.profileId) }
        val snapshot = vault.snapshot()
        vault.resetCounters()
        val valid = envelope(7)
        val cases = listOf(valid to "not the passphrase") + listOf(40, valid.lastIndex, 11).map { offset ->
            valid.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() } to passwords[0]
        }
        for ((bytes, password) in cases) {
            val failure = assertFailsWith<VitaMessageException> { consume(bytes, password) }
            assertEquals(L10nKey.BACKUP_WRONG_PASSPHRASE, failure.userText.key)
            assertEquals(0, entered)
            assertEquals(before, repository.profiles.value.map { repository.peekProfile(it.profileId) })
            assertEquals(snapshot, vault.snapshot())
            assertEquals(listOf(0, 0, 0, 0), vault.mutations())
        }
        consume(valid, passwords[0])
        assertEquals(1, entered)
        assertTrue(vault.writes > 0, "positive control must reach durable storage")
        assertEquals(listOf("sentinel", "house-a", "house-b").toSet(), repository.profiles.value.map { it.profileId }.toSet())
    }
}

private class HouseholdCountingVault(val inner: GenerationalMemoryVault = GenerationalMemoryVault()) : SecureVault by inner {
    var writes = 0
    var deletes = 0
    var quarantines = 0
    var resets = 0
    var reads = 0
    override suspend fun write(key: String, ciphertext: ByteArray) { writes++; inner.write(key, ciphertext) }
    override suspend fun delete(key: String) { deletes++; inner.delete(key) }
    override suspend fun quarantineAll(): Boolean { quarantines++; return inner.quarantineAll() }
    override suspend fun quarantineCurrent(key: String): Boolean { quarantines++; return inner.quarantineCurrent(key) }
    override suspend fun discardUnusableKey() { resets++; inner.discardUnusableKey() }
    override suspend fun read(key: String): ByteArray? { reads++; return inner.read(key) }
    override suspend fun readPrevious(key: String): ByteArray? { reads++; return inner.readPrevious(key) }
    fun mutations() = listOf(writes, deletes, quarantines, resets)
    fun resetCounters() { writes = 0; deletes = 0; quarantines = 0; resets = 0; reads = 0 }
    // Lists copy every byte and provide structural equality across all generations and quarantine.
    fun snapshot() = listOf(inner.current, inner.previous, inner.quarantined).map { generation -> generation.mapValues { it.value.toList() } }
}
