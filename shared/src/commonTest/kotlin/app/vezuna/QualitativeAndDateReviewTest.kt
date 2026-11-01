package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabObservation
import app.vezuna.domain.LabReport
import app.vezuna.domain.OcrCandidate
import app.vezuna.domain.ReferenceRange
import app.vezuna.domain.SourceDocument
import app.vezuna.domain.NumberAmbiguity
import app.vezuna.domain.ProfileData
import app.vezuna.domain.QualitativeTimelines
import app.vezuna.domain.TrendPreparation
import app.vezuna.export.CsvDoctorExporter
import app.vezuna.export.DoctorExportContent
import app.vezuna.export.DoctorReportFormat
import app.vezuna.llm.InstalledModel
import app.vezuna.llm.InstalledModelManager
import app.vezuna.llm.LlmGenerationSettings
import app.vezuna.llm.LocalHealthChat
import app.vezuna.llm.LocalLlm
import app.vezuna.llm.ModelCatalog
import app.vezuna.llm.ModelFileStore
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.ui.AppController
import app.vezuna.ui.ExtractedCandidate
import app.vezuna.ui.ExtractedValueKind
import app.vezuna.ui.PickedSource
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QualitativeAndDateReviewTest {
    private val day1 = Instant.parse("2026-03-10T00:00:00Z")
    private val day2 = Instant.parse("2026-04-10T00:00:00Z")

    private fun controllerWith(repository: InMemoryVezunaRepository): AppController {
        val llm = NoLlm()
        return AppController(repository, InstalledModelManager(ModelCatalog.unconfigured(), NoModelStore(), llm), LocalHealthChat(repository, llm))
    }

    @Test
    fun qualitativeCandidateConfirmsAndStaysOutOfNumericTrends() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = controllerWith(repository)
        controller.initialize()
        controller.importSource(PickedSource("a.pdf", "application/pdf", "a".encodeToByteArray(), listOf(
            ExtractedCandidate("HIV", "HIV", "Não reagente", collectedAt = day1, valueKind = ExtractedValueKind.QUALITATIVE),
        )))
        controller.importSource(PickedSource("b.pdf", "application/pdf", "b".encodeToByteArray(), listOf(
            ExtractedCandidate("HIV", "HIV", "Reagente", collectedAt = day2, valueKind = ExtractedValueKind.QUALITATIVE),
        )))
        val profileId = repository.profiles.value.single().profileId
        repository.readProfile(profileId).ocrCandidates.forEach {
            assertTrue(it.qualitative)
            controller.confirmCandidate(it.candidateId, it.rawValue, "", "", "")
        }
        assertNull(controller.state.value.error)

        val data = repository.readProfile(profileId)
        assertEquals(listOf("Não reagente", "Reagente"), data.observations.sortedBy { it.collectedAt }.map { it.qualitativeValue })
        assertTrue(data.observations.all { it.isQualitative })
        assertNull(TrendPreparation.forTest(profileId, "HIV", data.observations))
        assertEquals(listOf("Não reagente", "Reagente"), QualitativeTimelines.forProfile(profileId, data.observations).single().entries.map { it.qualitativeValue })

        val csv = CsvDoctorExporter().export(data)
        assertTrue(csv.contains("Reagente"))
        assertTrue(DoctorExportContent.lines(data).any { it.contains("result Reagente") })
        assertTrue(DoctorExportContent.trends(data).isEmpty())
        val rows = DoctorExportContent.document(data).resultRows
        assertEquals(listOf("Não reagente", "Reagente"), rows.map { DoctorReportFormat.originalValue(it) })
        assertNull(DoctorReportFormat.normalizedValue(rows.first()))
    }

    @Test
    fun blankQualitativeResultIsRejected() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = controllerWith(repository)
        controller.initialize()
        controller.importSource(PickedSource("a.pdf", "application/pdf", "a".encodeToByteArray(), listOf(
            ExtractedCandidate("HIV", "HIV", "Negativo", collectedAt = day1, valueKind = ExtractedValueKind.QUALITATIVE),
        )))
        val profileId = repository.profiles.value.single().profileId
        controller.confirmCandidate(repository.readProfile(profileId).ocrCandidates.single().candidateId, " ", "", "", "")
        assertEquals(L10nKey.QUALITATIVE_RESULT_REQUIRED, (controller.state.value.error as UserText.Key).key)
        assertTrue(repository.readProfile(profileId).observations.isEmpty())
    }

    @Test
    fun confirmationRequiresDateAndAppliesEditedDateNameAndCode() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = controllerWith(repository)
        controller.initialize()
        controller.importSource(PickedSource("a.pdf", "application/pdf", "a".encodeToByteArray(), listOf(
            ExtractedCandidate("HGB", "Hemoglobin", "13", "g/dL"),
        )))
        val profileId = repository.profiles.value.single().profileId
        val candidate = repository.readProfile(profileId).ocrCandidates.single()
        assertFalse(candidate.collectedAtKnown)

        controller.confirmCandidate(candidate.candidateId, "13", "g/dL", "", "")
        assertEquals(L10nKey.COLLECTION_DATE_REQUIRED, (controller.state.value.error as UserText.Key).key)
        assertTrue(repository.readProfile(profileId).observations.isEmpty())

        controller.confirmCandidate(candidate.candidateId, "13", "g/dL", "", "", day2, " HB ", " Hemoglobina ")
        val observation = repository.readProfile(profileId).observations.single()
        assertEquals(day2, observation.collectedAt)
        assertEquals("HB", observation.testCode)
        assertEquals("Hemoglobina", observation.displayName)
    }

    @Test
    fun blankEditedNameOrCodeIsRejected() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = controllerWith(repository)
        controller.initialize()
        controller.importSource(PickedSource("a.pdf", "application/pdf", "a".encodeToByteArray(), listOf(
            ExtractedCandidate("HGB", "Hemoglobin", "13", "g/dL", collectedAt = day1),
        )))
        val profileId = repository.profiles.value.single().profileId
        controller.confirmCandidate(repository.readProfile(profileId).ocrCandidates.single().candidateId, "13", "g/dL", "", "", null, " ", null)
        assertEquals(L10nKey.CANDIDATE_REQUIRED_FIELDS, (controller.state.value.error as UserText.Key).key)
    }

    @Test
    fun oldPendingNonNumericCandidateWithoutUnitConfirmsAsQualitative() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = controllerWith(repository)
        controller.initialize()
        val profileId = repository.profiles.value.single().profileId
        repository.addDocument(SourceDocument(profileId, "d", "old.pdf", "application/pdf", "hash", day1))
        repository.addReport(LabReport(profileId, "r", "d", importedAt = day1))
        repository.addCandidate(OcrCandidate(
            profileId = profileId, candidateId = "old", sourceDocumentId = "d", testCode = "HIV", displayName = "HIV",
            rawValue = "Negativo", rawUnit = "", collectedAt = day1, collectedAtKnown = true,
        ))
        controller.initialize()
        controller.confirmCandidate("old", "Negativo", "", "", "")
        assertNull(controller.state.value.error)
        assertEquals("Negativo", repository.readProfile(profileId).observations.single().qualitativeValue)
    }

    @Test
    fun textToggleOverridesAutomaticClassification() = runTest {
        val candidate = OcrCandidate("p", "c", "d", "X", "X", "12", "", collectedAt = day1)
        assertFalse(candidate.looksQualitative)
        assertTrue(OcrCandidate("p", "c", "d", "X", "X", "Reagente", "", collectedAt = day1).looksQualitative)
    }

    @Test
    fun qualitativeObservationInvariantsAreEnforced() {
        fun make(value: Double = 0.0, unit: String = "", range: ReferenceRange = ReferenceRange(), q: String? = "Negativo") = LabObservation(
            "p", "o", "r", "HIV", "HIV", value, unit, value, unit, range, day1, qualitativeValue = q,
        )
        make()
        assertFailsWith<IllegalArgumentException> { make(value = 1.0) }
        assertFailsWith<IllegalArgumentException> { make(unit = "g/L") }
        assertFailsWith<IllegalArgumentException> { make(range = ReferenceRange(lower = 1.0)) }
        assertFailsWith<IllegalArgumentException> { make(q = " ") }
    }

    @Test
    fun onlySingleDotGroupsAreFlaggedAmbiguous() {
        listOf("1.020", "7.350", "250.000").forEach { assertTrue(NumberAmbiguity.isAmbiguousThousands(it), it) }
        listOf("0.500", "1.234.567", "13.2", "1,020").forEach { assertFalse(NumberAmbiguity.isAmbiguousThousands(it), it) }
    }

    @Test
    fun snapshotsStoredBeforeQualitativeAndDateFieldsStillDecode() {
        val old = """{"profile":{"profileId":"p","displayName":"Ana","createdAt":"2026-01-01T00:00:00Z"},
            "observations":[{"profileId":"p","observationId":"o","reportId":"r","testCode":"HGB","displayName":"Hemoglobin",
            "originalValue":13.5,"originalUnit":"g/dL","normalizedValue":135.0,"normalizedUnit":"g/L","referenceRange":{"lower":120.0,"upper":160.0},
            "collectedAt":"2026-03-10T00:00:00Z","confirmation":"CONFIRMED"}],
            "ocrCandidates":[{"profileId":"p","candidateId":"c","sourceDocumentId":"d","testCode":"HGB","displayName":"Hemoglobin",
            "rawValue":"13","rawUnit":"g/dL","collectedAt":"2026-03-10T00:00:00Z"}]}"""
        val data = Json.decodeFromString(ProfileData.serializer(), old)
        val observation: LabObservation = data.observations.single()
        assertEquals(13.5, observation.originalValue)
        assertNull(observation.qualitativeValue)
        assertFalse(observation.isQualitative)
        assertEquals(ConfirmationStatus.CONFIRMED, observation.confirmation)
        assertFalse(data.ocrCandidates.single().qualitative)
        assertFalse(data.ocrCandidates.single().collectedAtKnown)
        assertEquals(1, TrendPreparation.forTest("p", "HGB", data.observations)?.points?.size)
    }
}

private class NoLlm : LocalLlm {
    override suspend fun load(modelPath: String, settings: LlmGenerationSettings) = Unit
    override suspend fun generate(prompt: String): String = ""
    override suspend fun unload() = Unit
}

private class NoModelStore : ModelFileStore {
    override suspend fun installed(): List<InstalledModel> = emptyList()
    override suspend fun install(model: InstalledModel, sourcePath: String) = Unit
    override suspend fun verifyIntegrity(model: InstalledModel): Boolean = true
    override suspend fun delete(model: InstalledModel) = Unit
    override suspend fun saveSelection(modelId: String?) = Unit
    override suspend fun savedSelection(): String? = null
}
