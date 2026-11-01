package app.vezuna

import app.vezuna.importer.OcrTextParser
import app.vezuna.ui.ExtractedValueKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the shared golden fixtures (fixtures/lab_importer, also exercised by tools/lab_importer/test_importer.py)
 * through the Kotlin parser that serves both Android and iOS.
 */
class LabImporterGoldenTest {
    private val fixtures: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "fixtures/lab_importer") }.first { it.isDirectory }

    private fun sources(): List<File> = fixtures.listFiles { f -> f.name.endsWith(".txt") }!!.sortedBy { it.name }

    @Test
    fun everyGoldenFixtureMatchesTheKotlinParser() {
        val checked = sources().map { source ->
            val name = source.name.removeSuffix(".txt")
            val expected = Json.parseToJsonElement(File(fixtures, "$name.expected.json").readText())
                .jsonObject.getValue("observations").jsonArray.map { it.jsonObject }
            val actual = OcrTextParser.parseMeasurements(source.readText())
            assertEquals(expected.map { it.string("analyte") }, actual.map { it.displayName }, "$name analytes")
            expected.zip(actual).forEach { (want, got) -> assertObservation(name, want, got) }
            expected.size
        }
        assertTrue(checked.sum() > 0)
    }

    private fun assertObservation(fixture: String, want: JsonObject, got: app.vezuna.importer.ParsedOcrMeasurement) {
        val label = "$fixture/${got.displayName}"
        val reference = want.getValue("reference").jsonObject
        if (want.string("value_type") == "qualitative") {
            assertEquals(ExtractedValueKind.QUALITATIVE, got.valueKind, label)
            assertEquals(want.string("value").trim(), got.value, label)
        } else {
            assertEquals(ExtractedValueKind.NUMERIC, got.valueKind, label)
            assertEquals(want.number("value"), got.value.toDouble(), label)
            assertEquals(want.string("unit"), got.unit, label)
        }
        assertEquals(reference.number("lower"), got.referenceRange.lower, "$label lower")
        assertEquals(reference.number("upper"), got.referenceRange.upper, "$label upper")
        assertEquals(reference.string("text").takeIf { it.isNotBlank() }, got.referenceRange.text, "$label text")
    }

    private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}
