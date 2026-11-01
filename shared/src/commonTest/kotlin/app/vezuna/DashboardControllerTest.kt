package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.domain.DoseEventStatus
import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.Medication
import app.vezuna.domain.Profile
import app.vezuna.domain.Recurrence
import app.vezuna.llm.InstalledModel
import app.vezuna.llm.InstalledModelManager
import app.vezuna.llm.LlmGenerationSettings
import app.vezuna.llm.LocalHealthChat
import app.vezuna.llm.LocalLlm
import app.vezuna.llm.ModelCatalog
import app.vezuna.llm.ModelFileStore
import app.vezuna.platform.currentInstant
import app.vezuna.ui.AppController
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DashboardControllerTest {
    @Test
    fun takenSkipAndSnoozePersistProfileScopedEventsWithoutDuplicates() = runTest {
        val repository = InMemoryVezunaRepository()
        repository.createProfile(Profile("p", "Current", createdAt = currentInstant()))
        repository.addMedication(medication("p"))
        repository.createProfile(Profile("other", "Other", createdAt = currentInstant()))
        repository.addMedication(medication("other"))
        val controller = AppController(repository, InstalledModelManager(ModelCatalog.unconfigured(), EmptyModels, FakeLlm), LocalHealthChat(repository, FakeLlm))
        controller.initialize()
        val next = requireNotNull(controller.state.value.dashboard?.nextDose)
        controller.takeDose(next)
        controller.takeDose(next)
        controller.skipDose(next)
        val afterTaken = repository.readProfile("p").doseEvents
        assertEquals(1, afterTaken.count { it.status == DoseEventStatus.TAKEN })
        assertEquals(0, afterTaken.count { it.status == DoseEventStatus.SKIPPED })
        val beforeSnooze = currentInstant()
        controller.snoozeDose(next)
        val afterSnooze = currentInstant()
        val snooze = repository.readProfile("p").doseEvents.single { it.status == DoseEventStatus.SCHEDULED && it.replacesScheduledFor == next.scheduledFor }
        assertEquals("p", snooze.profileId)
        assertEquals(next.medicationId, snooze.medicationId)
        assertEquals(next.scheduledFor, snooze.replacesScheduledFor)
        assertTrue(snooze.scheduledFor.epochSeconds >= beforeSnooze.epochSeconds + 600)
        assertTrue(snooze.scheduledFor.epochSeconds <= afterSnooze.epochSeconds + 600)
        assertTrue(repository.readProfile("other").doseEvents.isEmpty())
    }

    @Test
    fun refreshDashboardReloadsExternalDoseChanges() = runTest {
        val repository = InMemoryVezunaRepository()
        repository.createProfile(Profile("p", "Current", createdAt = currentInstant()))
        repository.addMedication(medication("p"))
        val controller = AppController(repository, InstalledModelManager(ModelCatalog.unconfigured(), EmptyModels, FakeLlm), LocalHealthChat(repository, FakeLlm))
        controller.initialize()
        val next = requireNotNull(controller.state.value.dashboard?.nextDose)
        repository.addDoseEvent(
            app.vezuna.domain.DoseEvent(
                profileId = next.profileId,
                eventId = "external-taken",
                medicationId = next.medicationId,
                scheduleId = next.scheduleId,
                scheduledFor = next.scheduledFor,
                status = DoseEventStatus.TAKEN,
                recordedAt = currentInstant(),
            ),
        )
        assertEquals(next.scheduledFor, controller.state.value.dashboard?.nextDose?.scheduledFor)

        controller.refreshDashboard()

        assertTrue(requireNotNull(controller.state.value.dashboard?.nextDose).scheduledFor > next.scheduledFor)
    }

    @Test
    fun newMedicationStartsOnLocalDateAtTenPmInSaoPaulo() = runTest {
        val repository = InMemoryVezunaRepository()
        repository.createProfile(Profile("p", "Current", createdAt = currentInstant()))
        // 22:00 on 2026-11-01 in UTC-3 is already 2026-11-02 in UTC.
        val controller = AppController(
            repository,
            InstalledModelManager(ModelCatalog.unconfigured(), EmptyModels, FakeLlm),
            LocalHealthChat(repository, FakeLlm),
            clock = { Instant.parse("2026-11-02T01:00:00Z") },
            timeZone = { TimeZone.of("America/Sao_Paulo") },
        )
        controller.initialize()
        controller.addMedication("Evening", "", "", "22:30")

        val start = repository.readProfile("p").medications.single().schedules.single().startDate
        assertEquals(LocalDate(2026, 11, 1), start)
        assertEquals(Instant.parse("2026-11-02T01:30:00Z"), controller.state.value.dashboard?.nextDose?.scheduledFor)
    }

    private fun medication(profileId: String) = Medication(
        profileId = profileId,
        medicationId = "med",
        displayName = "Medication",
        schedules = listOf(
            DoseSchedule(
                profileId = profileId,
                scheduleId = "schedule",
                medicationId = "med",
                recurrence = Recurrence.DAILY,
                startDate = LocalDate(2026, 1, 1),
                timesLocal = listOf("23:59"),
            ),
        ),
    )

    private object EmptyModels : ModelFileStore {
        override suspend fun installed(): List<InstalledModel> = emptyList()
        override suspend fun install(model: InstalledModel, sourcePath: String) = Unit
        override suspend fun verifyIntegrity(model: InstalledModel): Boolean = true
        override suspend fun delete(model: InstalledModel) = Unit
        override suspend fun saveSelection(modelId: String?) = Unit
        override suspend fun savedSelection(): String? = null
    }

    private object FakeLlm : LocalLlm {
        override suspend fun load(modelPath: String, settings: LlmGenerationSettings) = Unit
        override suspend fun generate(prompt: String): String = ""
        override suspend fun unload() = Unit
    }
}
