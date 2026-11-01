package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.ui.ExtractedCandidate
import app.vezuna.ui.PickedSource
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportDeletionTest {
    private val day = Instant.parse("2026-03-10T12:00:00Z")

    @Test
    fun deletingAnImportedReportRemovesDocumentCandidatesAndObservations() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = labController(repository, RecordingSourceStore())
        controller.initialize()
        controller.importSource(PickedSource("a.pdf", "application/pdf", "a".encodeToByteArray(), listOf(
            ExtractedCandidate("HGB", "Hemoglobin", "13", "g/dL", collectedAt = day),
            ExtractedCandidate("K", "Potassium", "4", "mmol/L", collectedAt = day),
        )))
        controller.importSource(PickedSource("b.pdf", "application/pdf", "b".encodeToByteArray(), listOf(
            ExtractedCandidate("GLU", "Glucose", "90", "mg/dL", collectedAt = day),
        )))
        val profileId = repository.profiles.value.single().profileId
        val first = repository.readProfile(profileId).ocrCandidates.first { it.testCode == "HGB" }
        controller.confirmCandidate(first.candidateId, "13", "g/dL", "", "")
        val reportA = repository.readProfile(profileId).reports.first { r -> repository.readProfile(profileId).documents.first { it.documentId == r.sourceDocumentId }.displayName == "a.pdf" }

        controller.deleteReport(reportA.reportId)

        assertNull(controller.state.value.error)
        assertEquals(L10nKey.REPORT_DELETED, (controller.state.value.notice as UserText.Key).key)
        val data = repository.readProfile(profileId)
        assertEquals(listOf("b.pdf"), data.documents.map { it.displayName })
        assertEquals(listOf("GLU"), data.ocrCandidates.map { it.testCode })
        assertTrue(data.observations.isEmpty())
        assertEquals(1, data.reports.size)
        assertTrue(controller.state.value.data!!.observations.isEmpty())
    }

    @Test
    fun deletedFileCanBeImportedAgainAsNewReport() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = labController(repository)
        controller.initialize()
        val source = PickedSource("a.pdf", "application/pdf", "a".encodeToByteArray(), listOf(ExtractedCandidate("HGB", "Hemoglobin", "13", "g/dL", collectedAt = day)))
        controller.importSource(source)
        val profileId = repository.profiles.value.single().profileId
        controller.deleteReport(repository.readProfile(profileId).reports.single().reportId)
        controller.importSource(source)
        assertEquals(1, repository.readProfile(profileId).ocrCandidates.size)
    }

    @Test
    fun unknownReportIsRejected() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = labController(repository)
        controller.initialize()
        controller.deleteReport("missing")
        assertEquals(L10nKey.REPORT_NOT_FOUND, (controller.state.value.error as UserText.Key).key)
    }
}
