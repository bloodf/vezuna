package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.domain.Profile
import app.vezuna.domain.Recurrence
import app.vezuna.llm.InstalledModel
import app.vezuna.llm.InstalledModelManager
import app.vezuna.llm.LlmGenerationSettings
import app.vezuna.llm.LocalLlm
import app.vezuna.llm.ModelFileStore
import app.vezuna.llm.LocalHealthChat
import app.vezuna.llm.ModelCatalog
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.ui.AppController
import app.vezuna.ui.ScheduleDraft
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduleDraftTest {
    private val start = LocalDate(2026, 11, 1)
    private fun draft(
        recurrence: Recurrence = Recurrence.DAILY,
        interval: String = "2",
        days: Set<DayOfWeek> = emptySet(),
        times: List<String> = listOf("08:00"),
        end: LocalDate? = null,
    ) = ScheduleDraft(recurrence = recurrence, intervalDays = interval, daysOfWeek = days, times = times, startDate = start, endDate = end)

    @Test
    fun validDraftsHaveNoErrors() {
        assertEquals(emptyList(), draft(times = listOf("08:00", "20:00")).errors())
        assertEquals(emptyList(), draft(Recurrence.WEEKLY, days = setOf(DayOfWeek.MONDAY)).errors())
        assertEquals(emptyList(), draft(Recurrence.EVERY_N_DAYS, interval = "3", end = start).errors())
    }

    @Test
    fun invalidDraftsReportInlineErrors() {
        assertEquals(listOf(L10nKey.SCHEDULE_TIMES_REQUIRED), draft(times = emptyList()).errors())
        assertEquals(listOf(L10nKey.INVALID_REMINDER_TIME), draft(times = listOf("25:00")).errors())
        assertEquals(listOf(L10nKey.SCHEDULE_TIME_DUPLICATE), draft(times = listOf("08:00", "08:00")).errors())
        assertEquals(listOf(L10nKey.SCHEDULE_WEEKDAYS_REQUIRED), draft(Recurrence.WEEKLY).errors())
        assertEquals(listOf(L10nKey.SCHEDULE_INTERVAL_INVALID), draft(Recurrence.EVERY_N_DAYS, interval = "0").errors())
        assertEquals(listOf(L10nKey.SCHEDULE_INTERVAL_INVALID), draft(Recurrence.EVERY_N_DAYS, interval = "x").errors())
        assertEquals(listOf(L10nKey.SCHEDULE_END_BEFORE_START), draft(end = LocalDate(2026, 10, 31)).errors())
    }

    @Test
    fun irrelevantFieldsDoNotBlockOtherRecurrences() {
        assertEquals(emptyList(), draft(Recurrence.DAILY, interval = "x").errors())
    }

    @Test
    fun controllerSavesAndEditsMultipleSchedulesKeepingExistingIds() = runTest {
        val now = Instant.parse("2026-11-02T01:00:00Z")
        val repository = InMemoryVezunaRepository()
        repository.createProfile(Profile("p", "Me", createdAt = now))
        val controller = AppController(
            repository, InstalledModelManager(ModelCatalog.unconfigured(), NoModels, NoLlm), LocalHealthChat(repository, NoLlm),
            clock = { now }, timeZone = { TimeZone.of("America/Sao_Paulo") },
        )
        controller.initialize()
        controller.addMedication("Aspirin", "", "", listOf(draft(times = listOf("08:00", "20:00")), draft(Recurrence.WEEKLY, days = setOf(DayOfWeek.FRIDAY))))
        val saved = repository.readProfile("p").medications.single()
        assertEquals(2, saved.schedules.size)
        assertEquals(listOf("08:00", "20:00"), saved.schedules[0].timesLocal)
        assertEquals(setOf(DayOfWeek.FRIDAY), saved.schedules[1].daysOfWeek)

        val keep = ScheduleDraft.from(saved.schedules[0]).copy(times = listOf("09:00"), endDate = LocalDate(2026, 12, 1))
        controller.updateMedication(saved.medicationId, "Aspirin", "", "", listOf(keep))
        val edited = repository.readProfile("p").medications.single()
        assertEquals(listOf(saved.schedules[0].scheduleId), edited.schedules.map { it.scheduleId })
        assertEquals(listOf("09:00"), edited.schedules.single().timesLocal)
        assertEquals(LocalDate(2026, 12, 1), edited.schedules.single().endDate)

        controller.updateMedication(saved.medicationId, "Aspirin", "", "", listOf(keep.copy(times = listOf("08:00", "08:00"))))
        assertEquals(L10nKey.SCHEDULE_TIME_DUPLICATE, (controller.state.value.error as? UserText.Key)?.key)
        assertEquals(listOf("09:00"), repository.readProfile("p").medications.single().schedules.single().timesLocal)

        controller.updateMedication(saved.medicationId, "Aspirin", "", "", emptyList())
        assertEquals(L10nKey.SCHEDULE_TIMES_REQUIRED, (controller.state.value.error as? UserText.Key)?.key)
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
