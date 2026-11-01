package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.domain.DoseEvent
import app.vezuna.domain.DoseEventStatus
import app.vezuna.domain.DoseEvents
import app.vezuna.domain.Profile
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
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MedicationManagementTest {
    private val now = Instant.parse("2026-11-02T01:00:00Z") // 2026-11-01 22:00 in Sao Paulo
    private val today = LocalDate(2026, 11, 1)

    private suspend fun setup(): Triple<InMemoryVezunaRepository, AppController, String> {
        val repository = InMemoryVezunaRepository()
        repository.createProfile(Profile("p", "Me", createdAt = now))
        val controller = AppController(
            repository, InstalledModelManager(ModelCatalog.unconfigured(), NoModels, NoLlm), LocalHealthChat(repository, NoLlm),
            clock = { now }, timeZone = { TimeZone.of("America/Sao_Paulo") },
        )
        controller.initialize()
        controller.addMedication("Aspirin", "100 mg", "with food", "23:00")
        return Triple(repository, controller, repository.readProfile("p").medications.single().medicationId)
    }

    private fun AppController.errorKey() = (state.value.error as? UserText.Key)?.key

    @Test
    fun editChangesFieldsAndReminderTime() = runTest {
        val (repository, controller, id) = setup()
        controller.updateMedication(id, " Ibuprofen ", "", "daily", "07:30")
        val medication = repository.readProfile("p").medications.single()
        assertEquals("Ibuprofen", medication.displayName)
        assertNull(medication.strength)
        assertEquals(listOf("07:30"), medication.schedules.single().timesLocal)
        controller.updateMedication(id, "x", "", "", "25:00")
        assertEquals(L10nKey.INVALID_REMINDER_TIME, controller.errorKey())
    }

    @Test
    fun pauseRemovesDoseFromDashboardAndResumeRestoresIt() = runTest {
        val (repository, controller, id) = setup()
        assertNotNull(controller.state.value.dashboard?.nextDose)
        controller.setMedicationActive(id, false)
        assertEquals(false, repository.readProfile("p").medications.single().active)
        assertNull(controller.state.value.dashboard?.nextDose)
        controller.setMedicationActive(id, true)
        assertNotNull(controller.state.value.dashboard?.nextDose)
    }

    @Test
    fun stopSetsEndDateTodayDeactivatesAndResumeClearsIt() = runTest {
        val (repository, controller, id) = setup()
        controller.stopMedication(id)
        val stopped = repository.readProfile("p").medications.single()
        assertEquals(false, stopped.active)
        assertEquals(today, stopped.schedules.single().endDate)
        assertNull(controller.state.value.dashboard?.nextDose)
        controller.setMedicationActive(id, true)
        assertNull(repository.readProfile("p").medications.single().schedules.single().endDate)
    }

    @Test
    fun deleteRemovesMedicationAndItsDoseHistory() = runTest {
        val (repository, controller, id) = setup()
        val schedule = repository.readProfile("p").medications.single().schedules.single()
        repository.addDoseEvent(DoseEvent("p", DoseEvents.idFor(schedule.scheduleId, now), id, schedule.scheduleId, now, DoseEventStatus.TAKEN, now))
        controller.deleteMedication(id)
        val data = repository.readProfile("p")
        assertTrue(data.medications.isEmpty() && data.doseEvents.isEmpty())
        controller.deleteMedication(id)
        assertEquals(L10nKey.MEDICATION_NOT_FOUND, controller.errorKey())
    }

    private object NoModels : ModelFileStore {
        override suspend fun installed(): List<InstalledModel> = emptyList()
        override suspend fun install(model: InstalledModel, sourcePath: String) = Unit
        override suspend fun verifyIntegrity(model: InstalledModel): Boolean = true
        override suspend fun delete(model: InstalledModel) = Unit
        override suspend fun saveSelection(modelId: String?) = Unit
        override suspend fun savedSelection(): String? = null
    }

    private object NoLlm : LocalLlm {
        override suspend fun load(modelPath: String, settings: LlmGenerationSettings) = Unit
        override suspend fun generate(prompt: String): String = ""
        override suspend fun unload() = Unit
    }
}
