package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.domain.Profile
import app.vezuna.domain.VitalMetric
import app.vezuna.domain.VitalSample
import app.vezuna.domain.VitalsBatch
import app.vezuna.health.DeviceVitalsSource
import app.vezuna.health.DeviceVitalsSync
import app.vezuna.health.VitalsChangesPage
import app.vezuna.health.VitalsCursorStore
import app.vezuna.health.VitalsFetch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeviceVitalsSyncTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")

    private fun weight(id: String) = VitalSample(id, VitalMetric.WEIGHT_KG, 70.0, Instant.parse("2026-10-30T00:00:00Z"), Instant.parse("2026-10-30T00:00:00Z"))

    private class MemoryCursor(var value: String? = null) : VitalsCursorStore {
        override suspend fun read() = value
        override suspend fun write(cursor: String) { value = cursor }
    }

    private class FakeSource(
        val initial: VitalsFetch = VitalsFetch(VitalsBatch(emptyList()), "c0"),
        val pages: Map<String, VitalsChangesPage> = emptyMap(),
    ) : DeviceVitalsSource {
        var initialCalls = 0
        override suspend fun initial(): VitalsFetch { initialCalls++; return initial }
        override suspend fun changes(cursor: String): VitalsChangesPage = pages.getValue(cursor)
    }

    private suspend fun repo() = InMemoryVezunaRepository().also { it.createProfile(Profile("p", "Me", createdAt = Instant.fromEpochSeconds(1))) }

    @Test
    fun cursorIsNotAdvancedWhenPersistingFails() = runTest {
        val cursor = MemoryCursor("c0")
        val empty = InMemoryVezunaRepository() // no profile: ingest throws
        val source = FakeSource(pages = mapOf("c0" to VitalsChangesPage(VitalsBatch(listOf(weight("a"))), "c1", false)))
        val sync = DeviceVitalsSync(source, empty, cursor) { now }
        assertFailsWith<IllegalStateException> { sync.synchronize() }
        assertEquals("c0", cursor.value)

        // Retry after the failure is fixed loses nothing.
        empty.createProfile(Profile("p", "Me", createdAt = Instant.fromEpochSeconds(1)))
        assertEquals(1, sync.synchronize())
        assertEquals("c1", cursor.value)
        assertEquals(1, empty.readProfile("p").vitals.samples.size)
    }

    @Test
    fun firstRunImportsAndStoresCursor() = runTest {
        val repository = repo()
        val cursor = MemoryCursor()
        val source = FakeSource(VitalsFetch(VitalsBatch(listOf(weight("a"))), "c0"))
        assertEquals(1, DeviceVitalsSync(source, repository, cursor) { now }.synchronize())
        assertEquals("c0", cursor.value)
    }

    @Test
    fun expiredTokenFallsBackToFullImport() = runTest {
        val repository = repo()
        val cursor = MemoryCursor("old")
        val source = FakeSource(
            initial = VitalsFetch(VitalsBatch(listOf(weight("full"))), "fresh"),
            pages = mapOf("old" to VitalsChangesPage(VitalsBatch(emptyList()), "old", hasMore = false, cursorExpired = true)),
        )
        DeviceVitalsSync(source, repository, cursor) { now }.synchronize()
        assertEquals(1, source.initialCalls)
        assertEquals("fresh", cursor.value)
        assertEquals(listOf("full"), repository.readProfile("p").vitals.samples.map { it.recordId })
    }

    @Test
    fun changePagesAreFollowedWhileHasMore() = runTest {
        val repository = repo()
        val cursor = MemoryCursor("c0")
        val source = FakeSource(pages = mapOf(
            "c0" to VitalsChangesPage(VitalsBatch(listOf(weight("a"))), "c1", hasMore = true),
            "c1" to VitalsChangesPage(VitalsBatch(listOf(weight("b")), listOf("a")), "c2", hasMore = false),
        ))
        assertEquals(2, DeviceVitalsSync(source, repository, cursor) { now }.synchronize())
        assertEquals("c2", cursor.value)
        assertEquals(listOf("b"), repository.readProfile("p").vitals.samples.map { it.recordId })
    }
}
