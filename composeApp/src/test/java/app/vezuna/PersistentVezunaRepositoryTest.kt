package app.vezuna

import app.vezuna.data.PersistentVezunaRepository
import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.LabReport
import app.vezuna.domain.Medication
import app.vezuna.domain.Profile
import app.vezuna.domain.Recurrence
import app.vezuna.domain.SourceDocument
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PersistentVezunaRepositoryTest {
    @Test
    fun profileScopedStateSurvivesRepositoryRecreation() = runTest {
        val vault = FakeSecureVault()
        val repository = PersistentVezunaRepository(vault)
        repository.load()
        val profile = Profile("profile-a", "Me", createdAt = Instant.fromEpochMilliseconds(1))
        repository.createProfile(profile)
        val document = SourceDocument("profile-a", "document-a", "lab.pdf", "application/pdf", "abc", Instant.fromEpochMilliseconds(2))
        repository.addDocument(document)
        repository.addReport(LabReport("profile-a", "report-a", "document-a", importedAt = Instant.fromEpochMilliseconds(3)))
        val medication = Medication(
            "profile-a", "med-a", "Recorded medicine", schedules = listOf(
                DoseSchedule("profile-a", "schedule-a", "med-a", Recurrence.DAILY, startDate = LocalDate(2026, 10, 1), timesLocal = listOf("08:00")),
            ),
        )
        repository.addMedication(medication)

        val restored = PersistentVezunaRepository(vault)
        restored.load()

        assertEquals(listOf(profile), restored.profiles.value)
        val data = restored.readProfile("profile-a")
        assertEquals(listOf(document), data.documents)
        assertEquals("report-a", data.reports.single().reportId)
        assertEquals("schedule-a", data.medications.single().schedules.single().scheduleId)
        assertTrue(restored.readProfile("profile-a").chat.isEmpty())
    }

    @Test
    fun relatedRecordsCannotCrossProfileBoundary() = runTest {
        val repository = PersistentVezunaRepository(FakeSecureVault())
        repository.load()
        repository.createProfile(Profile("a", "A", createdAt = Instant.fromEpochMilliseconds(1)))
        repository.createProfile(Profile("b", "B", createdAt = Instant.fromEpochMilliseconds(2)))
        repository.addDocument(SourceDocument("a", "doc-a", "a.pdf", "application/pdf", "hash", Instant.fromEpochMilliseconds(3)))

        assertFailsWith<IllegalArgumentException> {
            repository.addReport(LabReport("b", "report-b", "doc-a", importedAt = Instant.fromEpochMilliseconds(4)))
        }
        assertTrue(repository.readProfile("b").reports.isEmpty())
    }
}
