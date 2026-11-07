package app.vezuna

import app.vezuna.data.*
import app.vezuna.domain.*
import app.vezuna.health.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.*
import kotlin.time.Instant

class DeviceVitalsSyncOutcomeTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val src = WeightSource(WeightProvider.HEALTH_CONNECT, "old-live-chain", "weight-only/all-origins/v1")
    private val w = WeightReading("W", now, 70.0)
    private val steps = listOf(StepsDay(LocalDate(2026, 11, 1), 123))
    private val claim = SourceClaim(src, SourceContinuityEvidence(src, "synthetic live chain"))

    private class Provider : WeightChangesSource {
        var calls = 0
        var continuous = false
        var changes: (String) -> WeightChangesPage = { WeightChangesPage(emptyList(), "T1", false, false) }
        var snapshot = emptyList<WeightMutation.Upsert>()
        override suspend fun read(request: VitalsReadRequest) = VitalsBatch(request.replaceFrom,
            steps = listOf(StepsDay(LocalDate(2026, 11, 1), 123)))
        override suspend fun weightAccess() = WeightAccess(history = true)
        override suspend fun weightToken(): String { calls++; return "baseline" }
        override fun weightSource(token: String) = WeightSource(WeightProvider.HEALTH_CONNECT, "new-chain", "weight-only/all-origins/v1")
        override fun weightClaim(source: WeightSource) = SourceClaim(source,
            if (continuous) SourceContinuityEvidence(source, "synthetic live chain") else null)
        override suspend fun weightSnapshot(from: Instant, until: Instant, pageToken: String?, pageSize: Int): WeightSnapshotPage {
            calls++; return WeightSnapshotPage(snapshot, null)
        }
        override suspend fun weightChanges(token: String): WeightChangesPage { calls++; return changes(token) }
    }

    private suspend fun seed(): InMemoryVezunaRepository = InMemoryVezunaRepository().also { repo ->
        repo.createProfile(Profile("p", "Synthetic", createdAt = now))
        val state = repo.readVitalsState(repo.captureVitalsSync(TimeZone.UTC))
        assertIs<VitalsWriteOutcome.Committed>(repo.commitWeightPage(state, WeightPage(claim,
            listOf(WeightMutation.Upsert(w)), WeightContinuation.Steady(src, "T0"), WeightAccess(history = true))))
    }
    private suspend fun local(repo: VezunaRepository) = repo.readVitalsState(repo.captureVitalsSync(TimeZone.UTC)).local
    private suspend fun execute(provider: Provider, repo: VezunaRepository) =
        DeviceVitalsSync(provider, repo, { now }, { TimeZone.UTC }).synchronizeOutcome()
    private suspend fun outcome(provider: Provider, repo: VezunaRepository) = execute(provider, repo).status.name

    @Test fun cleanTerminalControlPreservesWeightAndAdvancesAggregateTimestamp() = runTest {
        val repo = seed()
        assertEquals("COMPLETE", outcome(Provider(), repo))
        assertEquals(listOf(w), repo.peekProfile("p").vitals.weights)
        assertEquals(steps, repo.peekProfile("p").vitals.steps)
        assertEquals(now, repo.peekProfile("p").vitals.lastSync)
        assertEquals(WeightContinuation.Steady(src, "T1"), local(repo).continuation)
        assertEquals(emptySet(), local(repo).unresolved)
    }

    @Test fun coldChainDeleteAndCollisionKeepClinicalValueButCannotComplete() = runTest {
        for ((mutation, reason) in listOf(WeightMutation.Delete("W") to UnresolvedWeightReason.UNPROVEN_TOMBSTONE,
            WeightMutation.Upsert(w.copy(kg = 99.0)) to UnresolvedWeightReason.UPSERT_COLLISION)) {
            val repo = seed()
            val provider = Provider().also { it.changes = { WeightChangesPage(listOf(mutation), "T1", false, false) } }
            val result = outcome(provider, repo)
            assertEquals(listOf(w), repo.peekProfile("p").vitals.weights)
            assertEquals(steps, repo.peekProfile("p").vitals.steps)
            assertEquals(now, repo.peekProfile("p").vitals.lastSync)
            assertEquals(setOf(UnresolvedWeightId(src, "W", reason)), local(repo).unresolved)
            assertEquals(WeightContinuation.Steady(src, "T1"), local(repo).continuation)
            assertEquals("UNRESOLVED", result)
        }
    }

    @Test fun restoredWeightBaselineCollisionRemainsPartial() = runTest {
        val repo = seed()
        assertTrue(repo.restoreProfileAndVitals(repo.peekProfile("p")))
        assertEquals(null, repo.peekProfile("p").vitals.lastSync)
        assertEquals(WeightLocalState(), local(repo))
        val provider = Provider().also { it.continuous = true; it.snapshot = listOf(WeightMutation.Upsert(w.copy(kg = 99.0))) }
        val result = outcome(provider, repo)
        assertEquals(listOf(w), repo.peekProfile("p").vitals.weights)
        assertEquals(now, repo.peekProfile("p").vitals.lastSync)
        assertTrue(local(repo).unresolved.any { it.recordId == "W" && it.reason == UnresolvedWeightReason.UPSERT_COLLISION })
        assertIs<WeightContinuation.Snapshot>(local(repo).continuation)
        assertEquals(20, provider.calls)
        assertEquals("BUDGET", result)
        var resumed = result
        repeat(10) { if (resumed == "BUDGET") resumed = outcome(provider, repo) }
        assertEquals("UNRESOLVED", resumed)
        assertIs<WeightContinuation.Steady>(local(repo).continuation)
        assertEquals(listOf(w), repo.peekProfile("p").vitals.weights)
    }

    @Test fun resolvedEvidenceCanCompleteDespiteHistoricalUnionCoverage() = runTest {
        val repo = seed()
        val provider = Provider().also { it.changes = { WeightChangesPage(listOf(WeightMutation.Delete("W")), "T1", false, false) } }
        outcome(provider, repo)
        provider.continuous = true
        provider.changes = { WeightChangesPage(listOf(WeightMutation.Upsert(w.copy(kg = 71.0))), "T2", false, false) }
        assertEquals("COMPLETE", outcome(provider, repo))
        assertEquals(listOf(w.copy(kg = 71.0)), repo.peekProfile("p").vitals.weights)
        assertEquals(emptySet(), local(repo).unresolved)
        assertTrue(WeightCoverageLimit.UNRESOLVED_IDS in local(repo).coverage)
        assertTrue(WeightCoverageLimit.SOURCE_UNCERTAIN in local(repo).coverage)
        assertEquals(WeightContinuation.Steady(src, "T2"), local(repo).continuation)
    }

    @Test fun pageBudgetAndRebuildStopWithoutFalseComplete() = runTest {
        for (kind in listOf("BUDGET", "REBUILD")) {
            val repo = seed()
            val provider = Provider().also { p -> p.changes = { token ->
                if (kind == "BUDGET") WeightChangesPage(emptyList(), token + "x", true, false)
                else WeightChangesPage(listOf(WeightMutation.Delete("W")), "", false, false)
            } }
            val result = outcome(provider, repo)
            assertEquals(listOf(w), repo.peekProfile("p").vitals.weights)
            assertEquals(now, repo.peekProfile("p").vitals.lastSync)
            if (kind == "BUDGET") {
                assertEquals(20, provider.calls)
                assertEquals(WeightContinuation.Replay(src, "T0" + "x".repeat(20)), local(repo).continuation)
            } else assertEquals(WeightContinuation.RebuildRequired(src, RebuildReason.UNUSABLE_TOKEN), local(repo).continuation)
            assertEquals(kind, result)
        }
    }

    private class Controlled(private val repo: VezunaRepository, val phase: String, val failure: String) : VezunaRepository by repo {
        var pages = 0
        private suspend fun write(real: suspend () -> VitalsWriteOutcome): VitalsWriteOutcome = when (failure) {
            "REJECTED" -> VitalsWriteOutcome.Rejected("synthetic prewrite rejection")
            "UNCERTAIN" -> VitalsWriteOutcome.Indeterminate("synthetic uncertain receipt")
            else -> assertIs<VitalsWriteOutcome.Committed>(real()).copy(next = null)
        }
        override suspend fun commitAggregateWindow(state: VitalsReadState, window: AggregateWindow) =
            if (phase == "aggregate") write { repo.commitAggregateWindow(state, window) } else repo.commitAggregateWindow(state, window)
        override suspend fun commitWeightPage(state: VitalsReadState, page: WeightPage): VitalsWriteOutcome {
            pages++
            return if (phase == "page" || phase == "later" && pages == 2) write { repo.commitWeightPage(state, page) }
                else repo.commitWeightPage(state, page)
        }
    }

    @Test fun aggregateAndPageReceiptsKeepRejectedUncertainAndBlockedDistinct() = runTest {
        for (phase in listOf("aggregate", "page", "later")) for (failure in listOf("REJECTED", "UNCERTAIN", "BLOCKED")) {
            val repo = seed()
            val controlled = Controlled(repo, phase, failure)
            val provider = Provider().also { it.continuous = true; it.changes = { token ->
                WeightChangesPage(listOf(WeightMutation.Upsert(w.copy(kg = if (token == "T0") 71.0 else 72.0))), token + "x", true, false)
            } }
            val result = execute(provider, controlled)
            val expectedCount = when (phase) { "aggregate" -> 0; "page" -> 1; else -> 2 } + if (failure == "BLOCKED") 1 else 0
            assertEquals(expectedCount, result.storedCount, "$phase/$failure acknowledged count")
            val acceptedPage = phase != "aggregate" && (phase == "later" || failure == "BLOCKED")
            val kg = if (phase == "later" && failure == "BLOCKED") 72.0 else if (acceptedPage) 71.0 else 70.0
            assertEquals(listOf(w.copy(kg = kg)), repo.peekProfile("p").vitals.weights)
            assertEquals(if (phase == "aggregate" && failure != "BLOCKED") null else now, repo.peekProfile("p").vitals.lastSync)
            assertEquals(if (phase == "aggregate") 0 else if (phase == "later") 2 else 1, provider.calls)
            val token = if (phase == "later" && failure == "BLOCKED") "T0xx" else if (acceptedPage) "T0x" else "T0"
            assertEquals(token, when (val c = local(repo).continuation) { is WeightContinuation.Replay -> c.token; is WeightContinuation.Steady -> c.token; else -> error("unexpected") })
            assertEquals(failure, result.status.name, "$phase/$failure")
        }
    }

    @Test fun unresolvedOverflowRejectsPageAfterEarlierDurableProgress() = runTest {
        val repo = seed()
        val provider = Provider().also { it.continuous = true; it.changes = { token ->
            if (token == "T0") WeightChangesPage(listOf(WeightMutation.Upsert(w.copy(kg = 71.0))), "T1", true, false)
            else WeightChangesPage((0..1024).map { WeightMutation.Delete("unknown-$it") }, "T2", false, false)
        } }
        val result = execute(provider, repo)
        assertEquals(listOf(w.copy(kg = 71.0)), repo.peekProfile("p").vitals.weights)
        assertEquals(WeightContinuation.Replay(src, "T1"), local(repo).continuation)
        assertEquals(emptySet(), local(repo).unresolved)
        assertEquals(now, repo.peekProfile("p").vitals.lastSync)
        assertEquals(2, result.storedCount)
        assertEquals(VitalsSyncStatus.REJECTED, result.status)
    }

    @Test fun repeatedExpiryDiscardsPayloadAndReportsCurrentRebuild() = runTest {
        val repo = seed()
        val provider = Provider().also { it.changes = {
            WeightChangesPage(listOf(WeightMutation.Delete("W"), WeightMutation.Upsert(w.copy(kg = 99.0))), "tempting", true, true)
        } }
        assertEquals("REBUILD", outcome(provider, repo))
        assertEquals(listOf(w), repo.peekProfile("p").vitals.weights)
        assertEquals(steps, repo.peekProfile("p").vitals.steps)
        assertEquals(now, repo.peekProfile("p").vitals.lastSync)
        assertEquals(emptySet(), local(repo).unresolved)
        assertEquals(WeightContinuation.RebuildRequired(provider.weightSource("baseline"), RebuildReason.TOKEN_EXPIRED), local(repo).continuation)
        assertEquals(3, provider.calls)
    }

    @Test fun providerFailureAfterCommittedPageKeepsPartialDurableProgress() = runTest {
        val repo = seed()
        val provider = Provider().also { it.continuous = true; it.changes = { token ->
            if (token != "T0") throw TestAccessDeniedException("synthetic access revoked")
            WeightChangesPage(listOf(WeightMutation.Upsert(w.copy(kg = 71.0))), "T1", true, false)
        } }
        assertFailsWith<TestAccessDeniedException> { execute(provider, repo) }
        assertEquals(listOf(w.copy(kg = 71.0)), repo.peekProfile("p").vitals.weights)
        assertEquals(WeightContinuation.Replay(src, "T1"), local(repo).continuation)
        assertEquals(steps, repo.peekProfile("p").vitals.steps)
        assertEquals(now, repo.peekProfile("p").vitals.lastSync)
    }

    private class FaultVault(val memory: GenerationalMemoryVault = GenerationalMemoryVault()) : app.vezuna.security.SecureVault by memory {
        var failAt = 0
        var writes = 0
        var fault = ""
        var before: ByteArray? = null
        override suspend fun writeAcknowledged(key: String, ciphertext: ByteArray): app.vezuna.security.VaultWriteResult {
            if (key == "repository-vitals-p-v1" && ++writes == failAt) {
                before = memory.current.getValue(key).copyOf()
                if (fault == "reject") return app.vezuna.security.VaultWriteResult.Rejected(IllegalStateException("prewrite"))
                if (fault == "after") memory.write(key, ciphertext)
                return app.vezuna.security.VaultWriteResult.Indeterminate(IllegalStateException("uncertain durability"))
            }
            return memory.writeAcknowledged(key, ciphertext)
        }
    }

    @Test fun realVaultReceiptsFenceUncertaintyAndKeepWholeClinicalCursorGenerations() = runTest {
        for (phase in 1..3) for (fault in listOf("reject", "before", "after")) {
            val vault = FaultVault()
            val repo = VaultBackedRepository(vault).also { it.load(); it.createProfile(Profile("p", "Synthetic", createdAt = now)) }
            assertIs<VitalsWriteOutcome.Committed>(repo.commitWeightPage(repo.readVitalsState(repo.captureVitalsSync(TimeZone.UTC)),
                WeightPage(claim, listOf(WeightMutation.Upsert(w)), WeightContinuation.Steady(src, "T0"), WeightAccess(history = true))))
            vault.writes = 0
            vault.failAt = phase
            vault.fault = fault
            val provider = Provider().also { it.continuous = true; it.changes = { token ->
                WeightChangesPage(listOf(WeightMutation.Upsert(w.copy(kg = if (token == "T0") 71.0 else 72.0))), token + "x", true, false)
            } }
            val result = execute(provider, repo)
            assertEquals(if (fault == "reject") VitalsSyncStatus.REJECTED else VitalsSyncStatus.UNCERTAIN, result.status)
            assertEquals(phase - 1, result.storedCount)
            assertEquals(phase - 1, provider.calls)
            val bytes = vault.memory.current.getValue("repository-vitals-p-v1").copyOf()
            if (fault != "after") assertContentEquals(vault.before, bytes)
            else assertFalse(vault.before!!.contentEquals(bytes))
            if (fault != "reject") {
                assertFailsWith<IllegalStateException> { repo.peekProfile("p") }
                assertFailsWith<IllegalStateException> { repo.readProfile("p") }
                assertFailsWith<IllegalStateException> { repo.captureVitalsSync(TimeZone.UTC) }
            }
            val reopened = VaultBackedRepository(vault).also { it.load() }
            val clinical = reopened.peekProfile("p").vitals
            val committed = phase - 1 + if (fault == "after") 1 else 0
            assertEquals(listOf(w.copy(kg = when (committed) { 3 -> 72.0; 2 -> 71.0; else -> 70.0 })), clinical.weights)
            assertEquals(if (committed == 0) null else now, clinical.lastSync)
            assertEquals(if (committed == 0) emptyList() else steps, clinical.steps)
            val saved = local(reopened)
            val token = "T0" + "x".repeat(maxOf(0, committed - 1))
            assertEquals(token, when (val c = saved.continuation) { is WeightContinuation.Replay -> c.token; is WeightContinuation.Steady -> c.token; else -> error("unexpected") })
            assertEquals(emptySet(), saved.unresolved)
            assertContentEquals(bytes, vault.memory.current.getValue("repository-vitals-p-v1"))
        }
    }

}
