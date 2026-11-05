package app.vezuna

import app.vezuna.data.*
import app.vezuna.domain.*
import app.vezuna.health.*
import app.vezuna.security.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.*
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Instant

class WeightPersistenceTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val source = WeightSource(WeightProvider.HEALTH_CONNECT, "provider-chain", "weight-only/all-origins/v1")
    private val claim = SourceClaim(source, SourceContinuityEvidence(source, "accepted provider token"))
    private val w = WeightReading("W", now, 70.0)
    private val zone = TimeZone.UTC
    private val json = Json { encodeDefaults = true }
    private val key = "repository-vitals-p-v1"

    private class FaultVault : SecureVault {
        val memory = GenerationalMemoryVault()
        var fault = ""
        var intentWritten = false
        override suspend fun encrypt(plainText: ByteArray, associatedData: ByteArray) = memory.encrypt(plainText, associatedData)
        override suspend fun decrypt(envelope: CipherEnvelope) = memory.decrypt(envelope)
        override suspend fun read(key: String) = memory.read(key)
        override suspend fun readPrevious(key: String) = memory.readPrevious(key)
        override suspend fun readGeneration(key: String) = memory.readGeneration(key)
        override suspend fun listKeys() = memory.listKeys()
        override suspend fun quarantineCurrent(key: String) = memory.quarantineCurrent(key)
        override suspend fun delete(key: String) {
            if (fault == "intent-clear" && key.startsWith("repository-vitals-replacement-")) error("interrupted before clear")
            memory.delete(key)
        }
        override suspend fun write(key: String, ciphertext: ByteArray) {
            if (fault == "profile" && intentWritten && key == "repository-profile-p-v1") throw CancellationException("profile interrupted")
            if (fault == "link" && key == "repository-health-link-v1") error("link bookkeeping")
            memory.write(key, ciphertext)
        }
        override suspend fun writeAcknowledged(key: String, ciphertext: ByteArray): VaultWriteResult {
            if (key.startsWith("repository-vitals-replacement-")) {
                memory.write(key, ciphertext); intentWritten = true
                return VaultWriteResult.Acknowledged
            }
            if (key == "repository-vitals-p-v1") {
                if (fault == "reject") return VaultWriteResult.Rejected(IllegalStateException("before replacement"))
                if (fault == "uncertain-before") return VaultWriteResult.Indeterminate(IllegalStateException("rename uncertainty"))
                memory.write(key, ciphertext)
                if (fault == "uncertain-after") return VaultWriteResult.Indeterminate(IllegalStateException("directory sync"))
                if (fault == "cancel-after") currentCoroutineContext().cancel()
                return VaultWriteResult.Acknowledged
            }
            memory.write(key, ciphertext)
            return VaultWriteResult.Acknowledged
        }
    }

    private suspend fun open(vault: SecureVault): VaultBackedRepository = VaultBackedRepository(vault).also { it.load() }
    private suspend fun seed(vault: FaultVault): Pair<VaultBackedRepository, VitalsReadState> {
        val repository = open(vault)
        repository.createProfile(Profile("p", "Synthetic", createdAt = now))
        repository.setLinkedHealthProfile("p")
        val initial = repository.readVitalsState(repository.captureVitalsSync(zone))
        val result = assertIs<VitalsWriteOutcome.Committed>(repository.commitWeightPage(initial,
            WeightPage(claim, listOf(WeightMutation.Upsert(w)), WeightContinuation.Steady(source, "T0"))))
        return repository to assertNotNull(result.next)
    }

    private class Pages(private val source: WeightSource, private val now: Instant) : WeightChangesSource {
        var calls = 0
        override fun weightClaim(source: WeightSource) = SourceClaim(source, SourceContinuityEvidence(source, "synthetic provider continuity"))
        override suspend fun read(request: VitalsReadRequest) = VitalsBatch(request.replaceFrom)
        override suspend fun weightAccess() = WeightAccess()
        override suspend fun weightToken(): String = error("must resume committed T0")
        override suspend fun weightSnapshot(from: Instant, until: Instant, pageToken: String?, pageSize: Int): WeightSnapshotPage = error("must not snapshot")
        override suspend fun weightChanges(token: String): WeightChangesPage {
            calls++
            assertEquals("T0", token)
            return WeightChangesPage(listOf(WeightMutation.Upsert(WeightReading("W", now, 72.0))), "T1", true, false)
        }
    }

    @Test fun rejectedAndUncertainPagesNeverReadNextAndReopenWholeGeneration() = runTest {
        for (fault in listOf("reject", "uncertain-before", "uncertain-after")) {
            val vault = FaultVault()
            val (repository, state) = seed(vault)
            vault.fault = fault
            val pages = Pages(source, now)
            WeightPaging(pages, repository, now) { Duration.ZERO }.run(state)
            assertEquals(1, pages.calls, fault)
            val reopened = open(vault)
            val saved = reopened.readVitalsState(reopened.captureVitalsSync(zone))
            assertEquals(if (fault == "uncertain-after") "T1" else "T0", (saved.local.continuation as? WeightContinuation.Steady)?.token ?: (saved.local.continuation as WeightContinuation.Replay).token)
            assertEquals(listOf(w.copy(kg = if (fault == "uncertain-after") 72.0 else 70.0)), reopened.peekProfile("p").vitals.weights)
            assertEquals(mapOf("W" to claim.continuity), saved.local.provenance)
            if (fault.startsWith("uncertain")) assertFailsWith<IllegalStateException> { repository.captureVitalsSync(zone) }
            assertIs<VitalsWriteOutcome.Rejected>(repository.commitWeightPage(state, WeightPage(claim, emptyList())))
        }
    }

    @Test fun acknowledgedWriteThenCancellationKeepsNewEnvelopeWithoutSuccessor() = runTest {
        val vault = FaultVault()
        val (repository, state) = seed(vault)
        vault.fault = "cancel-after"
        var outcome: VitalsWriteOutcome? = null
        val job = launch { outcome = repository.commitWeightPage(state, WeightPage(claim, listOf(WeightMutation.Delete("W")), WeightContinuation.Steady(source, "T1"))) }
        job.join()
        assertNull(assertIs<VitalsWriteOutcome.Committed>(outcome).next)
        val reopened = open(vault)
        assertEquals(emptyList(), reopened.peekProfile("p").vitals.weights)
        assertEquals("T1", (reopened.readVitalsState(reopened.captureVitalsSync(zone)).local.continuation as WeightContinuation.Steady).token)
    }

    @Test fun knownCommitThenLinkFailureReturnsCommittedWithoutNext() = runTest {
        val vault = FaultVault()
        val repository = open(vault)
        repository.createProfile(Profile("p", "Synthetic", createdAt = now))
        vault.fault = "link"
        val outcome = repository.commitWeightPage(repository.readVitalsState(repository.captureVitalsSync(zone)), WeightPage(claim, listOf(WeightMutation.Upsert(w)), WeightContinuation.Steady(source, "T1")))
        assertNull(assertIs<VitalsWriteOutcome.Committed>(outcome).next)
        assertFailsWith<IllegalStateException> { repository.captureVitalsSync(zone) }
        assertEquals(listOf(w), open(vault).peekProfile("p").vitals.weights)
    }

    @Test fun interruptedReplacementReopensBlockedUntilExplicitRetry() = runTest {
        for (fault in listOf("profile", "reject", "intent-clear")) {
            val vault = FaultVault()
            val (repository, state) = seed(vault)
            val restored = repository.peekProfile("p").copy(vitals = ProfileVitals(weights = listOf(w.copy(kg = 65.0)), lastSync = now))
            vault.fault = fault
            if (fault == "profile") assertFailsWith<CancellationException> { repository.restoreProfileAndVitals(restored) }
            else assertFalse(repository.restoreProfileAndVitals(restored))
            assertIs<VitalsWriteOutcome.Rejected>(repository.commitWeightPage(state, WeightPage(claim, listOf(WeightMutation.Delete("W")))))
            val reopened = open(vault)
            assertFailsWith<IllegalStateException> { reopened.captureVitalsSync(zone) }
            vault.fault = ""
            assertTrue(reopened.restoreProfileAndVitals(restored))
            val saved = reopened.readVitalsState(reopened.captureVitalsSync(zone))
            assertEquals(WeightLocalState(), saved.local)
            assertEquals(ProfileVitals(weights = listOf(w.copy(kg = 65.0))), reopened.peekProfile("p").vitals)
        }
    }

    @Test fun aggregateInterleavingsAndPortableSerializationKeepLocalStatePrivate() = runTest {
        for (persistent in listOf(false, true)) {
            val repository: VezunaRepository = if (persistent) open(FaultVault()) else InMemoryVezunaRepository()
            repository.createProfile(Profile("p", "Synthetic", createdAt = now))
            var state = repository.readVitalsState(repository.captureVitalsSync(zone))
            val weight = WeightPage(claim, listOf(WeightMutation.Upsert(w)), WeightContinuation.Steady(source, "secret-token"))
            val aggregate = AggregateWindow(LocalDate(2026, 10, 30), listOf(StepsDay(LocalDate(2026, 11, 1), 101)), emptyList(), now, zone)
            state = assertNotNull(assertIs<VitalsWriteOutcome.Committed>(repository.commitWeightPage(state, weight)).next)
            val before = state.local
            state = assertNotNull(assertIs<VitalsWriteOutcome.Committed>(repository.commitAggregateWindow(state, aggregate)).next)
            assertEquals(before, state.local)
            assertEquals(listOf(w), repository.peekProfile("p").vitals.weights)
            val clinical = repository.peekProfile("p").vitals
            state = assertNotNull(assertIs<VitalsWriteOutcome.Committed>(repository.commitWeightPage(state, WeightPage(claim, listOf(WeightMutation.Delete("W"))))).next)
            assertEquals(clinical.copy(weights = emptyList()), repository.peekProfile("p").vitals)
            val portable = json.encodeToString(ProfileData.serializer(), repository.peekProfile("p"))
            for (secret in listOf("secret-token", "provenance", "continuation", "commitId", "vitalsFormat")) assertFalse(secret in portable)
            assertEquals(now, state.context.lastSync)
        }
    }

    @Test fun legacyAndFutureGenerationsPreserveLiteralBytesAndClinicalValues() = runTest {
        val vault = GenerationalMemoryVault()
        val clinical = ProfileVitals(weights = listOf(w), samples = listOf(VitalSample("legacy", VitalMetric.WEIGHT_KG, 64.0, now, now)))
        suspend fun encoded(plain: String) = CipherEnvelopeCodec.encode(vault.encrypt(plain.encodeToByteArray(), "Vezuna vitals v1:p".encodeToByteArray()))
        val legacy = encoded(json.encodeToString(ProfileVitals.serializer(), clinical))
        vault.current[key] = legacy
        val store = VitalsStore(vault, json)
        assertEquals(clinical, store.load("p"))
        assertEquals(WeightLocalState(), store.loadStored("p")?.value?.local)
        val future = encoded("""{"vitalsFormat":99,"future":"keep"}""")
        for (previous in listOf(false, true)) {
            vault.current[key] = legacy
            vault.previous.clear()
            if (previous) vault.previous[key] = future else vault.current[key] = future
            val before = vault.current[key]!!.copyOf()
            assertFailsWith<StorageSchemaTooNewException> { store.load("p") }
            assertFailsWith<StorageSchemaTooNewException> { store.save("p", ProfileVitals()) }
            assertFailsWith<StorageSchemaTooNewException> { store.sweep(emptySet()) }
            assertContentEquals(before, vault.current[key])
            assertTrue(vault.quarantined.isEmpty())
        }
        vault.current.remove(key)
        vault.previous[key] = legacy
        val recovered = store.loadStored("p")!!
        assertTrue(recovered.previous)
        assertEquals(clinical, recovered.value.clinical)
        assertEquals(WeightLocalState(), recovered.value.local)
    }
}
