package app.vezuna

import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabObservation
import app.vezuna.domain.LabReport
import app.vezuna.domain.Medication
import app.vezuna.domain.OcrCandidate
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.ReferenceRange
import app.vezuna.domain.SourceDocument
import app.vezuna.ui.today.isTodayEmpty
import app.vezuna.ui.today.recentConfirmedReports
import app.vezuna.ui.today.reviewSourceName
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodayModelTest {
    private val t = Instant.parse("2026-11-01T12:00:00Z")
    private val profile = Profile("p", "Ana Silva", createdAt = t)

    private fun report(id: String, ordered: String, owner: String = "p") =
        LabReport(owner, id, "doc-$id", orderedAt = Instant.parse(ordered), importedAt = t)

    private fun observation(id: String, reportId: String, status: ConfirmationStatus, owner: String = "p") =
        LabObservation(owner, id, reportId, "HGB", "Hemoglobin", 13.0, "g/dL", 13.0, "g/dL", ReferenceRange(), t, status)

    private fun candidate(id: String, documentId: String, status: ConfirmationStatus = ConfirmationStatus.UNREVIEWED) =
        OcrCandidate("p", id, documentId, "GLU", "Glucose", "96", "mg/dL", collectedAt = t, status = status)

    @Test
    fun recentReportsAreConfirmedOnlyNewestFirstAndOwnProfile() {
        val data = ProfileData(
            profile,
            reports = listOf(
                report("old", "2026-07-15T12:00:00Z"),
                report("new", "2026-10-28T12:00:00Z"),
                report("mid", "2026-10-14T12:00:00Z"),
                report("draft", "2026-10-30T12:00:00Z"),
                report("other", "2026-10-31T12:00:00Z", owner = "q"),
            ),
            observations = listOf(
                observation("o1", "old", ConfirmationStatus.CONFIRMED),
                observation("o2", "new", ConfirmationStatus.CONFIRMED),
                observation("o3", "mid", ConfirmationStatus.CONFIRMED),
                observation("o4", "draft", ConfirmationStatus.UNREVIEWED),
                observation("o5", "other", ConfirmationStatus.CONFIRMED, owner = "q"),
            ),
        )
        assertEquals(listOf("new", "mid"), recentConfirmedReports(data).map { it.reportId })
        assertEquals(listOf("new", "mid", "old"), recentConfirmedReports(data, limit = 5).map { it.reportId })
    }

    @Test
    fun reviewSourceIsNamedOnlyWhenOneImportIsWaiting() {
        val documents = listOf(
            SourceDocument("p", "d1", "october-blood-work-review.pdf", "application/pdf", "h1", t),
            SourceDocument("p", "d2", "other.pdf", "application/pdf", "h2", t),
        )
        val single = ProfileData(profile, documents = documents, ocrCandidates = listOf(candidate("c1", "d1"), candidate("c2", "d1"), candidate("c3", "d2", ConfirmationStatus.CONFIRMED)))
        assertEquals("october-blood-work-review.pdf", reviewSourceName(single))
        val mixed = single.copy(ocrCandidates = listOf(candidate("c1", "d1"), candidate("c2", "d2")))
        assertNull(reviewSourceName(mixed))
        assertNull(reviewSourceName(ProfileData(profile)))
    }

    @Test
    fun todayIsEmptyOnlyWithoutMedicationsReportsOrPendingReview() {
        assertTrue(isTodayEmpty(ProfileData(profile)))
        assertFalse(isTodayEmpty(ProfileData(profile, medications = listOf(Medication("p", "m", "Vitamin D")))))
        assertFalse(isTodayEmpty(ProfileData(profile, reports = listOf(report("r", "2026-10-28T12:00:00Z")))))
        assertFalse(isTodayEmpty(ProfileData(profile, ocrCandidates = listOf(candidate("c", "d1")))))
        assertTrue(isTodayEmpty(ProfileData(profile, ocrCandidates = listOf(candidate("c", "d1", ConfirmationStatus.REJECTED)))))
    }
}
