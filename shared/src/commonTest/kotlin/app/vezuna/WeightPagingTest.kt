package app.vezuna

import app.vezuna.data.*
import app.vezuna.domain.*
import app.vezuna.health.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class WeightPagingTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val src = WeightSource(WeightProvider.HEALTH_CONNECT, "binding", "weight-only/all-origins/v1")
    private val w = WeightReading("W", now, 70.0)
    private val claim = SourceClaim(src, SourceContinuityEvidence(src, "provider accepted token"))

    private class Provider : WeightChangesSource {
        val requests = mutableListOf<String>()
        var access = WeightAccess()
        var changes: suspend (String) -> WeightChangesPage = { WeightChangesPage(emptyList(), "T1", false, false) }
        var snapshot: suspend (Instant, Instant, String?) -> WeightSnapshotPage = { _, _, _ -> WeightSnapshotPage(emptyList(), null) }
        override suspend fun read(request: VitalsReadRequest) = VitalsBatch(request.replaceFrom)
        override suspend fun weightAccess() = access
        override fun weightClaim(source: WeightSource) = SourceClaim(source, SourceContinuityEvidence(source, "synthetic accepted provider scope"))
        override suspend fun weightToken(): String { requests += "token"; return "fresh-baseline" }
        override suspend fun weightChanges(token: String): WeightChangesPage { requests += "changes:$token"; return changes(token) }
        override suspend fun weightSnapshot(from: Instant, until: Instant, pageToken: String?, pageSize: Int): WeightSnapshotPage {
            assertEquals(500, pageSize)
            assertTrue(until - from <= 7.days)
            requests += "snapshot:${pageToken ?: "first"}"
            return snapshot(from, until, pageToken)
        }
    }

    private suspend fun seed(progress: WeightContinuation = WeightContinuation.Steady(src, "T0"), access: WeightAccess? = null): Pair<InMemoryVezunaRepository, VitalsReadState> {
        val repository = InMemoryVezunaRepository()
        repository.createProfile(Profile("p", "Synthetic", createdAt = now))
        val initial = repository.readVitalsState(repository.captureVitalsSync(TimeZone.UTC))
        val result = assertIs<VitalsWriteOutcome.Committed>(repository.commitWeightPage(initial,
            WeightPage(claim, listOf(WeightMutation.Upsert(w)), progress, access)))
        return repository to assertNotNull(result.next)
    }

    @Test fun emptyNonterminalChangesAdvancesBeforeNextReadAndEmptyTerminalSavesToken() = runTest {
        val (repository, state) = seed()
        val provider = Provider()
        provider.changes = { token ->
            if (token == "T0") WeightChangesPage(emptyList(), "T1", true, false)
            else {
                val current = repository.readVitalsState(repository.captureVitalsSync(TimeZone.UTC))
                assertEquals("T1", (current.local.continuation as WeightContinuation.Replay).token)
                WeightChangesPage(emptyList(), "T2", false, false)
            }
        }
        WeightPaging(provider, repository, now) { Duration.ZERO }.run(state)
        assertEquals(listOf("changes:T0", "changes:T1"), provider.requests)
        assertEquals("T2", (repository.readVitalsState(repository.captureVitalsSync(TimeZone.UTC)).local.continuation as WeightContinuation.Steady).token)
        assertEquals(listOf(w), repository.peekProfile("p").vitals.weights)
    }

    @Test fun deniedAndRepeatedChangesPreserveWholeCommittedState() = runTest {
        for (deny in listOf(false, true)) {
            val (repository, state) = seed()
            val provider = Provider()
            provider.changes = { if (deny) throw SecurityException("revoked") else WeightChangesPage(listOf(WeightMutation.Delete("W")), "T0", true, false) }
            assertFails { WeightPaging(provider, repository, now) { Duration.ZERO }.run(state) }
            assertEquals(listOf("changes:T0"), provider.requests)
            assertEquals(state, repository.readVitalsState(state.context))
            assertEquals(listOf(w), repository.peekProfile("p").vitals.weights)
        }
    }

    @Test fun blankChangesTokenRecordsRebuildWithoutApplyingTemptingDeletion() = runTest {
        val (repository, state) = seed()
        val provider = Provider()
        provider.changes = { WeightChangesPage(listOf(WeightMutation.Delete("W")), "", false, false) }
        WeightPaging(provider, repository, now) { Duration.ZERO }.run(state)
        val local = repository.readVitalsState(repository.captureVitalsSync(TimeZone.UTC)).local
        assertEquals(WeightContinuation.RebuildRequired(src, RebuildReason.UNUSABLE_TOKEN), local.continuation)
        assertEquals(listOf(w), repository.peekProfile("p").vitals.weights)
        assertEquals(state.local.provenance, local.provenance)
    }

    @Test fun expiryInSnapshotReplayAndSteadyDiscardsPayloadBeforeFreshTokenSnapshot() = runTest {
        val phases = listOf(WeightContinuation.Snapshot(src, "T0", now - 1.days, now), WeightContinuation.Replay(src, "T0"), WeightContinuation.Steady(src, "T0"))
        for (phase in phases) {
            val (repository, state) = seed(phase)
            val provider = Provider()
            provider.changes = { WeightChangesPage(listOf(WeightMutation.Delete("W"), WeightMutation.Upsert(w.copy(kg = 88.0))), "tempting-next", true, true) }
            WeightPaging(provider, repository, now) { if (provider.requests.size >= 2) 11.seconds else Duration.ZERO }.run(state)
            assertEquals(listOf("changes:T0", "token"), provider.requests)
            val saved = repository.readVitalsState(repository.captureVitalsSync(TimeZone.UTC))
            assertEquals("fresh-baseline", (saved.local.continuation as WeightContinuation.Snapshot).baselineToken)
            assertEquals(listOf(w), repository.peekProfile("p").vitals.weights)
            assertEquals(state.local.provenance, saved.local.provenance)
        }
    }

    @Test fun snapshotNullAndEmptyAreTerminalAndAbsenceNeverDeletesExistingWeight() = runTest {
        for (terminal in listOf(null, "")) {
            val (repository, state) = seed(WeightContinuation.Snapshot(src, "T0", now - 1.days, now))
            val provider = Provider()
            provider.snapshot = { _, _, _ -> WeightSnapshotPage(emptyList(), terminal) }
            WeightPaging(provider, repository, now) { Duration.ZERO }.run(state)
            assertEquals(listOf("changes:T0", "snapshot:first", "changes:T0"), provider.requests)
            assertEquals(listOf(w), repository.peekProfile("p").vitals.weights)
            assertIs<WeightContinuation.Steady>(repository.readVitalsState(repository.captureVitalsSync(TimeZone.UTC)).local.continuation)
        }
    }

    @Test fun fixedFirstGrantBoundaryIsNotRollingAndUnknownGrantStaysUnknown() = runTest {
        for (known in listOf(false, true)) {
            val access = WeightAccess(firstGrant = if (known) now - 100.days else null)
            val (repository, state) = seed(WeightContinuation.RebuildRequired(src, RebuildReason.ACCESS_CHANGED))
            val provider = Provider().also { it.access = access }
            val starts = mutableListOf<Instant>()
            provider.snapshot = { from, _, _ -> starts += from; WeightSnapshotPage(emptyList(), null) }
            WeightPaging(provider, repository, now) { Duration.ZERO }.run(state)
            assertEquals(if (known) now - 130.days else Instant.parse("2025-11-01T00:00:00Z"), starts.first())
            assertTrue(starts.first() < now - 30.days)
            assertEquals(access, repository.readVitalsState(repository.captureVitalsSync(TimeZone.UTC)).local.access)
            assertEquals(listOf(w), repository.peekProfile("p").vitals.weights)
            assertTrue(provider.requests.size <= 20)
        }
    }

    @Test fun changesAndSnapshotCyclesStopBeforeRepeatedCursorOrPayloadCommit() = runTest {
        val (repository, state) = seed()
        val provider = Provider()
        provider.changes = { token -> WeightChangesPage(listOf(WeightMutation.Upsert(w.copy(kg = 71.0))), when (token) { "T0" -> "T1"; "T1" -> "T2"; else -> "T0" }, true, false) }
        assertFailsWith<IllegalStateException> { WeightPaging(provider, repository, now) { Duration.ZERO }.run(state) }
        val saved = repository.readVitalsState(repository.captureVitalsSync(TimeZone.UTC))
        assertEquals(WeightContinuation.Replay(src, "T2"), saved.local.continuation)
        assertEquals(3, provider.requests.size)
        assertEquals(3L, saved.revision)

        val (snapshotRepository, snapshotState) = seed(WeightContinuation.Snapshot(src, "T0", now - 1.days, now))
        val snapshotProvider = Provider()
        snapshotProvider.snapshot = { _, _, token -> WeightSnapshotPage(listOf(WeightMutation.Upsert(w.copy(kg = if (token == "B") 99.0 else 71.0))), when (token) { null -> "A"; "A" -> "B"; else -> "A" }) }
        assertFailsWith<IllegalStateException> { WeightPaging(snapshotProvider, snapshotRepository, now) { Duration.ZERO }.run(snapshotState) }
        val snapshotSaved = snapshotRepository.readVitalsState(snapshotRepository.captureVitalsSync(TimeZone.UTC))
        assertEquals("B", (snapshotSaved.local.continuation as WeightContinuation.Snapshot).pageToken)
        assertEquals(listOf(w.copy(kg = 71.0)), snapshotRepository.peekProfile("p").vitals.weights)
        assertEquals(3L, snapshotSaved.revision)
    }

    @Test fun capturedLocalYearBoundaryKeepsMidnightWeightAcrossDstAndPrunesOnlyEarlierValue() = runTest {
        val repository = InMemoryVezunaRepository()
        repository.createProfile(Profile("p", "Synthetic", createdAt = now))
        val zone = TimeZone.of("America/New_York")
        val state = repository.readVitalsState(repository.captureVitalsSync(zone))
        val provider = Provider()
        val floor = Instant.parse("2025-11-01T04:00:00Z")
        provider.snapshot = { from, _, _ ->
            assertEquals(floor, from)
            WeightSnapshotPage(listOf(WeightMutation.Upsert(w.copy(recordId = "in", time = floor)),
                WeightMutation.Upsert(w.copy(recordId = "out", time = Instant.parse("2025-11-01T03:59:59Z")))), null)
        }
        val count = WeightPaging(provider, repository, now) { if (provider.requests.size >= 3) 11.seconds else Duration.ZERO }.run(state)
        assertEquals(1, count)
        assertEquals(listOf(w.copy(recordId = "in", time = floor)), repository.peekProfile("p").vitals.weights)
        assertEquals(setOf("in"), repository.readVitalsState(repository.captureVitalsSync(zone)).local.provenance.keys)
    }

    @Test fun accessChangeAndRelinkBetweenReadAndCommitRejectWithoutCursorAdvance() = runTest {
        for (relink in listOf(false, true)) {
            val (repository, state) = seed()
            repository.createProfile(Profile("q", "Other", createdAt = now + 1.days))
            val provider = Provider()
            provider.changes = {
                if (relink) {
                    repository.setLinkedHealthProfile("q")
                    repository.setLinkedHealthProfile("p")
                } else provider.access = WeightAccess(history = true)
                WeightChangesPage(listOf(WeightMutation.Delete("W")), "T1", true, false)
            }
            assertFails { WeightPaging(provider, repository, now) { Duration.ZERO }.run(state) }
            val latest = repository.readVitalsState(repository.captureVitalsSync(TimeZone.UTC))
            assertEquals(state.local, latest.local)
            assertEquals(listOf(w), repository.peekProfile("p").vitals.weights)
            assertEquals(1, provider.requests.size)
        }
    }
}
