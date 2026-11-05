package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.data.VitalsSyncContext
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileVitals
import app.vezuna.domain.StepsDay
import app.vezuna.domain.VitalsBatch
import app.vezuna.health.DeviceVitalsSource
import app.vezuna.health.DeviceVitalsSync
import app.vezuna.health.HealthConnectPermissionFlow
import app.vezuna.health.VezunaHealthPermissions
import app.vezuna.health.VitalsReadRequest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidHealthPermissionBoundaryTest {
    private val zone = TimeZone.of("America/Sao_Paulo")
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val context = VitalsSyncContext("a", 17, null, zone)

    private class PermissionRig(onComplete: (Boolean) -> Unit) {
        lateinit var deliver: (Set<String>) -> Unit
        val launches = mutableListOf<Set<String>>()
        val flow = HealthConnectPermissionFlow(
            register = { callback ->
                deliver = callback
                val launch: (Set<String>) -> Unit = { launches.add(it) }
                launch
            },
            onComplete = onComplete,
        )
    }

    @Test
    fun delayedFullGrantConsumesExactContextBeforeCallbackAndRejectsDuplicate() {
        val holder = PendingHealthPermissionRequest()
        val forwarded = mutableListOf<VitalsSyncContext>()
        val rig = PermissionRig { granted ->
            holder.consume(granted) {
                forwarded.add(it)
                assertFalse(holder.consume(true) { error("Context must be cleared before callback") })
            }
        }
        holder.store(context)
        rig.flow.request()
        assertEquals(emptyList(), forwarded)
        assertEquals(listOf(VezunaHealthPermissions), rig.launches)
        rig.deliver(VezunaHealthPermissions)
        rig.deliver(VezunaHealthPermissions)
        assertEquals(listOf(context), forwarded)
        assertSame(context, forwarded.single())
    }

    @Test
    fun emptyAndPartialGrantsConsumeRequestAndFreshExplicitRequestSucceeds() {
        for (denied in listOf(emptySet(), VezunaHealthPermissions.drop(1).toSet())) {
            val holder = PendingHealthPermissionRequest()
            val forwarded = mutableListOf<VitalsSyncContext>()
            val rig = PermissionRig { holder.consume(it, forwarded::add) }
            holder.store(context)
            rig.flow.request()
            rig.deliver(denied)
            rig.deliver(VezunaHealthPermissions)
            assertEquals(emptyList(), forwarded)
            val fresh = context.copy(generation = 18)
            holder.store(fresh)
            rig.flow.request()
            rig.deliver(VezunaHealthPermissions)
            assertEquals(listOf(fresh), forwarded)
            assertEquals(listOf(VezunaHealthPermissions, VezunaHealthPermissions), rig.launches)
        }
    }

    @Test
    fun freshHolderRejectsResultWithoutContext() {
        val old = PendingHealthPermissionRequest().also { it.store(context) }
        val fresh = PendingHealthPermissionRequest()
        var calls = 0
        val rig = PermissionRig { fresh.consume(it) { calls++ } }
        rig.deliver(VezunaHealthPermissions)
        assertEquals(0, calls)
        assertTrue(old.consume(true) { assertSame(context, it) })
    }

    @Test
    fun delayedStaleGrantsRejectRelinkAndAtoBtoAWhileValidGrantImports() = runTest {
        for (target in listOf("b", "a", null)) {
            val repository = InMemoryVezunaRepository()
            repository.createProfile(Profile("a", "A", createdAt = Instant.fromEpochSeconds(1)))
            repository.createProfile(Profile("b", "B", createdAt = Instant.fromEpochSeconds(2)))
            repository.setLinkedHealthProfile("a")
            val expected = repository.captureVitalsSync(zone)
            var reads = 0
            val source = object : DeviceVitalsSource {
                override suspend fun read(request: VitalsReadRequest): VitalsBatch {
                    reads++
                    assertEquals(zone, request.zone)
                    return VitalsBatch(request.replaceFrom, steps = listOf(StepsDay(LocalDate(2026, 11, 1), 42)))
                }
            }
            val sync = DeviceVitalsSync(source, repository, { now })
            val holder = PendingHealthPermissionRequest()
            var calls = 0
            val rig = PermissionRig { granted ->
                holder.consume(granted) { saved ->
                    calls++
                    assertSame(expected, saved)
                    launch {
                        if (target == null) assertEquals(1, sync.synchronize(saved))
                        else assertFailsWith<IllegalStateException> { sync.synchronize(saved) }
                    }
                }
            }
            holder.store(expected)
            rig.flow.request()
            assertEquals(0, calls)
            if (target != null) {
                repository.setLinkedHealthProfile("b")
                if (target == "a") repository.setLinkedHealthProfile("a")
            }
            rig.deliver(VezunaHealthPermissions)
            rig.deliver(VezunaHealthPermissions)
            testScheduler.runCurrent()
            assertEquals(1, calls)
            assertEquals(if (target == null) 1 else 0, reads)
            assertEquals(ProfileVitals(), repository.readProfile("b").vitals)
            if (target == null) {
                assertEquals(listOf(42L), repository.readProfile("a").vitals.steps.map { it.count })
                assertEquals(now, repository.readProfile("a").vitals.lastSync)
            } else assertEquals(ProfileVitals(), repository.readProfile("a").vitals)
        }
    }
}
