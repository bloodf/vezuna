package app.vezuna.health

import app.vezuna.domain.ProfileVitals
import app.vezuna.domain.VitalMetric
import app.vezuna.domain.WeightReading
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
enum class WeightProvider { HEALTH_CONNECT, HEALTH_KIT }

/** Source-store and origin-policy binding, independent of a cursor or a linked profile's generation. */
@Serializable
data class WeightSource(val provider: WeightProvider, val store: String, val originPolicy: String) {
    init {
        require(store.isNotBlank() && originPolicy.isNotBlank())
    }
}

/**
 * Evidence established at the platform/repository boundary for this exact source binding.
 * [basis] identifies the actual continuity check, not a newly generated local UUID, package name,
 * matching record content, permission grant or cursor. This pure reducer cannot perform that check.
 * When the boundary cannot establish it, it must pass a SourceClaim without evidence.
 */
@Serializable
data class SourceContinuityEvidence(val source: WeightSource, val basis: String) {
    init {
        require(basis.isNotBlank())
    }
}

@Serializable
data class SourceClaim(val source: WeightSource, val continuity: SourceContinuityEvidence? = null) {
    init {
        require(continuity == null || continuity.source == source)
    }
}

/** Device-local progress only. Persist with clinical values, never in portable ProfileVitals/backups. */
@Serializable
sealed interface WeightContinuation {
    val source: WeightSource

    /** [nextFrom] starts the current bounded chunk; [until] is the fixed snapshot upper bound. */
    @Serializable
    data class Snapshot(
        override val source: WeightSource,
        val baselineToken: String,
        val nextFrom: Instant,
        val until: Instant,
        val pageToken: String? = null,
    ) : WeightContinuation {
        init {
            require(baselineToken.isNotBlank() && nextFrom < until)
            require(pageToken == null || pageToken.isNotBlank())
        }
    }

    @Serializable
    data class Replay(override val source: WeightSource, val token: String) : WeightContinuation {
        init { require(token.isNotBlank()) }
    }

    @Serializable
    data class Steady(override val source: WeightSource, val token: String) : WeightContinuation {
        init { require(token.isNotBlank()) }
    }

    @Serializable
    data class RebuildRequired(override val source: WeightSource, val reason: RebuildReason) : WeightContinuation
}

@Serializable
enum class RebuildReason { TOKEN_EXPIRED, UNUSABLE_TOKEN, SOURCE_CHANGED, ACCESS_CHANGED }

@Serializable
enum class UnresolvedWeightReason { UPSERT_COLLISION, UNPROVEN_TOMBSTONE }

@Serializable
data class UnresolvedWeightId(val source: WeightSource, val recordId: String, val reason: UnresolvedWeightReason) {
    init { require(recordId.isNotBlank()) }
}

/** Provenance survives ordinary relink/cursor reset. Restore/replacement must clear this entire state. */
@Serializable
data class WeightLocalState(
    val provenance: Map<String, SourceContinuityEvidence> = emptyMap(),
    val unresolved: Set<UnresolvedWeightId> = emptySet(),
    val continuation: WeightContinuation? = null,
) {
    init {
        require(provenance.keys.all { it.isNotBlank() })
        require(unresolved.size <= MAX_UNRESOLVED_IDS) { "Unresolved weight evidence limit exceeded" }
    }

    companion object {
        const val MAX_UNRESOLVED_IDS = 1024
    }
}

sealed interface WeightMutation {
    data class Upsert(val reading: WeightReading) : WeightMutation
    data class Delete(val recordId: String) : WeightMutation {
        init { require(recordId.isNotBlank()) }
    }
}

/**
 * Mutations are in provider order. Null [nextContinuation] preserves existing progress, including
 * another provider's cursor during a conservative import. Cursor resets are explicit local-state
 * transactions; expiry must discard its payload before constructing a page.
 */
data class WeightPage(
    val claim: SourceClaim,
    val mutations: List<WeightMutation>,
    val nextContinuation: WeightContinuation? = null,
) {
    init {
        require(nextContinuation == null || nextContinuation.source == claim.source)
    }
}

/** Caller must commit both members in one encrypted replacement, under its existing sync fences. */
data class WeightReduction(val vitals: ProfileVitals, val local: WeightLocalState)

/** No absence subtraction, migration, retention or aggregate/lastSync changes. */
object WeightChanges {
    fun reduce(stored: ProfileVitals, local: WeightLocalState, page: WeightPage): WeightReduction {
        val weights = stored.weights.toMutableList()
        val legacyIds = stored.samples.filter { it.metric == VitalMetric.WEIGHT_KG }.map { it.recordId }.toSet()
        val provenance = local.provenance.toMutableMap()
        val unresolved = local.unresolved.toMutableSet()
        val source = page.claim.source
        val continuous = page.claim.continuity != null

        fun recordUnresolved(id: String, reason: UnresolvedWeightReason) {
            unresolved.add(UnresolvedWeightId(source, id, reason))
            require(unresolved.size <= WeightLocalState.MAX_UNRESOLVED_IDS) { "Unresolved weight evidence limit exceeded" }
        }

        fun clearUnresolved(id: String) {
            unresolved.removeAll { it.source == source && it.recordId == id }
        }

        for (mutation in page.mutations) {
            when (mutation) {
                is WeightMutation.Upsert -> {
                    val reading = mutation.reading
                    if (WeightReading.ofOrNull(reading.recordId, reading.time, reading.kg) == null) continue
                    val index = weights.indexOfFirst { it.recordId == reading.recordId }
                    if (reading.recordId in legacyIds || index >= 0 && !(continuous && provenance[reading.recordId]?.source == source)) {
                        recordUnresolved(reading.recordId, UnresolvedWeightReason.UPSERT_COLLISION)
                        continue
                    }
                    if (index < 0) {
                        weights.add(reading)
                        provenance.remove(reading.recordId)
                        page.claim.continuity?.let { provenance[reading.recordId] = it }
                    } else {
                        weights[index] = reading
                    }
                    if (continuous) clearUnresolved(reading.recordId)
                }
                is WeightMutation.Delete -> {
                    if (mutation.recordId !in legacyIds && continuous && provenance[mutation.recordId]?.source == source) {
                        weights.removeAll { it.recordId == mutation.recordId }
                        provenance.remove(mutation.recordId)
                        clearUnresolved(mutation.recordId)
                    } else {
                        recordUnresolved(mutation.recordId, UnresolvedWeightReason.UNPROVEN_TOMBSTONE)
                    }
                }
            }
        }
        return WeightReduction(
            stored.copy(weights = weights.toList()),
            WeightLocalState(provenance.toMap(), unresolved.toSet(), page.nextContinuation ?: local.continuation),
        )
    }
}
