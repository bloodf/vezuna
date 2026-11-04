package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.data.VaultBackedRepository
import app.vezuna.domain.HeartRateBucket
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileVitals
import app.vezuna.domain.StepsDay
import app.vezuna.domain.VitalsBatch
import app.vezuna.health.DeviceVitalsSource
import app.vezuna.health.DeviceVitalsSync
import app.vezuna.health.VitalsReadRequest
import java.util.TimeZone as JavaTimeZone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class DeviceVitalsTimeZoneTest {
    @Test
    fun zoneChangeDuringReadKeepsOutsideWindowHeartAndOriginalStepDay() = runTest {
        val original = JavaTimeZone.getDefault()
        try {
            for (persistent in listOf(false, true)) {
                JavaTimeZone.setDefault(JavaTimeZone.getTimeZone("UTC"))
                val vault = GenerationalMemoryVault()
                val repository = if (persistent) VaultBackedRepository(vault).also { it.load() } else InMemoryVezunaRepository()
                repository.createProfile(Profile("a", "A", createdAt = Instant.fromEpochSeconds(1)))
                val outside = HeartRateBucket(Instant.parse("2026-10-24T20:00:00Z"), 60.0, 70.0, 80.0)
                repository.replaceVitals("a", ProfileVitals(
                    steps = listOf(StepsDay(LocalDate(2026, 10, 24), 42)), heartRateHourly = listOf(outside),
                    lastSync = Instant.parse("2026-11-01T12:00:00Z"),
                ))
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var zone: TimeZone = TimeZone.UTC
                val source = object : DeviceVitalsSource {
                    override suspend fun read(request: VitalsReadRequest): VitalsBatch {
                        assertEquals(TimeZone.UTC, request.zone)
                        assertEquals(LocalDate(2026, 10, 25), request.replaceFrom)
                        entered.complete(Unit)
                        release.await()
                        return VitalsBatch(request.replaceFrom, steps = listOf(StepsDay(LocalDate(2026, 11, 1), 100)))
                    }
                }
                val sync = DeviceVitalsSync(source, repository, { Instant.parse("2026-11-01T12:00:00Z") }, { zone })
                val result = async { sync.synchronize() }
                entered.await()
                zone = TimeZone.of("Asia/Tokyo")
                JavaTimeZone.setDefault(JavaTimeZone.getTimeZone(zone.id))
                release.complete(Unit)
                result.await()
                val stored = repository.peekProfile("a").vitals
                assertEquals(listOf(outside), stored.heartRateHourly)
                assertEquals(listOf(LocalDate(2026, 10, 24), LocalDate(2026, 11, 1)), stored.steps.map { it.date })
                if (persistent) {
                    val reopened = VaultBackedRepository(vault).also { it.load() }
                    assertEquals(stored, reopened.peekProfile("a").vitals)
                }
            }
        } finally {
            JavaTimeZone.setDefault(original)
        }
    }
}
