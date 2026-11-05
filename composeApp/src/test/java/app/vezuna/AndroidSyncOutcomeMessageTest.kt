package app.vezuna

import app.vezuna.data.*
import app.vezuna.domain.*
import app.vezuna.health.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.time.Instant
import kotlin.test.*
import org.junit.Test

class AndroidSyncOutcomeMessageTest {
    @Test fun realSyncCannotAnnounceCompleteForUnresolvedDeletion() = runTest {
        val now = Instant.parse("2026-11-01T12:00:00Z")
        val source = WeightSource(WeightProvider.HEALTH_CONNECT, "old-chain", "weight-only/all-origins/v1")
        val weight = WeightReading("W", now, 70.0)
        for (deleted in listOf(false, true)) {
            val repo = InMemoryVezunaRepository()
            repo.createProfile(Profile("p", "Synthetic", createdAt = now))
            assertIs<VitalsWriteOutcome.Committed>(repo.commitWeightPage(repo.readVitalsState(repo.captureVitalsSync(TimeZone.UTC)),
                WeightPage(SourceClaim(source, SourceContinuityEvidence(source, "synthetic prior live chain")),
                    listOf(WeightMutation.Upsert(weight)), WeightContinuation.Steady(source, "T0"))))
            val provider = object : WeightChangesSource {
                override suspend fun read(request: VitalsReadRequest) = VitalsBatch(request.replaceFrom)
                override suspend fun weightAccess() = WeightAccess()
                override suspend fun weightToken(): String = error("must resume")
                override suspend fun weightSnapshot(from: Instant, until: Instant, pageToken: String?, pageSize: Int): WeightSnapshotPage = error("must resume")
                override suspend fun weightChanges(token: String) = WeightChangesPage(
                    if (deleted) listOf(WeightMutation.Delete("W")) else emptyList(), "T1", false, false)
            }
            val message = DeviceVitalsSync(provider, repo, { now }, { TimeZone.UTC }).synchronizeOutcome().toHealthConnectMessage()
            assertEquals(if (deleted) nativeMessage(NativeKey.HEALTH_CONNECT_IMPORT_PARTIAL)
                else nativePlural(NativeKey.HEALTH_CONNECT_IMPORT_COMPLETE, 0), message)
            assertEquals(listOf(weight), repo.peekProfile("p").vitals.weights)
            assertEquals(now, repo.peekProfile("p").vitals.lastSync)
        }
    }

    @Test fun onlyCleanTerminalReceiptAnnouncesCompleteRegardlessOfCount() {
        for (count in listOf(0, 7)) {
            assertEquals(nativePlural(NativeKey.HEALTH_CONNECT_IMPORT_COMPLETE, count),
                VitalsSyncOutcome(count, VitalsSyncStatus.COMPLETE).toHealthConnectMessage())
            for (status in VitalsSyncStatus.entries.filter { it != VitalsSyncStatus.COMPLETE }) {
                val message = VitalsSyncOutcome(count, status).toHealthConnectMessage()
                assertIs<NativeText.Key>(message)
                val expected = when (status) {
                    VitalsSyncStatus.UNRESOLVED, VitalsSyncStatus.BUDGET,
                    VitalsSyncStatus.REBUILD -> NativeKey.HEALTH_CONNECT_IMPORT_PARTIAL
                    VitalsSyncStatus.UNCERTAIN -> NativeKey.HEALTH_CONNECT_IMPORT_UNCERTAIN
                    else -> NativeKey.HEALTH_CONNECT_IMPORT_STOPPED
                }
                assertEquals(expected, message.key)
            }
        }
    }

    @Test fun shippedFailureCopyMustAllowEarlierCommittedPages() {
        for (locale in listOf("values", "values-en", "values-pt", "values-es")) {
            val text = java.io.File("src/main/res/$locale/strings.xml").readText()
            val failure = Regex("<string name=\"health_connect_import_failed\">(.*?)</string>").find(text)!!.groupValues[1]
            assertFalse(failure.contains("previous cursor") || failure.contains("cursor anterior"), locale)
        }
    }
}
