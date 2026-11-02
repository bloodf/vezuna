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

/** The one import path both platforms use (iOS reaches it through the bridge). */
class LabImportPathTest {
    private val day = Instant.parse("2026-03-10T12:00:00Z")
    private val candidates = listOf(
        ExtractedCandidate("HGB", "Hemoglobin", "13.2", "g/dL", collectedAt = day),
        ExtractedCandidate("K", "Potassium", "4.4", "mmol/L", collectedAt = day),
    )

    @Test
    fun reimportingTheSameFileCreatesNoNewCandidatesAndStoresTheFileOnce() = runTest {
        val repository = InMemoryVezunaRepository()
        val store = RecordingSourceStore()
        val controller = labController(repository, store)
        controller.initialize()
        val source = PickedSource("lab.pdf", "application/pdf", "same bytes".encodeToByteArray(), candidates)

        controller.importSource(source)
        controller.importSource(source)

        val data = repository.readProfile(repository.profiles.value.single().profileId)
        assertNull(controller.state.value.error)
        assertEquals(2, data.ocrCandidates.size)
        assertEquals(1, data.documents.size)
        assertEquals(1, data.reports.size)
        assertEquals(store.saved, listOfNotNull(data.documents.single().vaultKey))
        assertEquals(L10nKey.NO_NEW_CANDIDATES, (controller.state.value.notice as UserText.Key).key)
    }

    @Test
    fun numericCandidateWithoutUnitIsRejectedAndNothingIsStored() = runTest {
        val repository = InMemoryVezunaRepository()
        val store = RecordingSourceStore()
        val controller = labController(repository, store)
        controller.initialize()

        controller.importSource(PickedSource("lab.pdf", "application/pdf", "x".encodeToByteArray(), listOf(ExtractedCandidate("HGB", "Hemoglobin", "13", "", collectedAt = day))))

        assertEquals(L10nKey.NUMERIC_CANDIDATE_UNIT_REQUIRED, (controller.state.value.error as UserText.Key).key)
        assertTrue(repository.readProfile(repository.profiles.value.single().profileId).documents.isEmpty())
        assertTrue(store.saved.isEmpty())
    }
}

class RestoreRejectedCandidateTest {
    private val day = Instant.parse("2026-03-10T12:00:00Z")

    @Test
    fun rejectedCandidateReturnsToReviewButConfirmedOnesDoNot() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = labController(repository)
        controller.initialize()
        controller.importSource(PickedSource("lab.pdf", "application/pdf", "x".encodeToByteArray(), listOf(
            ExtractedCandidate("HGB", "Hemoglobin", "13.2", "g/dL", collectedAt = day),
            ExtractedCandidate("K", "Potassium", "4.4", "mmol/L", collectedAt = day),
        )))
        val profileId = repository.profiles.value.single().profileId
        val (first, second) = repository.readProfile(profileId).ocrCandidates

        controller.rejectCandidate(first.candidateId)
        assertEquals(app.vezuna.domain.ConfirmationStatus.REJECTED, repository.readProfile(profileId).ocrCandidates.first { it.candidateId == first.candidateId }.status)

        controller.restoreCandidate(first.candidateId)
        assertNull(controller.state.value.error)
        assertEquals(app.vezuna.domain.ConfirmationStatus.UNREVIEWED, repository.readProfile(profileId).ocrCandidates.first { it.candidateId == first.candidateId }.status)

        controller.restoreCandidate(first.candidateId)
        assertEquals(L10nKey.CANDIDATE_ALREADY_REVIEWED, (controller.state.value.error as UserText.Key).key)
        controller.restoreCandidate("missing")
        assertEquals(L10nKey.CANDIDATE_UNAVAILABLE, (controller.state.value.error as UserText.Key).key)
        assertEquals(app.vezuna.domain.ConfirmationStatus.UNREVIEWED, repository.readProfile(profileId).ocrCandidates.first { it.candidateId == second.candidateId }.status)
    }
}

class ReportDetailsTest {
    private val day = Instant.parse("2026-03-10T12:00:00Z")
    private val ordered = Instant.parse("2026-03-01T12:00:00Z")

    @Test
    fun severalPhotosFormOneReportWithLabAndOrderDate() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = labController(repository)
        controller.initialize()
        val source = PickedSource(
            "photos.jpg", "image/jpeg", "page1".encodeToByteArray(),
            listOf(ExtractedCandidate("HGB", "Hemoglobin", "13.2", "g/dL", collectedAt = day), ExtractedCandidate("K", "Potassium", "4.4", "mmol/L", collectedAt = day)),
            laboratoryName = " Lab Central ", orderedAt = ordered, additionalPages = listOf("page2".encodeToByteArray()),
        )
        controller.importSource(source)
        controller.importSource(source)
        val profileId = repository.profiles.value.single().profileId
        val data = repository.readProfile(profileId)
        assertEquals(1, data.reports.size)
        assertEquals(1, data.documents.size)
        assertEquals("Lab Central", data.reports.single().laboratoryName)
        assertEquals(ordered, data.reports.single().orderedAt)
        assertEquals(2, data.ocrCandidates.size)

        controller.importSource(source.copy(additionalPages = listOf("different".encodeToByteArray())))
        assertEquals(2, repository.readProfile(profileId).documents.size)
    }

    @Test
    fun reportDetailsAreEditableAndClearable() = runTest {
        val repository = InMemoryVezunaRepository()
        val controller = labController(repository)
        controller.initialize()
        controller.importSource(PickedSource("lab.pdf", "application/pdf", "x".encodeToByteArray(), listOf(ExtractedCandidate("HGB", "Hemoglobin", "13.2", "g/dL", collectedAt = day))))
        val profileId = repository.profiles.value.single().profileId
        val reportId = repository.readProfile(profileId).reports.single().reportId

        controller.updateReportDetails(reportId, "  Lab Sul ", ordered)
        assertEquals("Lab Sul", repository.readProfile(profileId).reports.single().laboratoryName)
        assertEquals(ordered, repository.readProfile(profileId).reports.single().orderedAt)

        controller.updateReportDetails(reportId, " ", null)
        assertNull(repository.readProfile(profileId).reports.single().laboratoryName)
        assertNull(repository.readProfile(profileId).reports.single().orderedAt)

        controller.updateReportDetails("missing", "x", null)
        assertEquals(L10nKey.REPORT_NOT_FOUND, (controller.state.value.error as UserText.Key).key)
    }

    @Test
    fun exportRowsCarryLaboratoryAndOrderDate() {
        val observation = app.vezuna.domain.LabObservation(
            "p", "o", "r", "HGB", "Hemoglobin", 13.0, "g/dL", 130.0, "g/L", app.vezuna.domain.ReferenceRange(), day, app.vezuna.domain.ConfirmationStatus.CONFIRMED,
        )
        val data = app.vezuna.domain.ProfileData(
            app.vezuna.domain.Profile("p", "Ana", createdAt = day),
            reports = listOf(app.vezuna.domain.LabReport("p", "r", "d", laboratoryName = "Lab Central", orderedAt = ordered, importedAt = day)),
            observations = listOf(observation),
        )
        val row = app.vezuna.export.DoctorExportContent.document(data).resultRows.single()
        assertEquals("Lab Central", row.laboratoryName)
        assertEquals(ordered, row.orderedAt)
    }
}
