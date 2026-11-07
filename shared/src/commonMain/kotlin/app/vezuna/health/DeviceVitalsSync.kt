package app.vezuna.health

import app.vezuna.data.VitalsSyncContext
import app.vezuna.data.VezunaRepository
import app.vezuna.domain.VitalsBatch
import app.vezuna.domain.VitalsWindow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/** [replaceFrom] is the first local day to re-read (steps, heart rate, weight); weights are also read back to [weightsFrom]. */
data class VitalsReadRequest(val replaceFrom: LocalDate, val weightsFrom: LocalDate, val zone: TimeZone, val now: Instant? = null)

interface DeviceVitalsSource {
    suspend fun read(request: VitalsReadRequest): VitalsBatch
}

/** Counts acknowledged writes, including earlier pages when a later operation stops. */
data class VitalsSyncOutcome(val storedCount: Int, val status: VitalsSyncStatus)

/** COMPLETE covers current available pages with no unresolved IDs, not inaccessible historical data. */
enum class VitalsSyncStatus { COMPLETE, UNRESOLVED, BUDGET, REBUILD, REJECTED, UNCERTAIN, BLOCKED }

/** Re-reads aggregate windows and, for paged sources, acknowledges each weight page before continuing. */
class DeviceVitalsSync(
    private val source: DeviceVitalsSource,
    private val repository: VezunaRepository,
    private val clock: () -> Instant,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val beforeSync: suspend (VitalsSyncContext) -> VitalsSyncContext = { it },
) {
    private val mutex = Mutex()

    /** Count-only compatibility for existing nonpaging callers. Status consumers use [synchronizeOutcome]. */
    suspend fun synchronize(expected: VitalsSyncContext? = null): Int = synchronizeOutcome(expected).storedCount

    suspend fun synchronizeOutcome(expected: VitalsSyncContext? = null): VitalsSyncOutcome = mutex.withLock {
        currentCoroutineContext().ensureActive()
        val current = repository.captureVitalsSync(expected?.zone ?: zone())
        check(expected == null || current.profileId == expected.profileId && current.generation == expected.generation) { "Device vitals sync is stale" }
        val context = beforeSync(expected ?: current)
        currentCoroutineContext().ensureActive()
        repository.withValidatedVitalsSync(context) {}
        val now = clock()
        val lastSync = context.lastSync
        val tz = context.zone
        val batch = source.read(VitalsReadRequest(VitalsWindow.replaceFrom(lastSync, now, tz), VitalsWindow.weightsFrom(lastSync, now, tz), tz, now))
        currentCoroutineContext().ensureActive()
        if (source is WeightChangesSource) {
            check(batch.weights.isEmpty() && batch.deletedWeightIds.isEmpty()) { "Aggregate payload contained weight" }
            val state = repository.readVitalsState(context)
            val aggregate = repository.commitAggregateWindow(state,
                app.vezuna.domain.AggregateWindow(batch.replaceFrom, batch.steps, batch.heartRate, now, tz))
            when (aggregate) {
                is VitalsWriteOutcome.Rejected -> return@withLock VitalsSyncOutcome(0, VitalsSyncStatus.REJECTED)
                is VitalsWriteOutcome.Indeterminate -> return@withLock VitalsSyncOutcome(0, VitalsSyncStatus.UNCERTAIN)
                is VitalsWriteOutcome.Committed -> if (aggregate.next == null)
                    return@withLock VitalsSyncOutcome(aggregate.storedCount, VitalsSyncStatus.BLOCKED)
            }
            val started = kotlin.time.TimeSource.Monotonic.markNow()
            val weights = WeightPaging(source, repository, now) { started.elapsedNow() }.run(aggregate.next)
            weights.copy(storedCount = aggregate.storedCount + weights.storedCount)
        } else VitalsSyncOutcome(repository.ingestDeviceVitals(context, batch, now), VitalsSyncStatus.COMPLETE)
    }
}

/** Separate aggregate and weight requests. Every page is bounded and acknowledged before the next read. */
interface WeightChangesSource : DeviceVitalsSource {
    suspend fun weightAccess(): WeightAccess
    fun bindWeightTarget(profileId: String) {}
    fun weightSource(token: String): WeightSource = WeightSource(WeightProvider.HEALTH_CONNECT, token, "weight-only/all-origins/v1")
    fun weightClaim(source: WeightSource): SourceClaim = SourceClaim(source)
    suspend fun weightToken(): String
    suspend fun weightSnapshot(from: Instant, until: Instant, pageToken: String?, pageSize: Int): WeightSnapshotPage
    suspend fun weightChanges(token: String): WeightChangesPage
}

data class WeightSnapshotPage(val mutations: List<WeightMutation.Upsert>, val nextPageToken: String?)
data class WeightChangesPage(val mutations: List<WeightMutation>, val nextToken: String, val hasMore: Boolean, val expired: Boolean)

internal class WeightPaging(
    private val source: WeightChangesSource,
    private val repository: VezunaRepository,
    private val now: Instant,
    private val elapsed: () -> kotlin.time.Duration,
) {
    suspend fun run(initial: VitalsReadState): VitalsSyncOutcome {
        var state = initial
        source.bindWeightTarget(state.context.profileId)
        var calls = 0
        var rebuilt = false
        var storedCount = 0
        var stopped = VitalsSyncStatus.BLOCKED
        fun outcome(status: VitalsSyncStatus) = VitalsSyncOutcome(storedCount, status)
        fun terminal() = outcome(if (state.local.unresolved.isNotEmpty()) VitalsSyncStatus.UNRESOLVED else VitalsSyncStatus.COMPLETE)
        val access = source.weightAccess()
        val floor = VitalsWindow.weightsFrom(null, now, state.context.zone).atStartOfDayIn(state.context.zone)
        val seenChanges = mutableSetOf<String>()
        val seenSnapshot = mutableMapOf<Instant, MutableSet<String>>()
        suspend fun validate() {
            currentCoroutineContext().ensureActive()
            repository.readVitalsState(state.context)
            check(source.weightAccess() == access) { "Health access changed" }
        }
        suspend fun permit() {
            validate()
            check(calls < 20 && elapsed() < kotlin.time.Duration.parse("10s")) { "Weight paging budget exhausted" }
            calls++
        }
        suspend fun commit(page: WeightPage): Boolean {
            validate()
            return when (val result = repository.commitWeightPage(state, page.copy(access = access, retentionFrom = if (page.mutations.isNotEmpty()) floor else null))) {
                is VitalsWriteOutcome.Committed -> {
                    storedCount += result.storedCount
                    val next = result.next ?: run { stopped = VitalsSyncStatus.BLOCKED; return false }
                    state = next
                    true
                }
                is VitalsWriteOutcome.Rejected -> { stopped = VitalsSyncStatus.REJECTED; false }
                is VitalsWriteOutcome.Indeterminate -> { stopped = VitalsSyncStatus.UNCERTAIN; false }
            }
        }
        suspend fun start(): Boolean {
            seenChanges.clear()
            seenSnapshot.clear()
            permit()
            val token = source.weightToken()
            check(token.isNotBlank()) { "Unusable baseline token" }
            // A new observation chain cannot adopt any existing provenance, even if the provider reuses a token.
            val src = source.weightSource(token)
            val grantFloor = if (!access.history) access.firstGrant?.minus(kotlin.time.Duration.parse("30d")) else null
            val from = maxOf(floor, grantFloor ?: floor)
            val continuation = if (from < now) WeightContinuation.Snapshot(src, token, from, now)
                else WeightContinuation.Replay(src, token)
            return commit(WeightPage(SourceClaim(src), emptyList(), continuation))
        }
        if (state.local.access != null && state.local.access != access || state.local.continuation == null || state.local.continuation is WeightContinuation.RebuildRequired) {
            if (!start()) return outcome(stopped)
        }
        while (calls < 20 && elapsed() < kotlin.time.Duration.parse("10s")) {
            when (val progress = state.local.continuation ?: return outcome(VitalsSyncStatus.REBUILD)) {
                is WeightContinuation.Snapshot -> {
                    // A restarted/long snapshot's baseline may expire. Inspect flags before using any payload.
                    permit()
                    val verification = source.weightChanges(progress.baselineToken)
                    if (verification.expired) {
                        if (!commit(WeightPage(SourceClaim(progress.source), emptyList(), WeightContinuation.RebuildRequired(progress.source, RebuildReason.TOKEN_EXPIRED)))) return outcome(stopped)
                        if (rebuilt) return outcome(VitalsSyncStatus.REBUILD)
                        rebuilt = true
                        if (!start()) return outcome(stopped)
                        continue
                    }
                    if (calls >= 20 || elapsed() >= kotlin.time.Duration.parse("10s")) return outcome(VitalsSyncStatus.BUDGET)
                    val end = minOf(progress.nextFrom + kotlin.time.Duration.parse("7d"), progress.until)
                    val seen = seenSnapshot.getOrPut(progress.nextFrom) { mutableSetOf() }
                    progress.pageToken?.let { seen += it }
                    permit()
                    val page = source.weightSnapshot(progress.nextFrom, end, progress.pageToken, 500)
                    val token = page.nextPageToken?.takeUnless { it.isBlank() }
                    check(token == null || token !in seen) { "Repeated snapshot token" }
                    val next = if (token != null) progress.copy(pageToken = token)
                        else if (end < progress.until) progress.copy(nextFrom = end, pageToken = null)
                        else WeightContinuation.Replay(progress.source, progress.baselineToken)
                    val claim = source.weightClaim(progress.source)
                    if (!commit(WeightPage(claim, page.mutations, next))) return outcome(stopped)
                }
                is WeightContinuation.Replay, is WeightContinuation.Steady -> {
                    val token = when (progress) {
                        is WeightContinuation.Replay -> progress.token
                        is WeightContinuation.Steady -> progress.token
                        else -> error("unreachable")
                    }
                    seenChanges += token
                    permit()
                    val page = source.weightChanges(token)
                    if (page.expired) {
                        if (!commit(WeightPage(SourceClaim(progress.source), emptyList(), WeightContinuation.RebuildRequired(progress.source, RebuildReason.TOKEN_EXPIRED)))) return outcome(stopped)
                        if (rebuilt) return outcome(VitalsSyncStatus.REBUILD)
                        rebuilt = true
                        if (!start()) return outcome(stopped)
                        continue
                    }
                    if (page.nextToken.isBlank()) {
                        if (!commit(WeightPage(SourceClaim(progress.source), emptyList(), WeightContinuation.RebuildRequired(progress.source, RebuildReason.UNUSABLE_TOKEN)))) return outcome(stopped)
                        return outcome(VitalsSyncStatus.REBUILD)
                    }
                    check(!page.hasMore || page.nextToken !in seenChanges) { "Repeated Changes token" }
                    val next = if (page.hasMore) WeightContinuation.Replay(progress.source, page.nextToken) else WeightContinuation.Steady(progress.source, page.nextToken)
                    val claim = source.weightClaim(progress.source)
                    if (!commit(WeightPage(claim, page.mutations, next))) return outcome(stopped)
                    if (!page.hasMore) return terminal()
                }
                is WeightContinuation.RebuildRequired -> return outcome(VitalsSyncStatus.REBUILD)
            }
        }
        return outcome(VitalsSyncStatus.BUDGET)
    }
}
