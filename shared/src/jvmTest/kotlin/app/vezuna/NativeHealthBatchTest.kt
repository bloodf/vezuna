package app.vezuna

import app.vezuna.data.VaultBackedRepository
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileVitals
import app.vezuna.domain.StepsDay
import app.vezuna.health.NativeHealthBatch
import java.util.TimeZone as JavaTimeZone
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class NativeHealthBatchTest {
    @Test
    fun capturedUtcPayloadKeepsSampleDayAfterSystemMovesToLosAngeles() = runTest {
        val original = JavaTimeZone.getDefault()
        try {
            JavaTimeZone.setDefault(JavaTimeZone.getTimeZone("UTC"))
            val vault = GenerationalMemoryVault()
            val repository = VaultBackedRepository(vault).also { it.load() }
            repository.createProfile(Profile("a", "A", createdAt = Instant.fromEpochSeconds(1)))
            repository.replaceVitals("a", ProfileVitals(steps = listOf(StepsDay(LocalDate(2026, 10, 31), 42))))
            val context = repository.captureVitalsSync()
            val midnight = Instant.parse("2026-11-01T00:00:00Z").epochSeconds
            val payload = """{"profileId":"a","generation":${context.generation},"zoneId":"${context.zone.id}","replaceFromEpochSeconds":$midnight,"steps":[{"dayStartEpochSeconds":$midnight,"count":100.0}]}"""
            JavaTimeZone.setDefault(JavaTimeZone.getTimeZone("America/Los_Angeles"))
            val native = Json.decodeFromString(NativeHealthBatch.serializer(), payload)
            assertEquals(LocalDate(2026, 11, 1), native.toBatch().replaceFrom)
            repository.ingestDeviceVitals(native.context(), native.toBatch(), Instant.parse("2026-11-01T12:00:00Z"))
            val stored = repository.peekProfile("a").vitals
            assertEquals(listOf(StepsDay(LocalDate(2026, 10, 31), 42), StepsDay(LocalDate(2026, 11, 1), 100)), stored.steps)
            assertEquals(stored, VaultBackedRepository(vault).also { it.load() }.peekProfile("a").vitals)
        } finally {
            JavaTimeZone.setDefault(original)
        }
    }

    @Test
    fun missingIdentityGenerationOrZoneCannotDecodeAndInvalidZoneCannotMap() {
        val fields = listOf("\"profileId\":\"a\"", "\"generation\":1", "\"zoneId\":\"UTC\"", "\"replaceFromEpochSeconds\":0")
        for (missing in 0..2) {
            val payload = fields.filterIndexed { index, _ -> index != missing }.joinToString(",", "{", "}")
            assertFailsWith<SerializationException> { Json.decodeFromString(NativeHealthBatch.serializer(), payload) }
        }
        val invalid = NativeHealthBatch("a", 1, "not/a/zone", 0)
        assertFailsWith<IllegalArgumentException> { invalid.context() }
        assertFailsWith<IllegalArgumentException> { invalid.toBatch() }
    }
}
