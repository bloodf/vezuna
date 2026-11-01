package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.domain.ChatMessage
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.DoseEvent
import app.vezuna.domain.DoseEventStatus
import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.LabObservation
import app.vezuna.domain.LabReport
import app.vezuna.domain.Medication
import app.vezuna.domain.OcrCandidate
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.Recurrence
import app.vezuna.domain.ReferenceRange
import app.vezuna.domain.SourceDocument
import app.vezuna.domain.vaultKeys
import app.vezuna.domain.withoutDocument
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RepositoryEditsTest {
    private val t = Instant.parse("2026-11-01T12:00:00Z")

    private suspend fun seeded() = InMemoryVezunaRepository().also { repo ->
        repo.createProfile(Profile("p", "Me", createdAt = t))
        repo.createProfile(Profile("q", "Other", createdAt = t))
        repo.addDocument(SourceDocument("p", "d", "lab.pdf", "application/pdf", "h", t, vaultKey = "source-d"))
        repo.addReport(LabReport("p", "r", "d", importedAt = t))
        repo.addCandidate(OcrCandidate("p", "c", "d", "K", "Potassium", "4", "mmol/L", collectedAt = t))
        repo.addObservation(observation())
        repo.addMedication(medication())
        repo.addDoseEvent(DoseEvent("p", "e", "m", "s", t, DoseEventStatus.TAKEN, t))
        repo.addChatMessage(ChatMessage("p", "msg", ChatMessage.Role.USER, "hi", t))
    }

    private fun observation(value: Double = 4.0) = LabObservation(
        "p", "o", "r", "K", "Potassium", value, "mmol/L", value, "mmol/L", ReferenceRange(3.5, 5.0), t, ConfirmationStatus.CONFIRMED,
    )

    private fun medication(name: String = "Med") = Medication(
        "p", "m", name, schedules = listOf(DoseSchedule("p", "s", "m", Recurrence.DAILY, startDate = LocalDate(2026, 1, 1), timesLocal = listOf("09:00"))),
    )

    @Test
    fun updatesReplaceRecordsInPlace() = runTest {
        val repo = seeded()
        repo.updateProfile(Profile("p", "Renamed", createdAt = t))
        repo.updateObservation(observation(4.4))
        repo.updateMedication(medication("Renamed med").copy(active = false))
        repo.updateChatMessage(ChatMessage("p", "msg", ChatMessage.Role.USER, "edited", t))
        val data = repo.readProfile("p")
        assertEquals("Renamed", data.profile.displayName)
        assertEquals(4.4, data.observations.single().originalValue)
        assertEquals(false, data.medications.single().active)
        assertEquals("edited", data.chat.single().content)
        assertEquals(listOf("Other", "Renamed"), repo.profiles.value.map { it.displayName })
        assertFailsWith<IllegalArgumentException> { repo.updateProfile(Profile("p", "  ", createdAt = t)) }
        assertFailsWith<IllegalArgumentException> { repo.updateObservation(observation().copy(observationId = "missing")) }
    }

    @Test
    fun deletingReportCascadesToObservationsCandidatesAndDocument() = runTest {
        val repo = seeded()
        val before = repo.readProfile("p")
        repo.deleteReport("p", "r")
        val after = repo.readProfile("p")
        assertTrue(after.reports.isEmpty() && after.observations.isEmpty() && after.ocrCandidates.isEmpty() && after.documents.isEmpty())
        assertEquals(setOf("source-d"), before.vaultKeys() - after.vaultKeys())
        assertEquals(1, after.medications.size)
    }

    @Test
    fun deletingDocumentCascadesLikeDeletingItsReport() = runTest {
        val data: ProfileData = seeded().readProfile("p")
        val after = data.withoutDocument("d")
        assertTrue(after.reports.isEmpty() && after.observations.isEmpty() && after.ocrCandidates.isEmpty() && after.documents.isEmpty())
    }

    @Test
    fun deletingMedicationOrScheduleRemovesItsDoseEvents() = runTest {
        val repo = seeded()
        repo.deleteSchedule("p", "s")
        assertTrue(repo.readProfile("p").medications.single().schedules.isEmpty())
        assertTrue(repo.readProfile("p").doseEvents.isEmpty())

        val other = seeded()
        other.deleteMedication("p", "m")
        assertTrue(other.readProfile("p").medications.isEmpty() && other.readProfile("p").doseEvents.isEmpty())
    }

    @Test
    fun deletingObservationAndChatMessage() = runTest {
        val repo = seeded()
        repo.deleteObservation("p", "o")
        repo.deleteChatMessage("p", "msg")
        val data = repo.readProfile("p")
        assertTrue(data.observations.isEmpty() && data.chat.isEmpty())
        assertEquals(1, data.reports.size)
    }

    @Test
    fun deletingProfileRemovesAllItsRecordsAndLeavesOthersUntouched() = runTest {
        val repo = seeded()
        repo.deleteProfile("p")
        assertEquals(listOf("q"), repo.profiles.value.map { it.profileId })
        assertFailsWith<NoSuchElementException> { repo.readProfile("p") }
        assertFailsWith<IllegalArgumentException> { repo.deleteProfile("p") }
    }
}
