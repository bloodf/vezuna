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
    @Test fun uncertainAggregateAndWeightReceiptsPublishWithoutReadingFencedProfile() = runTest {
        val now = Instant.parse("2026-11-01T12:00:00Z")
        for (failAt in listOf(1, 2)) for (writeBeforeFailure in listOf(false, true)) {
            val memory = FakeSecureVault()
            var armed = false
            var writes = 0
            val vault = object : app.vezuna.security.SecureVault by memory {
                override val supportsAcknowledgedWrites = true
                override suspend fun writeAcknowledged(key: String, ciphertext: ByteArray): app.vezuna.security.VaultWriteResult {
                    if (armed && key == "repository-vitals-p-v1" && ++writes == failAt) {
                        if (writeBeforeFailure) memory.write(key, ciphertext)
                        return app.vezuna.security.VaultWriteResult.Indeterminate(IllegalStateException("synthetic uncertainty"))
                    }
                    memory.write(key, ciphertext)
                    return app.vezuna.security.VaultWriteResult.Acknowledged
                }
            }
            val repo = VaultBackedRepository(vault)
            repo.load()
            repo.createProfile(Profile("p", "Synthetic", createdAt = now))
            val source = WeightSource(WeightProvider.HEALTH_CONNECT, "chain", "weight-only/all-origins/v1")
            assertIs<VitalsWriteOutcome.Committed>(repo.commitWeightPage(repo.readVitalsState(repo.captureVitalsSync(TimeZone.UTC)),
                WeightPage(SourceClaim(source, SourceContinuityEvidence(source, "synthetic live chain")),
                    listOf(WeightMutation.Upsert(WeightReading("W", now, 70.0))), WeightContinuation.Steady(source, "T0"))))
            armed = true
            val provider = object : WeightChangesSource {
                override suspend fun read(request: VitalsReadRequest) = VitalsBatch(request.replaceFrom)
                override suspend fun weightAccess() = WeightAccess()
                override suspend fun weightToken(): String = error("must resume")
                override suspend fun weightSnapshot(from: Instant, until: Instant, pageToken: String?, pageSize: Int): WeightSnapshotPage = error("must resume")
                override suspend fun weightChanges(token: String) = WeightChangesPage(emptyList(), "T1", false, false)
            }
            val outcome = DeviceVitalsSync(provider, repo, { now }, { TimeZone.UTC }).synchronizeOutcome()
            assertEquals(VitalsSyncStatus.UNCERTAIN, outcome.status)
            assertFailsWith<IllegalStateException> { repo.peekProfile("p") }
            val bytes = memory.read("repository-vitals-p-v1")!!
            val messages = mutableListOf<NativeText>()
            var refreshes = 0
            try {
                outcome.presentHealthConnectOutcome(
                    refresh = { refreshes++; repo.peekProfile("p"); Unit },
                    publish = { messages += it },
                )
            } catch (error: Throwable) {
                messages += error.toNativeText(NativeKey.HEALTH_CONNECT_IMPORT_FAILED)
            }
            assertEquals<List<NativeText>>(listOf(nativeMessage(NativeKey.HEALTH_CONNECT_IMPORT_UNCERTAIN)), messages)
            assertEquals(0, refreshes)
            assertContentEquals(bytes, memory.read("repository-vitals-p-v1"))
            assertFailsWith<IllegalStateException> { repo.peekProfile("p") }
        }
    }

    @Test fun readableOutcomeRefreshesBeforePublishing() = runTest {
        val events = mutableListOf<String>()
        VitalsSyncOutcome(3, VitalsSyncStatus.COMPLETE).presentHealthConnectOutcome(
            refresh = { events += "refresh" },
            publish = { assertEquals(nativePlural(NativeKey.HEALTH_CONNECT_IMPORT_COMPLETE, 3), it); events += "message" },
        )
        assertEquals(listOf("refresh", "message"), events)
    }

}
