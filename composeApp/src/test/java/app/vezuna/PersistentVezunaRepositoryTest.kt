package app.vezuna

import app.vezuna.data.PersistentVezunaRepository
import app.vezuna.domain.DoseEvent
import app.vezuna.domain.DoseEventStatus
import app.vezuna.domain.DoseEvents
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

    @Test
    fun notificationActionAndInAppWriteForSameDoseStoreOneTerminalEvent() = runTest {
        val repository = PersistentVezunaRepository(FakeSecureVault())
        repository.load()
        repository.createProfile(Profile("p", "Me", createdAt = Instant.fromEpochMilliseconds(1)))
        repository.addMedication(
            Medication("p", "m", "Med", schedules = listOf(
                DoseSchedule("p", "s", "m", Recurrence.DAILY, startDate = LocalDate(2026, 10, 1), timesLocal = listOf("08:00")),
            )),
        )
        // Android receiver path: scheduledFor arrives as epoch millis in the intent.
        val fromReceiver = Instant.fromEpochMilliseconds(1_790_000_000_000L)
        // In-app path: scheduledFor comes from the dashboard snapshot (whole seconds).
        val fromApp = Instant.fromEpochSeconds(1_790_000_000L)
        repository.addDoseEvent(DoseEvent("p", DoseEvents.idFor("s", fromReceiver), "m", "s", fromReceiver, DoseEventStatus.TAKEN))
        repository.addDoseEvent(DoseEvent("p", DoseEvents.idFor("s", fromApp), "m", "s", fromApp, DoseEventStatus.TAKEN))
        repository.addDoseEvent(DoseEvent("p", DoseEvents.idFor("s", fromApp), "m", "s", fromApp, DoseEventStatus.SKIPPED))

        assertEquals(listOf(DoseEventStatus.TAKEN), repository.readProfile("p").doseEvents.map { it.status })
    }

    @Test
    fun deletesPersistAndPurgeStoredSourceFiles() = runTest {
        val vault = FakeSecureVault()
        val repository = PersistentVezunaRepository(vault)
        repository.load()
        repository.createProfile(Profile("a", "A", createdAt = Instant.fromEpochMilliseconds(1)))
        repository.createProfile(Profile("b", "B", createdAt = Instant.fromEpochMilliseconds(2)))
        for (profile in listOf("a", "b")) {
            vault.write("source-$profile", byteArrayOf(1))
            repository.addDocument(SourceDocument(profile, "doc-$profile", "x.pdf", "application/pdf", "h", Instant.fromEpochMilliseconds(3), "source-$profile"))
            repository.addReport(LabReport(profile, "rep-$profile", "doc-$profile", importedAt = Instant.fromEpochMilliseconds(4)))
        }

        repository.deleteReport("a", "rep-a")
        assertEquals(null, vault.read("source-a"))
        assertTrue(repository.readProfile("a").documents.isEmpty())

        repository.deleteProfile("b")
        assertEquals(null, vault.read("source-b"))

        val restored = PersistentVezunaRepository(vault)
        restored.load()
        assertEquals(listOf("a"), restored.profiles.value.map { it.profileId })
        assertTrue(restored.readProfile("a").reports.isEmpty())
    }
}
