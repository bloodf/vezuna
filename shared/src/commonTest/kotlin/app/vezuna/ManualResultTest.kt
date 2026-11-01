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
}
