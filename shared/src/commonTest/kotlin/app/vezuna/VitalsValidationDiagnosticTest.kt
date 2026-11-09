package app.vezuna

import app.vezuna.health.StoredVitals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.test.Test
import kotlin.test.assertFails

class VitalsValidationDiagnosticTest {
    @Test fun classifyValidationFailure() {
        val json = Json { encodeDefaults = true }
        val plain = """{"vitalsFormat":1,"local":{"provenance":{"":{"source":{"provider":"HEALTH_KIT","store":"synthetic","originPolicy":"weight-only"},"basis":"synthetic"}}}}"""
        val failure = assertFails { json.decodeFromJsonElement(StoredVitals.serializer(), json.parseToJsonElement(plain)) }
        println("DIAGNOSTIC_CLASS=" + failure::class.qualifiedName)
        println("DIAGNOSTIC_MESSAGE=" + failure.message)
        println("DIAGNOSTIC_CAUSE=" + failure.cause?.let { it::class.qualifiedName })
    }
}
