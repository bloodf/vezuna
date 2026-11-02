package app.vezuna

import app.vezuna.domain.Profile
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** Stored vault/backup JSON written before the kotlin.time.Instant migration must round-trip byte-identically. */
class StoredInstantWireFormatTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun storedProfilesWithIsoInstantsReencodeIdentically() {
        val stored = """[{"profileId":"p1","displayName":"Me","birthDate":"1990-05-17","createdAt":"2024-03-01T12:00:00Z"},""" +
            """{"profileId":"p2","displayName":"Kid","birthDate":null,"createdAt":"2025-11-02T08:15:30.123456789Z"}]"""
        val decoded = json.decodeFromString(ListSerializer(Profile.serializer()), stored)
        assertEquals(1709294400L, decoded[0].createdAt.epochSeconds)
        assertEquals(123456789, decoded[1].createdAt.nanosecondsOfSecond)
        assertEquals(stored, json.encodeToString(ListSerializer(Profile.serializer()), decoded))
    }
}
