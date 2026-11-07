package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.domain.Profile
import app.vezuna.health.LegacyHealthConnectMigration
import app.vezuna.security.CipherEnvelope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LegacyHealthConnectMigrationTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val json = Json { encodeDefaults = true }

    private suspend fun FakeSecureVault.putLegacy(plain: String) {
        val envelope = encrypt(plain.encodeToByteArray(), "Vezuna Health Connect v1".encodeToByteArray())
        write("health-connect-v1", json.encodeToString(CipherEnvelope.serializer(), envelope).encodeToByteArray())
    }

    private suspend fun repo() = InMemoryVezunaRepository().also {
        it.createProfile(Profile("a", "A", createdAt = Instant.fromEpochSeconds(1)))
        it.createProfile(Profile("b", "B", createdAt = Instant.fromEpochSeconds(2)))
    }

    @Test
    fun legacyWeightsMoveToLinkedProfileOnceWithoutMarkingSynced() = runTest {
        val vault = FakeSecureVault()
        vault.putLegacy(
            """{"cursor":"t","samples":{"w|WEIGHT_KG|x":{"recordId":"w","metric":"WEIGHT_KG","value":70.0,"startTime":"2026-10-30T00:00:00Z","endTime":"2026-10-30T00:00:00Z"},
            "s|STEPS|x":{"recordId":"s","metric":"STEPS","value":9.0,"startTime":"2026-10-30T00:00:00Z","endTime":"2026-10-30T01:00:00Z"}}}""",
        )
        val repository = repo()
        repository.ingestDeviceVitals(repository.captureVitalsSync(), app.vezuna.domain.VitalsBatch(java.time.LocalDate.now().let { kotlinx.datetime.LocalDate(it.year, it.monthValue, it.dayOfMonth) }, steps = listOf(app.vezuna.domain.StepsDay(kotlinx.datetime.LocalDate(2026, 11, 1), 42)), markSynced = false), now)
        LegacyHealthConnectMigration(vault, repository, { now }).run(repository.captureVitalsSync())
        val vitals = repository.readProfile("a").vitals
        assertEquals(listOf("w"), vitals.weights.map { it.recordId })
        assertEquals(listOf(42L), vitals.steps.map { it.count }, "today's stored data must survive a merge-only migration")
        assertNull(vitals.lastSync)
        assertEquals(emptyList(), repository.readProfile("b").vitals.weights)
        assertNull(vault.read("health-connect-v1"))
    }

    @Test
    fun corruptLegacyFileIsDeletedAndDoesNotThrow() = runTest {
        val vault = FakeSecureVault()
        vault.write("health-connect-v1", "not json".encodeToByteArray())
        val repository = repo()
        LegacyHealthConnectMigration(vault, repository, { now }).run(repository.captureVitalsSync())
        assertNull(vault.read("health-connect-v1"))
    }
    private val legacy = """{"samples":{"w":{"recordId":"w","metric":"WEIGHT_KG","value":70.0,"startTime":"2026-09-30T00:00:00Z"}}}"""

    private suspend fun repositories(): List<app.vezuna.data.VezunaRepository> = listOf(
        repo(),
        app.vezuna.data.VaultBackedRepository(FakeSecureVault()).also {
            it.load()
            it.createProfile(Profile("a", "A", createdAt = Instant.fromEpochSeconds(1)))
            it.createProfile(Profile("b", "B", createdAt = Instant.fromEpochSeconds(2)))
        },
    )

    @Test
    fun realCallbackRejectsStaleTargetAndPreservesLegacySource() = runTest {
        for (repository in repositories()) {
            val vault = FakeSecureVault().also { it.putLegacy(legacy) }
            val bytes = vault.read("health-connect-v1")!!
            val expected = repository.captureVitalsSync()
            repository.setLinkedHealthProfile("b")
            var reads = 0
            val source = object : app.vezuna.health.DeviceVitalsSource {
                override suspend fun read(request: app.vezuna.health.VitalsReadRequest): app.vezuna.domain.VitalsBatch {
                    reads++
                    return app.vezuna.domain.VitalsBatch(request.replaceFrom)
                }
            }
            val migration = LegacyHealthConnectMigration(vault, repository, { now })
            val sync = app.vezuna.health.DeviceVitalsSync(source, repository, { now }, beforeSync = migration::run)
            kotlin.test.assertFailsWith<IllegalStateException> { sync.synchronize(expected) }
            assertEquals(0, reads)
            assertEquals(app.vezuna.domain.ProfileVitals(), repository.readProfile("a").vitals)
            assertEquals(app.vezuna.domain.ProfileVitals(), repository.readProfile("b").vitals)
            kotlin.test.assertContentEquals(bytes, vault.read("health-connect-v1"))
        }
    }

    @Test
    fun realCallbackMigratesThenReadsWithProtectedSuccessorAndCapturedTimezone() = runTest {
        for (repository in repositories()) {
            val vault = FakeSecureVault().also { it.putLegacy(legacy) }
            val zone = kotlinx.datetime.TimeZone.of("America/Sao_Paulo")
            val expected = repository.captureVitalsSync(zone)
            var reads = 0
            val source = object : app.vezuna.health.DeviceVitalsSource {
                override suspend fun read(request: app.vezuna.health.VitalsReadRequest): app.vezuna.domain.VitalsBatch {
                    reads++
                    assertEquals(zone, request.zone)
                    assertEquals(expected.generation + 1, repository.captureVitalsSync(zone).generation)
                    assertEquals(listOf("w"), repository.readProfile("a").vitals.weights.map { it.recordId })
                    return app.vezuna.domain.VitalsBatch(request.replaceFrom,
                        steps = listOf(app.vezuna.domain.StepsDay(kotlinx.datetime.LocalDate(2026, 11, 1), 42)))
                }
            }
            val migration = LegacyHealthConnectMigration(vault, repository, { now })
            val sync = app.vezuna.health.DeviceVitalsSync(source, repository, { now }, { kotlinx.datetime.TimeZone.UTC }, migration::run)
            assertEquals(1, sync.synchronize(expected))
            assertEquals(1, reads)
            assertEquals(now, repository.readProfile("a").vitals.lastSync)
            assertEquals(listOf("w"), repository.readProfile("a").vitals.weights.map { it.recordId })
            assertEquals(listOf(42L), repository.readProfile("a").vitals.steps.map { it.count })
            assertEquals(expected.generation + 2, repository.captureVitalsSync(zone).generation)
            assertNull(vault.read("health-connect-v1"))
        }
    }

    @Test
    fun relinkAndCancellationDuringRealMigrationPreserveSourceWithoutMutation() = runTest {
        for (cancel in listOf(false, true)) {
            for (repository in repositories()) {
                val storage = FakeSecureVault().also { it.putLegacy(legacy) }
                val bytes = storage.read("health-connect-v1")!!
                val expected = repository.captureVitalsSync()
                val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
                val release = kotlinx.coroutines.CompletableDeferred<Unit>()
                val vault = object : app.vezuna.security.SecureVault by storage {
                    override suspend fun decrypt(envelope: CipherEnvelope): ByteArray {
                        entered.complete(Unit)
                        release.await()
                        return storage.decrypt(envelope)
                    }
                }
                var reads = 0
                val source = object : app.vezuna.health.DeviceVitalsSource {
                    override suspend fun read(request: app.vezuna.health.VitalsReadRequest): app.vezuna.domain.VitalsBatch {
                        reads++
                        return app.vezuna.domain.VitalsBatch(request.replaceFrom)
                    }
                }
                val migration = LegacyHealthConnectMigration(vault, repository, { now })
                val sync = app.vezuna.health.DeviceVitalsSync(source, repository, { now }, beforeSync = migration::run)
                val job = launch {
                    if (cancel) sync.synchronize(expected)
                    else kotlin.test.assertFailsWith<IllegalStateException> { sync.synchronize(expected) }
                }
                kotlinx.coroutines.withTimeout(5_000) { entered.await() }
                if (cancel) job.cancel() else {
                    repository.setLinkedHealthProfile("b")
                    repository.setLinkedHealthProfile("a")
                }
                release.complete(Unit)
                kotlinx.coroutines.withTimeout(5_000) { job.join() }
                assertEquals(cancel, job.isCancelled)
                assertEquals(0, reads)
                assertEquals(app.vezuna.domain.ProfileVitals(), repository.readProfile("a").vitals)
                assertEquals(app.vezuna.domain.ProfileVitals(), repository.readProfile("b").vitals)
                kotlin.test.assertContentEquals(bytes, storage.read("health-connect-v1"))
            }
        }
    }

    @Test
    fun relinkOrCancellationDuringUnreadableSourceReadCannotDeleteLegacyFile() = runTest {
        for (repository in repositories()) {
            for (cancel in listOf(false, true)) {
                val storage = FakeSecureVault()
                val bytes = "not json".encodeToByteArray()
                storage.write("health-connect-v1", bytes)
                val expected = repository.captureVitalsSync()
                val vault = object : app.vezuna.security.SecureVault by storage {
                    override suspend fun read(key: String): ByteArray? {
                        if (key == "health-connect-v1") {
                            if (cancel) kotlinx.coroutines.currentCoroutineContext().cancel()
                            else {
                                repository.setLinkedHealthProfile("b")
                                repository.setLinkedHealthProfile("a")
                            }
                        }
                        return storage.read(key)
                    }
                }
                val source = object : app.vezuna.health.DeviceVitalsSource {
                    override suspend fun read(request: app.vezuna.health.VitalsReadRequest): app.vezuna.domain.VitalsBatch =
                    error("Stale migration must not reach the platform")
                }
                val migration = LegacyHealthConnectMigration(vault, repository, { now })
                val sync = app.vezuna.health.DeviceVitalsSync(source, repository, { now }, beforeSync = migration::run)
                val job = launch {
                    if (cancel) sync.synchronize(expected)
                    else kotlin.test.assertFailsWith<IllegalStateException> { sync.synchronize(expected) }
                }
                job.join()
                assertEquals(cancel, job.isCancelled)
                kotlin.test.assertContentEquals(bytes, storage.read("health-connect-v1"))
                assertEquals(app.vezuna.domain.ProfileVitals(), repository.readProfile("a").vitals)
                assertEquals(app.vezuna.domain.ProfileVitals(), repository.readProfile("b").vitals)
            }
        }
    }

}
