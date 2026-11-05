package app.vezuna

import app.vezuna.domain.HeartRateBucket
import app.vezuna.domain.HeartRateDay
import app.vezuna.domain.ProfileVitals
import app.vezuna.domain.StepsDay
import app.vezuna.domain.VitalDailySummary
import app.vezuna.domain.VitalMetric
import app.vezuna.domain.VitalSample
import app.vezuna.domain.WeightReading
import app.vezuna.health.RebuildReason
import app.vezuna.health.SourceClaim
import app.vezuna.health.SourceContinuityEvidence
import app.vezuna.health.UnresolvedWeightId
import app.vezuna.health.UnresolvedWeightReason
import app.vezuna.health.WeightChanges
import app.vezuna.health.WeightContinuation
import app.vezuna.health.WeightLocalState
import app.vezuna.health.WeightMutation
import app.vezuna.health.WeightPage
import app.vezuna.health.WeightProvider
import app.vezuna.health.WeightSource
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class WeightChangesTest {
    private val time = Instant.parse("2026-10-31T12:00:00Z")
    private val source = WeightSource(WeightProvider.HEALTH_CONNECT, "synthetic-store", "all-origins")
    private val evidence = SourceContinuityEvidence(source, "synthetic boundary established same-store continuity")
    private val claim = SourceClaim(source, evidence)
    private fun weight(id: String, kg: Double = 70.0) = WeightReading(id, time, kg)
    private fun upsert(id: String, kg: Double = 70.0) = WeightMutation.Upsert(weight(id, kg))
    private fun page(vararg mutations: WeightMutation) = WeightPage(claim, mutations.toList())

    @Test
    fun noncollidingWeightAndProgressAreReturnedTogetherWithoutChangingAnyOtherClinicalField() {
        val old = ProfileVitals(
            steps = listOf(StepsDay(LocalDate(2026, 10, 30), 123)),
            heartRateHourly = listOf(HeartRateBucket(time, 60.0, 70.0, 80.0)),
            heartRateDaily = listOf(HeartRateDay(LocalDate(2026, 9, 1), 50.0, 65.0, 80.0, 3)),
            weights = listOf(weight("existing")), lastSync = time,
            samples = listOf(VitalSample("legacy", VitalMetric.WEIGHT_KG, 64.0, time, time)),
            dailySummaries = listOf(VitalDailySummary(VitalMetric.STEPS, LocalDate(2026, 8, 1), 50.0, 1)),
        )
        val next = WeightContinuation.Replay(source, "replay-1")
        val result = WeightChanges.reduce(old, WeightLocalState(), WeightPage(claim, listOf(upsert("new", 71.0)), next))
        assertEquals(old.copy(weights = listOf(weight("existing"), weight("new", 71.0))), result.vitals)
        assertEquals(WeightLocalState(mapOf("new" to evidence), continuation = next), result.local)
    }

    @Test
    fun sourceLabelAndLocalUuidAloneDoNotProveNewImportOrAuthorizeLaterAdoption() {
        val unverified = SourceClaim(source)
        val first = WeightChanges.reduce(ProfileVitals(), WeightLocalState(), WeightPage(unverified, listOf(upsert("w"))))
        assertEquals(listOf(weight("w")), first.vitals.weights)
        assertEquals(emptyMap(), first.local.provenance)
        val second = WeightChanges.reduce(first.vitals, first.local, page(upsert("w")))
        assertEquals(listOf(weight("w")), second.vitals.weights)
        assertEquals(emptyMap(), second.local.provenance)
        assertEquals(setOf(UnresolvedWeightId(source, "w", UnresolvedWeightReason.UPSERT_COLLISION)), second.local.unresolved)
    }

    @Test
    fun unknownCollisionPreservesDifferentOldValueAndDoesNotAdoptEqualReread() {
        val old = ProfileVitals(weights = listOf(weight("w", 65.0)))
        val changed = WeightChanges.reduce(old, WeightLocalState(), page(upsert("w", 80.0)))
        val equal = WeightChanges.reduce(changed.vitals, changed.local, page(upsert("w", 65.0)))
        assertEquals(listOf(weight("w", 65.0)), equal.vitals.weights)
        assertEquals(WeightLocalState(unresolved = setOf(UnresolvedWeightId(source, "w", UnresolvedWeightReason.UPSERT_COLLISION))), equal.local)
    }

    @Test
    fun legacyRawWeightOccupiesItsIdWithoutWholeVitalsMigrationOrProvenanceAdoption() {
        val old = ProfileVitals(samples = listOf(VitalSample("legacy", VitalMetric.WEIGHT_KG, 65.0, time, time)))
        val result = WeightChanges.reduce(old, WeightLocalState(), page(upsert("legacy", 80.0), WeightMutation.Delete("legacy")))
        assertEquals(old, result.vitals)
        assertEquals(WeightLocalState(unresolved = setOf(
            UnresolvedWeightId(source, "legacy", UnresolvedWeightReason.UPSERT_COLLISION),
            UnresolvedWeightId(source, "legacy", UnresolvedWeightReason.UNPROVEN_TOMBSTONE),
        )), result.local)
    }

    @Test
    fun foreignStoreProviderAndOriginPolicyCannotOverwriteOrDeleteProvenWeight() {
        val old = ProfileVitals(weights = listOf(weight("w", 65.0)))
        val local = WeightLocalState(mapOf("w" to evidence))
        val foreignSources = listOf(
            source.copy(store = "another-store"), source.copy(provider = WeightProvider.HEALTH_KIT),
            source.copy(originPolicy = "one-origin"),
        )
        for (foreign in foreignSources) {
            val result = WeightChanges.reduce(old, local, WeightPage(
                SourceClaim(foreign, SourceContinuityEvidence(foreign, "synthetic foreign boundary evidence")),
                listOf(upsert("w", 80.0), WeightMutation.Delete("w")),
            ))
            assertEquals(listOf(weight("w", 65.0)), result.vitals.weights)
            assertEquals(mapOf("w" to evidence), result.local.provenance)
            assertEquals(setOf(
                UnresolvedWeightId(foreign, "w", UnresolvedWeightReason.UPSERT_COLLISION),
                UnresolvedWeightId(foreign, "w", UnresolvedWeightReason.UNPROVEN_TOMBSTONE),
            ), result.local.unresolved)
        }
    }

    @Test
    fun uncertainContinuityPreservesPreviouslyProvenValue() {
        val old = ProfileVitals(weights = listOf(weight("w", 65.0)))
        val local = WeightLocalState(mapOf("w" to evidence))
        val result = WeightChanges.reduce(old, local, WeightPage(SourceClaim(source), listOf(upsert("w", 80.0), WeightMutation.Delete("w"))))
        assertEquals(old, result.vitals)
        assertEquals(mapOf("w" to evidence), result.local.provenance)
        assertEquals(setOf(
            UnresolvedWeightId(source, "w", UnresolvedWeightReason.UPSERT_COLLISION),
            UnresolvedWeightId(source, "w", UnresolvedWeightReason.UNPROVEN_TOMBSTONE),
        ), result.local.unresolved)
    }

    @Test
    fun orderedMutationsUpdateInPlaceDeleteAndRecreateWithLiteralFinalValues() {
        val old = ProfileVitals(weights = listOf(weight("a", 65.0), weight("b", 66.0)))
        val local = WeightLocalState(mapOf("a" to evidence, "b" to evidence))
        val result = WeightChanges.reduce(old, local, page(
            upsert("a", 68.0), WeightMutation.Delete("b"), upsert("b", 80.0),
            upsert("c", 90.0), WeightMutation.Delete("c"), upsert("a", 69.0),
        ))
        assertEquals(listOf(weight("a", 69.0), weight("b", 80.0)), result.vitals.weights)
        assertEquals(WeightLocalState(mapOf("a" to evidence, "b" to evidence)), result.local)
    }

    @Test
    fun unknownAndAbsentTombstonesAreRecordedWithoutChangingValuesAndCanAdvancePage() {
        val old = ProfileVitals(weights = listOf(weight("legacy", 65.0)))
        val next = WeightContinuation.Steady(source, "next-token")
        val result = WeightChanges.reduce(old, WeightLocalState(), WeightPage(claim,
            listOf(WeightMutation.Delete("legacy"), WeightMutation.Delete("absent")), next))
        assertEquals(old, result.vitals)
        assertEquals(WeightLocalState(unresolved = setOf(
            UnresolvedWeightId(source, "legacy", UnresolvedWeightReason.UNPROVEN_TOMBSTONE),
            UnresolvedWeightId(source, "absent", UnresolvedWeightReason.UNPROVEN_TOMBSTONE),
        ), continuation = next), result.local)
    }

    @Test
    fun provenTombstoneWorksAfterCursorResetAndNewSameStoreClaim() {
        val imported = WeightChanges.reduce(ProfileVitals(), WeightLocalState(), WeightPage(claim,
            listOf(upsert("w")), WeightContinuation.Steady(source, "old-token")))
        // Repository relink invalidates request authorization, but retains source evidence.
        val reset = imported.local.copy(continuation = null)
        val relinked = SourceClaim(source, SourceContinuityEvidence(source, "synthetic revalidated same store after relink"))
        val result = WeightChanges.reduce(imported.vitals, reset, WeightPage(relinked, listOf(WeightMutation.Delete("w"))))
        assertEquals(ProfileVitals(), result.vitals)
        assertEquals(WeightLocalState(), result.local)
    }

    @Test
    fun invalidUpsertsPreserveProvenAndUnknownValuesWithoutStrippingProvenance() {
        val old = ProfileVitals(weights = listOf(weight("proven", 65.0), weight("unknown", 66.0)))
        val local = WeightLocalState(mapOf("proven" to evidence))
        val invalid = listOf(0.0, -1.0, 700.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        val mutations = invalid.flatMap { listOf(upsert("proven", it), upsert("unknown", it), upsert("new", it)) } + upsert(" ")
        val result = WeightChanges.reduce(old, local, WeightPage(claim, mutations))
        assertEquals(old, result.vitals)
        assertEquals(local, result.local)
    }

    @Test
    fun validBoundaryWeightsAreAcceptedAndEmptySnapshotNeverSubtractsUnseenValues() {
        val old = ProfileVitals(weights = listOf(WeightReading("ancient", Instant.parse("2020-01-01T00:00:00Z"), 65.0)))
        val result = WeightChanges.reduce(old, WeightLocalState(), page(upsert("low", 1.0), upsert("high", 700.0)))
        val snapshot = WeightContinuation.Snapshot(source, "baseline", Instant.parse("2026-01-01T00:00:00Z"), time)
        val empty = WeightChanges.reduce(result.vitals, result.local, WeightPage(claim, emptyList(), snapshot))
        assertEquals(listOf(old.weights.single(), weight("low", 1.0), weight("high", 700.0)), empty.vitals.weights)
        assertEquals(mapOf("low" to evidence, "high" to evidence), empty.local.provenance)
        assertEquals(snapshot, empty.local.continuation)
    }

    @Test
    fun unresolvedOverflowThrowsWithoutPublishingEarlierMutationsOrProgress() {
        val unresolved = (0 until WeightLocalState.MAX_UNRESOLVED_IDS).map {
            UnresolvedWeightId(source, "unknown-$it", UnresolvedWeightReason.UNPROVEN_TOMBSTONE)
        }.toSet()
        val old = ProfileVitals(weights = listOf(weight("kept", 65.0)))
        val local = WeightLocalState(unresolved = unresolved, continuation = WeightContinuation.Replay(source, "old"))
        assertFailsWith<IllegalArgumentException> {
            WeightChanges.reduce(old, local, WeightPage(claim,
                listOf(upsert("new"), WeightMutation.Delete("overflow")), WeightContinuation.Steady(source, "new")))
        }
        assertEquals(listOf(weight("kept", 65.0)), old.weights)
        assertEquals(emptyMap(), local.provenance)
        assertEquals(unresolved, local.unresolved)
        assertEquals(WeightContinuation.Replay(source, "old"), local.continuation)
        // Repeated evidence at the limit does not consume another slot.
        val repeated = WeightChanges.reduce(old, local, page(WeightMutation.Delete("unknown-0")))
        assertEquals(local, repeated.local)
    }

    @Test
    fun importWithoutContinuationPreservesAnotherProvidersCursorExactly() {
        val cursor = WeightContinuation.Steady(source, "android-sentinel")
        val ios = WeightSource(WeightProvider.HEALTH_KIT, "synthetic-ios-store", "all-origins")
        val result = WeightChanges.reduce(ProfileVitals(), WeightLocalState(continuation = cursor),
            WeightPage(SourceClaim(ios), listOf(upsert("ios"))))
        assertEquals(listOf(weight("ios")), result.vitals.weights)
        assertEquals(WeightLocalState(continuation = cursor), result.local)
    }

    @Test
    fun allContinuationPhasesAndLocalEvidenceRoundTripStrictSerialization() {
        val phases = listOf(
            WeightContinuation.Snapshot(source, "baseline", Instant.parse("2026-01-01T00:00:00Z"), time, "raw-page"),
            WeightContinuation.Replay(source, "replay"), WeightContinuation.Steady(source, "steady"),
            WeightContinuation.RebuildRequired(source, RebuildReason.TOKEN_EXPIRED),
        )
        for (phase in phases) {
            val state = WeightLocalState(mapOf("w" to evidence), setOf(UnresolvedWeightId(source, "u", UnresolvedWeightReason.UNPROVEN_TOMBSTONE)), phase)
            val encoded = Json.encodeToString(WeightLocalState.serializer(), state)
            assertEquals(state, Json.decodeFromString(WeightLocalState.serializer(), encoded))
        }
        assertEquals(claim, Json.decodeFromString(SourceClaim.serializer(), Json.encodeToString(SourceClaim.serializer(), claim)))
        assertEquals(WeightLocalState(), Json.decodeFromString(WeightLocalState.serializer(), "{}"))
    }

    @Test
    fun invalidClaimsTokensAndSnapshotBoundsRejectAtConstruction() {
        val other = source.copy(store = "foreign")
        assertFailsWith<IllegalArgumentException> { SourceClaim(other, evidence) }
        assertFailsWith<IllegalArgumentException> { SourceContinuityEvidence(source, " ") }
        assertFailsWith<IllegalArgumentException> { WeightContinuation.Replay(source, "") }
        assertFailsWith<IllegalArgumentException> { WeightContinuation.Steady(source, " ") }
        assertFailsWith<IllegalArgumentException> { WeightContinuation.Snapshot(source, "baseline", time, time) }
        assertFailsWith<IllegalArgumentException> { WeightContinuation.Snapshot(source, "baseline", Instant.parse("2026-01-01T00:00:00Z"), time, "") }
        assertFailsWith<IllegalArgumentException> { WeightPage(claim, emptyList(), WeightContinuation.Replay(other, "token")) }
        assertFailsWith<IllegalArgumentException> { WeightMutation.Delete(" ") }
        assertNull(SourceClaim(source).continuity)
    }
}
