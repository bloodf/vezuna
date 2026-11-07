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
    override var supportsAcknowledgedWrites: Boolean = true
        val memory = GenerationalMemoryVault()
        var fault = ""
        var intentWritten = false
        var recoveryAvailable = false
        var linkEntered: CompletableDeferred<Unit>? = null
        var releaseLink: CompletableDeferred<Unit>? = null
        override suspend fun acknowledgeCurrent(key: String): VaultWriteResult =
            if (recoveryAvailable) VaultWriteResult.Acknowledged else VaultWriteResult.Indeterminate(IllegalStateException("recovery unavailable"))
        override suspend fun encrypt(plainText: ByteArray, associatedData: ByteArray) = memory.encrypt(plainText, associatedData)
        override suspend fun decrypt(envelope: CipherEnvelope) = memory.decrypt(envelope)
        override suspend fun read(key: String) = memory.read(key)
        override suspend fun readPrevious(key: String) = memory.readPrevious(key)
        override suspend fun readGeneration(key: String) = memory.readGeneration(key)
        override suspend fun listKeys() = memory.listKeys()
        override suspend fun quarantineCurrent(key: String) = memory.quarantineCurrent(key)
        override suspend fun delete(key: String) {
            if (fault == "intent-clear" && key.startsWith("repository-replacement-intent-")) error("interrupted before clear")
            memory.delete(key)
        }
        override suspend fun write(key: String, ciphertext: ByteArray) {
            if (fault == "profile" && intentWritten && key == "repository-profile-p-v1") throw CancellationException("profile interrupted")
            if (fault == "link" && key == "repository-health-link-v1") error("link bookkeeping")
            if (key == "repository-health-link-v1") {
                linkEntered?.complete(Unit)
                releaseLink?.await()
            }
            memory.write(key, ciphertext)
            if (fault == "legacy-after" && key == "repository-vitals-p-v1") error("legacy post-write failure")
        }
        override suspend fun writeAcknowledged(key: String, ciphertext: ByteArray): VaultWriteResult {
            if (key.startsWith("repository-replacement-intent-")) {
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

    private class Pages(private val source: WeightSource, private val now: Instant, private val mutation: Boolean = true, private val more: Boolean = true) : WeightChangesSource {
        var calls = 0
        override fun weightClaim(source: WeightSource) = SourceClaim(source, SourceContinuityEvidence(source, "synthetic provider continuity"))
        override suspend fun read(request: VitalsReadRequest) = VitalsBatch(request.replaceFrom)
        override suspend fun weightAccess() = WeightAccess()
        override suspend fun weightToken(): String = error("must resume committed T0")
        override suspend fun weightSnapshot(from: Instant, until: Instant, pageToken: String?, pageSize: Int): WeightSnapshotPage = error("must not snapshot")
        override suspend fun weightChanges(token: String): WeightChangesPage {
            calls++
            assertEquals("T0", token)
            return WeightChangesPage(if (mutation) listOf(WeightMutation.Upsert(WeightReading("W", now, 72.0))) else emptyList(), "T1", more, false)
        }
    }

    @Test fun rejectedAndUncertainPagesNeverReadNextAndReopenWholeGeneration() = runTest {
        for ((mutation, more) in listOf(true to true, false to true, false to false)) {
        for (fault in listOf("reject", "uncertain-before", "uncertain-after")) {
            val vault = FaultVault()
            val (repository, state) = seed(vault)
            vault.fault = fault
            val pages = Pages(source, now, mutation, more)
            WeightPaging(pages, repository, now) { Duration.ZERO }.run(state)
            assertEquals(1, pages.calls, fault)
            val exactBytes = vault.memory.current.getValue(key).copyOf()
            val reopened = open(vault)
            assertContentEquals(exactBytes, vault.memory.current[key])
            val saved = reopened.readVitalsState(reopened.captureVitalsSync(zone))
            assertEquals(if (fault == "uncertain-after") "T1" else "T0", (saved.local.continuation as? WeightContinuation.Steady)?.token ?: (saved.local.continuation as WeightContinuation.Replay).token)
            assertEquals(listOf(w.copy(kg = if (fault == "uncertain-after" && mutation) 72.0 else 70.0)), reopened.peekProfile("p").vitals.weights)
            assertEquals(mapOf("W" to claim.continuity), saved.local.provenance)
            assertEquals(emptySet(), saved.local.unresolved)
            assertEquals(if (fault == "uncertain-after") 2L else 1L, saved.revision)
            assertFailsWith<IllegalStateException> { reopened.readVitalsState(state.context) }
            if (fault.startsWith("uncertain")) assertFailsWith<IllegalStateException> { repository.captureVitalsSync(zone) }
            assertIs<VitalsWriteOutcome.Rejected>(repository.commitWeightPage(state, WeightPage(claim, emptyList())))
        }
        }
    }

    @Test fun uncertainResolutionNeedsPlatformAcknowledgementAndNeverRevivesOldContext() = runTest {
        for (fault in listOf("uncertain-before", "uncertain-after")) {
            val vault = FaultVault()
            val (repository, state) = seed(vault)
            vault.fault = fault
            val result = assertIs<VitalsWriteOutcome.Indeterminate>(repository.commitWeightPage(state,
                WeightPage(claim, listOf(WeightMutation.Delete("W")), WeightContinuation.Steady(source, "T1"))))
            assertFalse(repository.resolveVitalsWrite(result.operationId))
            assertFailsWith<IllegalStateException> { repository.captureVitalsSync(zone) }
            vault.recoveryAvailable = true
            assertTrue(repository.resolveVitalsWrite(result.operationId))
            val current = repository.readVitalsState(repository.captureVitalsSync(zone))
            assertEquals(if (fault == "uncertain-after") "T1" else "T0", (current.local.continuation as WeightContinuation.Steady).token)
            assertEquals(if (fault == "uncertain-after") emptyList() else listOf(w), repository.peekProfile("p").vitals.weights)
            assertFailsWith<IllegalStateException> { repository.readVitalsState(state.context) }
            assertFalse(repository.resolveVitalsWrite(result.operationId))
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

    @Test fun replacementUncertaintyBlocksCachedReadsExportsAndSyncUntilExplicitRecovery() = runTest {
        for (restore in listOf(false, true)) for (fault in listOf("uncertain-before", "uncertain-after", "legacy-after", "reject")) {
            val vault = FaultVault()
            val (repository, state) = seed(vault)
            if (fault == "legacy-after") {
                // Unsupported legacy backends may replace clinical-only state, never transactional local state.
                repository.replaceVitals("p", ProfileVitals(weights = listOf(w)))
                vault.supportsAcknowledgedWrites = false
            }
            val oldBytes = vault.memory.current.getValue(key).copyOf()
            val oldStored = assertNotNull(VitalsStore(vault, json).loadStored("p")).value
            val data = repository.peekProfile("p").copy(vitals = ProfileVitals(weights = listOf(w.copy(kg = 65.0))))
            vault.fault = fault
            if (restore) assertFalse(repository.restoreProfileAndVitals(data))
            else assertFailsWith<IllegalStateException> { repository.replaceVitals("p", data.vitals) }
            assertContentEquals(byteArrayOf(1), vault.memory.current["repository-replacement-intent-p-v1"])
            val exactBytes = vault.memory.current.getValue(key).copyOf()
            val written = fault == "uncertain-after" || fault == "legacy-after"
            val saved = assertNotNull(VitalsStore(vault, json).loadStored("p")).value
            if (!written) {
                assertContentEquals(oldBytes, exactBytes)
                assertEquals(oldStored, saved)
            } else {
                assertEquals(data.vitals, saved.clinical)
                assertEquals(WeightLocalState(), saved.local)
                assertEquals(oldStored.revision + 1, saved.revision)
                assertNotEquals(oldStored.commitId, saved.commitId)
            }
            val expected = if (fault == "uncertain-after" || fault == "legacy-after") data.vitals else ProfileVitals(weights = listOf(w))
            if (fault == "reject") {
                assertEquals(expected, repository.peekProfile("p").vitals)
                assertEquals(expected, repository.readProfile("p").vitals)
                val backup = BackupService().create(repository.peekProfile("p"), "synthetic password".toCharArray())
                assertEquals(expected, BackupService().open(backup, "synthetic password".toCharArray()).data.vitals)
            } else {
                assertFailsWith<IllegalStateException> { repository.peekProfile("p") }
                assertFailsWith<IllegalStateException> { repository.readProfile("p") }
                assertFailsWith<IllegalStateException> { BackupService().create(repository.peekProfile("p"), "synthetic password".toCharArray()) }
            }
            assertFailsWith<IllegalStateException> { repository.captureVitalsSync(zone) }
            assertIs<VitalsWriteOutcome.Rejected>(repository.commitWeightPage(state, WeightPage(claim, emptyList())))
            assertFalse(repository.resolveVitalsWrite("not-a-replacement-recovery"))
            vault.fault = ""
            val reopened = open(vault)
            assertContentEquals(exactBytes, vault.memory.current[key])
            assertEquals(expected, reopened.peekProfile("p").vitals)
            assertFailsWith<IllegalStateException> { reopened.captureVitalsSync(zone) }
            assertTrue(reopened.restoreProfileAndVitals(data))
            assertNull(vault.memory.current["repository-replacement-intent-p-v1"])
            assertEquals(data.vitals, reopened.peekProfile("p").vitals)
            val fresh = reopened.readVitalsState(reopened.captureVitalsSync(zone))
            assertEquals(WeightLocalState(), fresh.local)
        }
    }

    @Test fun cancellationDuringSuspendedLinkWriteCannotReturnSuccessor() = runTest {
        val vault = FaultVault()
        val repository = open(vault)
        repository.createProfile(Profile("p", "Synthetic", createdAt = now))
        val state = repository.readVitalsState(repository.captureVitalsSync(zone))
        vault.linkEntered = CompletableDeferred()
        vault.releaseLink = CompletableDeferred()
        var outcome: VitalsWriteOutcome? = null
        val pages = Pages(source, now)
        val job = launch {
            outcome = repository.commitWeightPage(state, WeightPage(claim, listOf(WeightMutation.Upsert(w)), WeightContinuation.Steady(source, "T0")))
            (outcome as? VitalsWriteOutcome.Committed)?.next?.let { WeightPaging(pages, repository, now) { Duration.ZERO }.run(it) }
        }
        vault.linkEntered!!.await()
        val acknowledgedBytes = vault.memory.current.getValue(key).copyOf()
        job.cancel()
        vault.releaseLink!!.complete(Unit)
        job.join()
        assertNull(assertIs<VitalsWriteOutcome.Committed>(outcome).next)
        assertEquals(0, pages.calls)
        assertContentEquals(acknowledgedBytes, vault.memory.current[key])
        assertFailsWith<IllegalStateException> { repository.readVitalsState(state.context) }
        val reopened = open(vault)
        assertEquals(listOf(w), reopened.peekProfile("p").vitals.weights)
        assertEquals("T0", (reopened.readVitalsState(reopened.captureVitalsSync(zone)).local.continuation as WeightContinuation.Steady).token)
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

    @Test fun unsupportedBackendKeepsLegacyNonpagingButCannotAdvanceCursor() = runTest {
        val memory = GenerationalMemoryVault()
        val unsupported = object : SecureVault by memory {
            override val supportsAcknowledgedWrites: Boolean = false
            override suspend fun writeAcknowledged(key: String, ciphertext: ByteArray): VaultWriteResult =
                error("Unsupported backend must not be asked for a transactional write")
        }
        val repository = open(unsupported)
        repository.createProfile(Profile("p", "Synthetic", createdAt = now))
        val batch = VitalsBatch(LocalDate(2026, 10, 30), steps = listOf(StepsDay(LocalDate(2026, 11, 1), 42)), weights = listOf(w), markSynced = false)
        val legacy = repository.ingestDeviceVitalsAndCapture(repository.captureVitalsSync(zone), batch, now)
        assertEquals(2, legacy.storedCount)
        assertEquals(ProfileVitals(steps = batch.steps, weights = listOf(w)), repository.peekProfile("p").vitals)
        val before = memory.current.getValue(key).copyOf()
        val state = repository.readVitalsState(legacy.context)
        assertIs<VitalsWriteOutcome.Rejected>(repository.commitWeightPage(state, WeightPage(claim, listOf(WeightMutation.Delete("W")), WeightContinuation.Steady(source, "forbidden-T1"))))
        assertContentEquals(before, memory.current[key])
        assertNull(open(unsupported).readVitalsState(open(unsupported).captureVitalsSync(zone)).local.continuation)
        val next = repository.ingestDeviceVitalsAndCapture(repository.captureVitalsSync(zone), VitalsBatch(VitalsBatch.MERGE_ONLY, weights = listOf(w.copy(recordId = "new")), markSynced = false), now)
        assertEquals(listOf(w, w.copy(recordId = "new")), repository.peekProfile("p").vitals.weights)
        assertNull(next.context.lastSync)
        repository.replaceVitals("p", ProfileVitals(weights = listOf(w), lastSync = now))
        assertEquals(ProfileVitals(weights = listOf(w)), open(unsupported).peekProfile("p").vitals)
    }

    @Test fun failedAggregateReadAndInvalidCompletedWindowPreserveClinicalAndCursor() = runTest {
        val vault = FaultVault()
        val (repository, state) = seed(vault)
        val bytes = vault.memory.current.getValue(key).copyOf()
        val denied = object : DeviceVitalsSource {
            override suspend fun read(request: VitalsReadRequest): VitalsBatch = throw SecurityException("query failed")
        }
        assertFailsWith<SecurityException> { DeviceVitalsSync(denied, repository, { now }).synchronize(state.context) }
        assertContentEquals(bytes, vault.memory.current[key])
        assertEquals(state, repository.readVitalsState(state.context))
        val rejected = repository.commitAggregateWindow(state, AggregateWindow(LocalDate(2026, 10, 30),
            listOf(StepsDay(LocalDate(2026, 10, 1), 99)), emptyList(), now, zone))
        assertIs<VitalsWriteOutcome.Rejected>(rejected)
        assertContentEquals(bytes, vault.memory.current[key])
        assertEquals(state.local, repository.readVitalsState(repository.captureVitalsSync(zone)).local)
        assertNull(repository.peekProfile("p").vitals.lastSync)
    }

    @Test fun mixedLegacyWriterKeepsTransactionalLocalStateExactlyAndWeightWriteKeepsAggregates() = runTest {
        val vault = FaultVault()
        val (repository, state) = seed(vault)
        val legacy = repository.ingestDeviceVitalsAndCapture(state.context,
            VitalsBatch(LocalDate(2026, 10, 30), steps = listOf(StepsDay(LocalDate(2026, 11, 1), 101))), now)
        val latest = repository.readVitalsState(legacy.context)
        assertEquals(state.local, latest.local)
        assertEquals(listOf(w), repository.peekProfile("p").vitals.weights)
        val before = repository.peekProfile("p").vitals
        val deleted = assertIs<VitalsWriteOutcome.Committed>(repository.commitWeightPage(latest, WeightPage(claim, listOf(WeightMutation.Delete("W")), WeightContinuation.Steady(source, "T2"))))
        assertEquals(0, deleted.storedCount)
        assertEquals(before.copy(weights = emptyList()), repository.peekProfile("p").vitals)
    }

    @Test fun embeddedClinicalMigrationSurvivesOrdinaryProfileWriteAndOnlyMovesOnAuthorizedSync() = runTest {
        val vault = FaultVault()
        val repository = open(vault)
        repository.createProfile(Profile("p", "Synthetic", createdAt = now))
        val clinical = ProfileVitals(weights = listOf(w), samples = listOf(VitalSample("raw", VitalMetric.WEIGHT_KG, 64.0, now, now)))
        val data = repository.peekProfile("p").copy(vitals = clinical)
        val plain = StorageSchema.stamp(json.encodeToJsonElement(ProfileData.serializer(), data) as kotlinx.serialization.json.JsonObject)
        vault.memory.write("repository-profile-p-v1", CipherEnvelopeCodec.encode(vault.encrypt(plain.toString().encodeToByteArray(), "Vezuna repository profile v1:p".encodeToByteArray())))
        val reopened = open(vault)
        assertEquals(clinical, reopened.peekProfile("p").vitals)
        assertNull(vault.memory.current[key])
        reopened.updateProfile(data.profile.copy(displayName = "Updated"))
        val again = open(vault)
        assertEquals(clinical, again.peekProfile("p").vitals)
        val state = again.readVitalsState(again.captureVitalsSync(zone))
        assertIs<VitalsWriteOutcome.Committed>(again.commitWeightPage(state, WeightPage(claim, emptyList(), WeightContinuation.Steady(source, "T1"))))
        assertEquals(clinical, open(vault).peekProfile("p").vitals)
        assertNotNull(vault.memory.current[key])
    }

    @Test fun corruptCurrentRecoversWholePreviousWithoutDeletingAnyRecoverableBytes() = runTest {
        val vault = FaultVault()
        val (repository, state) = seed(vault)
        val previousBytes = vault.memory.current.getValue(key).copyOf()
        repository.commitWeightPage(state, WeightPage(claim, listOf(WeightMutation.Upsert(w.copy(kg = 72.0))), WeightContinuation.Steady(source, "T1")))
        vault.memory.current[key] = "broken-current".encodeToByteArray()
        val recovered = open(vault)
        assertTrue(recovered.openedFromPreviousGeneration.value)
        assertEquals(listOf(w), recovered.peekProfile("p").vitals.weights)
        assertContentEquals(previousBytes, vault.memory.previous[key])
        assertEquals("broken-current", vault.memory.quarantined.getValue(key).decodeToString())
        val current = recovered.readVitalsState(recovered.captureVitalsSync(zone))
        assertEquals("T0", (current.local.continuation as WeightContinuation.Steady).token)
        val next = recovered.commitWeightPage(current, WeightPage(claim, listOf(WeightMutation.Upsert(w.copy(kg = 71.0))), WeightContinuation.Steady(source, "T2")))
        assertIs<VitalsWriteOutcome.Committed>(next)
        assertContentEquals(previousBytes, vault.memory.previous[key])
        assertEquals(listOf(w.copy(kg = 71.0)), open(vault).peekProfile("p").vitals.weights)
    }

    @Test fun replacementIntentCannotCollideWithAnotherProfilesVitalsNamespace() = runTest {
        val vault = FaultVault()
        val (repository, _) = seed(vault)
        repository.createProfile(Profile("replacement-p", "Other", createdAt = now))
        repository.setLinkedHealthProfile("replacement-p")
        repository.commitWeightPage(repository.readVitalsState(repository.captureVitalsSync(zone)), WeightPage(claim, listOf(WeightMutation.Upsert(w))))
        val otherKey = "repository-vitals-replacement-p-v1"
        val bytes = vault.memory.current.getValue(otherKey).copyOf()
        repository.setLinkedHealthProfile("p")
        vault.fault = "profile"
        assertFailsWith<CancellationException> { repository.restoreProfileAndVitals(repository.peekProfile("p")) }
        assertContentEquals(bytes, vault.memory.current[otherKey])
        assertEquals(listOf(w), open(vault).peekProfile("replacement-p").vitals.weights)
        assertEquals(listOf<Byte>(1), vault.memory.current["repository-replacement-intent-p-v1"]?.toList())
    }

    @Test fun actualPortableBackupAndSizeSerializationExcludeLocalSentinels() = runTest {
        val vault = FaultVault()
        val (repository, state) = seed(vault)
        val token = "WEIGHT_LOCAL_SENTINEL_NEVER_PORTABLE_62"
        repository.commitWeightPage(state, WeightPage(claim, emptyList(), WeightContinuation.Steady(source, token)))
        val data = repository.peekProfile("p")
        val passphrase = "synthetic export password".toCharArray()
        val file = BackupService().create(data, passphrase.copyOf())
        val decrypted = PassphraseBackupCodec.open(file, passphrase.copyOf()).decodeToString()
        for (sentinel in listOf("provider-chain", token, "provenance", "continuation", "vitalsFormat", "commitId")) assertFalse(sentinel in decrypted, sentinel)
        assertEquals(data.vitals, BackupService().open(file, passphrase.copyOf()).data.vitals)
        assertEquals(ProfileData(Profile("p", "Synthetic", createdAt = now), vitals = ProfileVitals(weights = listOf(w))), data)
    }

    @Test fun sweepEnumerationPreservesUnindexedClinicalAndRefusesUnindexedFuturePrefix() = runTest {
        val vault = FaultVault()
        seed(vault)
        val index = "repository-vitals-index-v1"
        vault.memory.current.remove(index)
        vault.memory.previous.remove(index)
        val before = vault.memory.current.getValue(key).copyOf()
        val store = VitalsStore(vault, json)
        store.sweep(emptySet())
        assertContentEquals(before, vault.memory.current[key])
        val futureKey = "repository-vitals-replacement-p-v1"
        val future = CipherEnvelopeCodec.encode(vault.encrypt("""{"vitalsFormat":77,"future":"retain"}""".encodeToByteArray(), "Vezuna vitals v1:replacement-p".encodeToByteArray()))
        vault.memory.current[futureKey] = future
        assertFailsWith<StorageSchemaTooNewException> { store.sweep(emptySet()) }
        assertContentEquals(before, vault.memory.current[key])
        assertContentEquals(future, vault.memory.current[futureKey])
        assertTrue(vault.memory.quarantined.isEmpty())
    }

    @Test fun unknownNestedFieldsInPreviousEnvelopeBlockRotationAndDeletion() = runTest {
        for (section in listOf("clinical", "local")) {
            val vault = GenerationalMemoryVault()
            suspend fun encoded(plain: String) = CipherEnvelopeCodec.encode(vault.encrypt(plain.encodeToByteArray(), "Vezuna vitals v1:p".encodeToByteArray()))
            val current = encoded(json.encodeToString(StoredVitals.serializer(), StoredVitals()))
            val previous = encoded("""{"vitalsFormat":1,"$section":{"futureField":true}}""")
            vault.current[key] = current
            vault.previous[key] = previous
            val store = VitalsStore(vault, json)

            assertFailsWith<StorageSchemaTooNewException> { store.load("p") }
            assertFailsWith<StorageSchemaTooNewException> { store.save("p", ProfileVitals()) }
            assertFailsWith<StorageSchemaTooNewException> { store.delete("p") }
            assertFailsWith<StorageSchemaTooNewException> { store.sweep(emptySet()) }
            assertContentEquals(current, vault.current[key])
            assertContentEquals(previous, vault.previous[key])
            assertTrue(vault.quarantined.isEmpty())
            assertTrue(vault.writes.isEmpty())
        }
    }

    @Test fun invalidLocalStateInPreviousEnvelopeNeverQuarantinesReadableCurrent() = runTest {
        val vault = GenerationalMemoryVault()
        suspend fun encoded(plain: String) = CipherEnvelopeCodec.encode(vault.encrypt(plain.encodeToByteArray(), "Vezuna vitals v1:p".encodeToByteArray()))
        val current = encoded(json.encodeToString(StoredVitals.serializer(), StoredVitals(clinical = ProfileVitals(weights = listOf(w)))))
        val previous = encoded("""{"vitalsFormat":1,"local":{"provenance":{"":{"source":{"provider":"HEALTH_KIT","store":"synthetic","originPolicy":"weight-only"},"basis":"synthetic"}}}}""")
        vault.current[key] = current
        vault.previous[key] = previous
        val store = VitalsStore(vault, json)

        assertFailsWith<StorageSchemaTooNewException> { store.load("p") }
        assertFailsWith<StorageSchemaTooNewException> { store.save("p", ProfileVitals()) }
        assertFailsWith<StorageSchemaTooNewException> { store.delete("p") }
        assertFailsWith<StorageSchemaTooNewException> { store.sweep(emptySet()) }
        assertContentEquals(current, vault.current[key])
        assertContentEquals(previous, vault.previous[key])
        assertTrue(vault.quarantined.isEmpty())
        assertTrue(vault.writes.isEmpty())
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
