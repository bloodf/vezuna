package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.isInternallyConsistent
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManualResultTest {
    private val day = Instant.parse("2026-03-10T12:00:00Z")

    private suspend fun start(): Pair<InMemoryVezunaRepository, app.vezuna.ui.AppController> {
        val repository = InMemoryVezunaRepository()
        val controller = labController(repository)
        controller.initialize()
        return repository to controller
    }

    @Test
    fun manualEntryCreatesAManualReportWithoutAnyDocument() = runTest {
        val (repository, controller) = start()
        controller.addManualResult("HGB", "Hemoglobin", "13,4", "g/dL", "12", "15,5", "12 a 15,5 g/dL", day)

        assertNull(controller.state.value.error)
        val data = repository.readProfile(repository.profiles.value.single().profileId)
        assertTrue(data.documents.isEmpty())
        assertTrue(data.ocrCandidates.isEmpty())
        val report = data.reports.single()
        assertTrue(report.isManual)
        val observation = data.observations.single()
        assertEquals(report.reportId, observation.reportId)
        assertEquals(ConfirmationStatus.CONFIRMED, observation.confirmation)
        assertEquals(13.4, observation.originalValue)
        assertEquals(day, observation.collectedAt)
        assertEquals("12 a 15,5 g/dL", observation.referenceRange.text)
        assertTrue(data.isInternallyConsistent())
    }

    @Test
    fun collectionDateIsRequired() = runTest {
        val (repository, controller) = start()
        controller.addManualResult("HGB", "Hemoglobin", "13", "g/dL", "", "", null, null)
        assertEquals(L10nKey.COLLECTION_DATE_REQUIRED, (controller.state.value.error as UserText.Key).key)
        assertTrue(repository.readProfile(repository.profiles.value.single().profileId).reports.isEmpty())
    }

    @Test
    fun duplicateManualResultIsRejectedWithoutLeavingAnEmptyReport() = runTest {
        val (repository, controller) = start()
        controller.addManualResult("HGB", "Hemoglobin", "13", "g/dL", "", "", null, day)
        controller.addManualResult("HGB", "Hemoglobin", "13", "g/dL", "", "", null, day)
        assertEquals(L10nKey.DUPLICATE_CLINICAL_RESULT, (controller.state.value.error as UserText.Key).key)
        val data = repository.readProfile(repository.profiles.value.single().profileId)
        assertEquals(1, data.reports.size)
        assertEquals(1, data.observations.size)
    }

    @Test
    fun manualTextResultAndMissingUnitValidation() = runTest {
        val (repository, controller) = start()
        controller.addManualResult("HIV", "HIV", "Negativo", "", "", "", "Negativo", day, asText = true)
        assertNull(controller.state.value.error)
        controller.addManualResult("GLU", "Glucose", "90", " ", "", "", null, day)
        assertEquals(L10nKey.NUMERIC_CANDIDATE_UNIT_REQUIRED, (controller.state.value.error as UserText.Key).key)
        val data = repository.readProfile(repository.profiles.value.single().profileId)
        assertEquals("Negativo", data.observations.single().qualitativeValue)
    }

    @Test
    fun deletingAManualReportRemovesItsResult() = runTest {
        val (repository, controller) = start()
        controller.addManualResult("HGB", "Hemoglobin", "13", "g/dL", "", "", null, day)
        val profileId = repository.profiles.value.single().profileId
        repository.deleteReport(profileId, repository.readProfile(profileId).reports.single().reportId)
        val data = repository.readProfile(profileId)
        assertTrue(data.reports.isEmpty() && data.observations.isEmpty())
    }

    @Test
    fun ambiguousThousandsInputIsRejectedForEntryAndEdit() = runTest {
        val (repository, controller) = start()
        controller.addManualResult("PLT", "Platelets", "250.000", "/µL", "", "", null, day)
        assertEquals(L10nKey.NUMBER_AMBIGUOUS_INPUT, (controller.state.value.error as UserText.Key).key)
        assertTrue(repository.readProfile(repository.profiles.value.single().profileId).reports.isEmpty())

        controller.addManualResult("PLT", "Platelets", "250000", "/µL", "", "", null, day)
        val id = repository.readProfile(repository.profiles.value.single().profileId).observations.single().observationId
        controller.editObservation(id, "7.350", "/µL", "", "", null, day)
        assertEquals(L10nKey.NUMBER_AMBIGUOUS_INPUT, (controller.state.value.error as UserText.Key).key)
        assertEquals(250000.0, repository.readProfile(repository.profiles.value.single().profileId).observations.single().originalValue)

        controller.editObservation(id, "7,350", "/µL", "", "", null, day)
        assertEquals(7.35, repository.readProfile(repository.profiles.value.single().profileId).observations.single().originalValue)
    }

    @Test
    fun untouchedPrefilledThreeDecimalValuesAreNotRejected() = runTest {
        val (repository, controller) = start()
        // 1,250 typed in PT is stored as 1.25 with 3 decimals; an EN device prefills "1.250".
        controller.addManualResult("TSH", "TSH", "1,250", "mIU/L", "", "", null, day)
        val profileId = repository.profiles.value.single().profileId
        val observation = repository.readProfile(profileId).observations.single()
        assertEquals(3, observation.valueDecimals)
        val prefill = app.vezuna.localization.formatLabValue(observation.originalValue, observation.valueDecimals, "en-US")
        assertEquals("1.250", prefill)
        controller.editObservation(observation.observationId, prefill, "mIU/L", "", "", null, Instant.parse("2026-03-11T12:00:00Z"))
        assertNull(controller.state.value.error)
        assertEquals(1.25, repository.readProfile(profileId).observations.single().originalValue)
    }

    @Test
    fun confirmingAParsedCanonicalThreeDecimalValueSucceeds() = runTest {
        val (repository, controller) = start()
        controller.importSource(app.vezuna.ui.PickedSource("a.pdf", "application/pdf", "a".encodeToByteArray(), listOf(
            app.vezuna.ui.ExtractedCandidate("TSH", "TSH", "1.250", "mIU/L", collectedAt = day),
        )))
        val profileId = repository.profiles.value.single().profileId
        val candidate = repository.readProfile(profileId).ocrCandidates.single()
        controller.confirmCandidate(candidate.candidateId, "1.250", "mIU/L", "", "")
        assertNull(controller.state.value.error)
        assertEquals(1.25, repository.readProfile(profileId).observations.single().originalValue)
    }
}
