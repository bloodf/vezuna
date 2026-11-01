package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.ReferenceRange
import app.vezuna.llm.InstalledModel
import app.vezuna.llm.InstalledModelManager
import app.vezuna.llm.LlmGenerationSettings
import app.vezuna.llm.LocalHealthChat
import app.vezuna.llm.LocalLlm
import app.vezuna.llm.ModelCatalog
import app.vezuna.llm.ModelFileStore
import app.vezuna.platform.sha256
import app.vezuna.platform.toHex
import app.vezuna.ui.AppController
import app.vezuna.ui.ExtractedCandidate
import app.vezuna.ui.ExtractedValueKind
import app.vezuna.ui.PickedSource
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportSourceTest {
    @Test
    fun oneSourceCreatesMultipleUnreviewedCandidatesAndSkipsDuplicates() = runTest {
        val repository = InMemoryVezunaRepository()
        val llm = EmptyLlm()
        val controller = AppController(repository, InstalledModelManager(ModelCatalog.unconfigured(), EmptyModelStore(), llm), LocalHealthChat(repository, llm))
        controller.initialize()
        val bytes = "synthetic-pdf-bytes".encodeToByteArray()
        val firstDate = Instant.parse("2026-03-10T00:00:00Z")
        val secondDate = Instant.parse("2026-03-11T00:00:00Z")
        val source = PickedSource(
            "panel.pdf",
            "application/pdf",
            bytes,
            listOf(
                ExtractedCandidate("ZN", "Zinco", "88.0", "µg/L", ReferenceRange(60.0, 120.0, unit = "µg/L"), firstDate),
                ExtractedCandidate("B12", "Vitamina B-12", "372", "pg/mL", ReferenceRange(190.0, 900.0, unit = "pg/mL"), secondDate),
                ExtractedCandidate("PARASITE", "Protozoários", "Ausente", valueKind = ExtractedValueKind.QUALITATIVE),
            ),
        )

        controller.importSource(source)

        val imported = repository.readProfile(repository.profiles.value.single().profileId)
        assertEquals(1, imported.documents.size)
        assertEquals(1, imported.reports.size)
        assertEquals(sha256(bytes).toHex(), imported.documents.single().sha256)
        assertEquals(3, imported.ocrCandidates.size)
        assertTrue(imported.ocrCandidates.all { it.status == ConfirmationStatus.UNREVIEWED })
        assertTrue(imported.observations.isEmpty())
        assertEquals(firstDate, imported.ocrCandidates[0].collectedAt)
        assertEquals(secondDate, imported.ocrCandidates[1].collectedAt)
        assertEquals(60.0, imported.ocrCandidates[0].referenceRange.lower)
        assertEquals(900.0, imported.ocrCandidates[1].referenceRange.upper)
        assertNull(controller.state.value.error)

        controller.importSource(source)
        val duplicateAttempt = repository.readProfile(imported.profile.profileId)
        assertEquals(1, duplicateAttempt.documents.size)
        assertEquals(1, duplicateAttempt.reports.size)
        assertEquals(3, duplicateAttempt.ocrCandidates.size)
    }

    @Test
    fun decimalCommaConfirmationStoresCanonicalClinicalValues() = runTest {
        val repository = InMemoryVezunaRepository()
        val llm = EmptyLlm()
        val controller = AppController(repository, InstalledModelManager(ModelCatalog.unconfigured(), EmptyModelStore(), llm), LocalHealthChat(repository, llm))
        controller.initialize()
        val collectedAt = Instant.parse("2026-03-10T00:00:00Z")
        controller.importSource(PickedSource("decimal.pdf", "application/pdf", "synthetic".encodeToByteArray(), listOf(
            ExtractedCandidate("HGB", "Hemoglobin", "13,4", "g/dL", collectedAt = collectedAt),
        )))
        val profileId = repository.profiles.value.single().profileId
        val candidateId = repository.readProfile(profileId).ocrCandidates.single().candidateId

        controller.confirmCandidate(candidateId, "13,4", "g/dL", "12,0", "15,5")

        val observation = repository.readProfile(profileId).observations.single()
        assertEquals(13.4, observation.originalValue)
        assertEquals(12.0, observation.referenceRange.lower)
        assertEquals(15.5, observation.referenceRange.upper)
        assertEquals(134.0, observation.normalizedValue)
        assertEquals("g/L", observation.normalizedUnit)
    }
}

private class EmptyLlm : LocalLlm {
    override suspend fun load(modelPath: String, settings: LlmGenerationSettings) = Unit
    override suspend fun generate(prompt: String): String = ""
    override suspend fun unload() = Unit
}

private class EmptyModelStore : ModelFileStore {
    override suspend fun installed(): List<InstalledModel> = emptyList()
    override suspend fun install(model: InstalledModel, sourcePath: String) = Unit
    override suspend fun verifyIntegrity(model: InstalledModel): Boolean = true
    override suspend fun delete(model: InstalledModel) = Unit
    override suspend fun saveSelection(modelId: String?) = Unit
    override suspend fun savedSelection(): String? = null
}
