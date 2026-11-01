package app.vezuna

import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabObservation
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.ReferenceRange
import app.vezuna.export.CsvDoctorExporter
import app.vezuna.export.DoctorExportContent
import app.vezuna.llm.HealthPromptBuilder
import app.vezuna.llm.InstalledModel
import app.vezuna.llm.InstalledModelManager
import app.vezuna.llm.LlmGenerationSettings
import app.vezuna.llm.LocalLlm
import app.vezuna.llm.ModelCatalog
import app.vezuna.llm.ModelFileStore
import app.vezuna.llm.ModelPack
import app.vezuna.platform.sha256
import app.vezuna.platform.toHex
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExportModelAndPromptTest {
    private val time = Instant.parse("2026-01-10T10:00:00Z")

    @Test
    fun csvProtectsSpreadsheetFormulasAndExportsOnlyConfirmedRecords() {
        val confirmed = observation("confirmed").copy(displayName = "=HYPERLINK(\"bad\")")
        val data = ProfileData(
            Profile("p", "Patient", createdAt = time),
            observations = listOf(confirmed, observation("pending").copy(confirmation = ConfirmationStatus.UNREVIEWED)),
        )
        val csv = CsvDoctorExporter().export(data)
        assertTrue(csv.contains("\"'=HYPERLINK"))
        assertFalse(csv.contains("pending"))
        assertTrue(csv.startsWith("profile_id,collected_at"))
    }

    @Test
    fun doctorContentUsesOriginalNormalizedAndPerResultRange() {
        val data = ProfileData(
            Profile("p", "Patient", createdAt = time),
            observations = listOf(observation("one"), observation("other").copy(profileId = "other", displayName = "Other profile secret")),
        )
        val content = DoctorExportContent.lines(data).joinToString("\n")
        assertTrue(content.contains("130.0 g/L"))
        assertTrue(content.contains("120–140"))
        assertTrue(content.contains("Confirmed laboratory results"))
        assertFalse(content.contains("Other profile secret"))
    }

    @Test
    fun promptIncludesOnlySelectedConfirmedFactsAndSafetyBoundaries() {
        val confirmed = observation("confirmed")
        val unreviewed = observation("unreviewed").copy(confirmation = ConfirmationStatus.UNREVIEWED)
        val other = observation("other")
        val prompt = HealthPromptBuilder.build(
            ProfileData(Profile("p", "Patient", createdAt = time), observations = listOf(confirmed, unreviewed, other)),
            setOf("confirmed", "unreviewed"),
            "What does this mean?",
        )
        assertTrue(prompt.contains("CONFIRMED RECORD FACTS"))
        assertTrue(prompt.contains("Never diagnose"))
        assertTrue(prompt.contains("never") || prompt.contains("Never"))
        assertFalse(prompt.contains("observationId"))
        assertEquals(1, prompt.split("- 2026-01-10").size - 1)
    }

    @Test
    fun modelManagerVerifiesInstallSwitchesAndDeletesIndependently() = runTest {
        val firstBytes = "first-model".encodeToByteArray()
        val secondBytes = "second-model".encodeToByteArray()
        val first = pack("first", firstBytes)
        val second = pack("second", secondBytes)
        val store = MemoryModelStore()
        val llm = RecordingLlm()
        val manager = InstalledModelManager(ModelCatalog(listOf(first, second)), store, llm)
        manager.install(first, firstBytes, 1)
        manager.install(second, secondBytes, 2)
        manager.select(first.modelId)
        manager.select(second.modelId)
        assertEquals(second.modelId, manager.state.value.selectedModelId)
        assertEquals(listOf(first.fileName, second.fileName), llm.loaded)
        manager.delete(first.modelId)
        assertEquals(second.modelId, manager.state.value.selectedModelId)
        manager.delete(second.modelId)
        assertNull(manager.state.value.selectedModelId)
        assertNull(manager.state.value.loadedModelId)
        assertEquals(2, llm.unloads)
    }

    @Test
    fun refreshRestoresSelectedModelIntoLoadedState() = runTest {
        val bytes = "restorable-model".encodeToByteArray()
        val model = pack("restore", bytes)
        val store = MemoryModelStore()
        store.write(InstalledModel(model, model.fileName, 1), bytes)
        store.saveSelection(model.modelId)
        val llm = RecordingLlm()
        val manager = InstalledModelManager(ModelCatalog(listOf(model)), store, llm)

        manager.refresh()

        assertEquals(model.modelId, manager.state.value.selectedModelId)
        assertEquals(model.modelId, manager.state.value.loadedModelId)
        assertEquals(listOf(model.fileName), llm.loaded)
        assertNull(manager.state.value.error)
    }

    @Test
    fun refreshKeepsSelectionButReportsIntegrityFailureAsNotLoaded() = runTest {
        val bytes = "original-model".encodeToByteArray()
        val model = pack("tampered", bytes)
        val store = MemoryModelStore()
        store.write(InstalledModel(model, model.fileName, 1), bytes)
        store.saveSelection(model.modelId)
        store.tamper(model.modelId, "tampered-model".encodeToByteArray())
        val llm = RecordingLlm()
        val manager = InstalledModelManager(ModelCatalog(listOf(model)), store, llm)

        manager.refresh()

        assertEquals(model.modelId, manager.state.value.selectedModelId)
        assertNull(manager.state.value.loadedModelId)
        val error = manager.state.value.error as app.vezuna.localization.UserText.Key
        assertEquals(app.vezuna.localization.L10nKey.SELECTED_MODEL_NOT_LOADED, error.key)
        assertEquals(listOf(model.modelId), error.args)
        assertTrue(llm.loaded.isEmpty())
    }

    @Test
    fun selectionUsesStoreIntegrityWithoutWholeFileRead() = runTest {
        val bytes = "streamed-model".encodeToByteArray()
        val model = pack("stream", bytes)
        val store = MemoryModelStore()
        store.write(InstalledModel(model, model.fileName, 1), bytes)
        val manager = InstalledModelManager(ModelCatalog(listOf(model)), store, RecordingLlm())

        manager.select(model.modelId)

        assertEquals(1, store.integrityChecks)
        assertEquals(0, store.readBytesCalls)
        assertEquals(model.modelId, manager.state.value.loadedModelId)
    }

    @Test
    fun restoreRespectsContextLimitAndExplicitUnloadKeepsOnlySelection() = runTest {
        val bytes = "small-context".encodeToByteArray()
        val model = pack("context", bytes).copy(maximumContextLength = 1024)
        val store = MemoryModelStore()
        store.write(InstalledModel(model, model.fileName, 1), bytes)
        store.saveSelection(model.modelId)
        val llm = RecordingLlm()
        val manager = InstalledModelManager(ModelCatalog(listOf(model)), store, llm)

        manager.refresh()
        assertEquals(listOf(1024), llm.contextLengths)
        manager.unload()

        assertEquals(model.modelId, manager.state.value.selectedModelId)
        assertNull(manager.state.value.loadedModelId)
        assertEquals(model.modelId, store.savedSelection())
    }

    @Test
    fun reinstallingSelectedModelPreservesSelectionButRequiresReload() = runTest {
        val bytes = "reinstall".encodeToByteArray()
        val model = pack("reinstall", bytes)
        val store = MemoryModelStore()
        val manager = InstalledModelManager(ModelCatalog(listOf(model)), store, RecordingLlm())
        manager.install(model, bytes, 1)
        manager.select(model.modelId)

        manager.install(model, bytes, 2)

        assertEquals(model.modelId, manager.state.value.selectedModelId)
        assertNull(manager.state.value.loadedModelId)
        assertEquals(model.modelId, store.savedSelection())
    }

    @Test
    fun modelManagerRejectsWrongHashBeforeWriting() = runTest {
        val bytes = "real".encodeToByteArray()
        val store = MemoryModelStore()
        val manager = InstalledModelManager(ModelCatalog(listOf(pack("model", bytes))), store, RecordingLlm())
        val result = runCatching { manager.install(pack("model", "fake".encodeToByteArray()), bytes, 1) }
        assertTrue(result.isFailure)
        assertTrue(store.installed().isEmpty())
    }

    private fun pack(id: String, bytes: ByteArray) = ModelPack(
        modelId = id,
        displayName = id,
        fileName = "$id.gguf",
        androidAssetPackId = "android-$id",
        iosAssetPackId = "ios-$id",
        sizeBytes = bytes.size.toLong(),
        minimumRamBytes = 1,
        maximumContextLength = 4096,
        sha256 = sha256(bytes).toHex(),
    )

    private fun observation(id: String) = LabObservation(
        "p", id, "r", "HGB", "Hemoglobin", 130.0, "g/L", 130.0, "g/L", ReferenceRange(120.0, 140.0), time, ConfirmationStatus.CONFIRMED,
    )
}

private class RecordingLlm : LocalLlm {
    val loaded = mutableListOf<String>()
    val contextLengths = mutableListOf<Int>()
    var unloads = 0
    override suspend fun load(modelPath: String, settings: LlmGenerationSettings) {
        loaded += modelPath
        contextLengths += settings.contextLength
    }
    override suspend fun generate(prompt: String): String = "education"
    override suspend fun unload() { unloads++ }
}

private class MemoryModelStore : ModelFileStore {
    private val models = mutableMapOf<String, InstalledModel>()
    private val bytes = mutableMapOf<String, ByteArray>()
    private var selection: String? = null
    var integrityChecks = 0
    var readBytesCalls = 0
    override suspend fun installed(): List<InstalledModel> = models.values.toList()
    override suspend fun write(model: InstalledModel, bytes: ByteArray) { models[model.pack.modelId] = model; this.bytes[model.pack.modelId] = bytes }
    override suspend fun readBytes(model: InstalledModel): ByteArray {
        readBytesCalls++
        return bytes.getValue(model.pack.modelId)
    }
    override suspend fun verifyIntegrity(model: InstalledModel): Boolean {
        integrityChecks++
        return sha256(bytes.getValue(model.pack.modelId)).toHex() == model.pack.sha256
    }
    override suspend fun delete(model: InstalledModel) { models.remove(model.pack.modelId); bytes.remove(model.pack.modelId) }
    override suspend fun saveSelection(modelId: String?) { selection = modelId }
    override suspend fun savedSelection(): String? = selection
    fun tamper(modelId: String, replacement: ByteArray) { bytes[modelId] = replacement }
}
