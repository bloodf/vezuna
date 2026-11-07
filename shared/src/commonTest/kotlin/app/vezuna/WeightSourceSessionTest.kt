package app.vezuna

import app.vezuna.domain.*
import app.vezuna.health.*
import kotlin.test.*
import kotlin.time.Instant

class WeightSourceSessionTest {
    private val w = WeightReading("W", Instant.parse("2026-11-01T12:00:00Z"), 70.0)

    @Test fun sameLiveTargetProvesNewWeightAndTombstoneAfterCursorReset() {
        val session = WeightSourceSession().also { it.bindTarget("a") }
        val source = session.baselineAcquired("provider-token")
        val claim = session.claim(source)
        assertNotNull(claim.continuity)
        val imported = WeightChanges.reduce(ProfileVitals(), WeightLocalState(), WeightPage(claim, listOf(WeightMutation.Upsert(w))))
        session.bindTarget("a")
        val reset = imported.local.copy(continuation = WeightContinuation.RebuildRequired(source, RebuildReason.TOKEN_EXPIRED))
        val deleted = WeightChanges.reduce(imported.vitals, reset, WeightPage(session.claim(source), listOf(WeightMutation.Delete("W"))))
        assertEquals(emptyList(), deleted.vitals.weights)
        assertEquals(emptyMap(), deleted.local.provenance)
    }

    @Test fun coldRestoredAndReusedTokenCannotAdoptOrDeleteButPositiveImportContinues() {
        val original = WeightSourceSession().also { it.bindTarget("a") }
        val source = original.baselineAcquired("same-provider-token")
        val imported = WeightChanges.reduce(ProfileVitals(), WeightLocalState(), WeightPage(original.claim(source), listOf(WeightMutation.Upsert(w))))
        val cold = WeightSourceSession().also { it.bindTarget("a") }
        assertNull(cold.claim(source).continuity)
        val reused = cold.baselineAcquired("same-provider-token")
        assertNotEquals(source, reused)
        val ambiguous = WeightChanges.reduce(imported.vitals, imported.local,
            WeightPage(cold.claim(source), listOf(WeightMutation.Delete("W"), WeightMutation.Upsert(w.copy(recordId = "NEW")))))
        assertEquals(listOf(w, w.copy(recordId = "NEW")), ambiguous.vitals.weights)
        assertEquals(imported.local.provenance, ambiguous.local.provenance)
        assertEquals(setOf(UnresolvedWeightId(source, "W", UnresolvedWeightReason.UNPROVEN_TOMBSTONE)), ambiguous.local.unresolved)
        val reusedDelete = WeightChanges.reduce(imported.vitals, imported.local, WeightPage(cold.claim(reused), listOf(WeightMutation.Delete("W"))))
        assertEquals(listOf(w), reusedDelete.vitals.weights)
        // A UUID-shaped scope or package-shaped scope alone is never evidence.
        assertNull(cold.claim(source.copy(store = "app.vezuna")).continuity)
    }

    @Test fun targetRoundTripDiscardsLiveEvidenceAndDurableProvenanceNeverAuthorizes() {
        val session = WeightSourceSession().also { it.bindTarget("a") }
        val source = session.baselineAcquired("T0")
        val imported = WeightChanges.reduce(ProfileVitals(), WeightLocalState(), WeightPage(session.claim(source), listOf(WeightMutation.Upsert(w))))
        session.bindTarget("b")
        session.bindTarget("a")
        assertNull(session.claim(source).continuity)
        val result = WeightChanges.reduce(imported.vitals, imported.local, WeightPage(session.claim(source), listOf(WeightMutation.Delete("W"))))
        assertEquals(listOf(w), result.vitals.weights)
        assertEquals(imported.local.provenance, result.local.provenance)
    }
}
