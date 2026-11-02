package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.domain.DoseEvent
import app.vezuna.domain.DoseEventStatus
import app.vezuna.domain.DoseEvents
import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.Medication
import app.vezuna.domain.MedicationSupplies
import app.vezuna.domain.MedicationSupply
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.Recurrence
import app.vezuna.llm.InstalledModel
import app.vezuna.llm.InstalledModelManager
import app.vezuna.llm.LlmGenerationSettings
import app.vezuna.llm.LocalHealthChat
import app.vezuna.llm.LocalLlm
import app.vezuna.llm.ModelCatalog
import app.vezuna.llm.ModelFileStore
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.security.BackupService
import app.vezuna.ui.AppController
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class MedicationSupplyTest {
    private val zone = TimeZone.UTC
    private val counted = Instant.parse("2026-11-01T08:00:00Z")
    private val now = Instant.parse("2026-11-02T10:00:00Z")
    private val today = LocalDate(2026, 11, 2)

    private fun schedule(recurrence: Recurrence = Recurrence.DAILY, times: List<String> = listOf("08:00"), interval: Int = 1, days: Set<DayOfWeek> = emptySet()) =
        DoseSchedule("p", "s", "m", recurrence, interval, days, LocalDate(2026, 10, 1), null, times)

    private fun medication(units: Int = 10, threshold: Int? = null, schedules: List<DoseSchedule> = listOf(schedule()), active: Boolean = true) =
        Medication("p", "m", "Vitamin D", schedules = schedules, active = active, supply = MedicationSupply(units, counted, threshold))

    private fun event(id: String, status: DoseEventStatus, at: Instant = counted.plus(kotlin.time.Duration.parse("1h")), recorded: Instant? = at) =
        DoseEvent("p", id, "m", "s", at, status, recorded)

    @Test
    fun onlyTakenDosesAfterTheCountUseAUnit() {
        val events = listOf(
            event("t", DoseEventStatus.TAKEN),
            event("sk", DoseEventStatus.SKIPPED),
            event("mi", DoseEventStatus.MISSED),
            event("sc", DoseEventStatus.SCHEDULED),
            event("old", DoseEventStatus.TAKEN, recorded = counted.minus(kotlin.time.Duration.parse("1h"))),
        )
        assertEquals(9, MedicationSupplies.remaining(medication(), events))
    }

    @Test
    fun undoingATakenDoseRestoresTheUnit() {
        val taken = event("d1", DoseEventStatus.TAKEN)
        val first = DoseEvents.merge(emptyList(), taken)
        assertEquals(9, MedicationSupplies.remaining(medication(), first))
        val undone = DoseEvents.merge(first, taken.copy(eventId = "d1b", status = DoseEventStatus.SKIPPED))
        assertEquals(10, MedicationSupplies.remaining(medication(), undone))
    }

    @Test
    fun remainingNeverGoesBelowZeroAndIsNullWithoutSupply() {
        val many = (1..5).map { event("e$it", DoseEventStatus.TAKEN, at = counted.plus(kotlin.time.Duration.parse("${it}h"))) }
        assertEquals(0, MedicationSupplies.remaining(medication(units = 2), many))
        assertNull(MedicationSupplies.remaining(medication().copy(supply = null), many))
    }

    @Test
    fun runOutDateFollowsTheScheduleRecurrence() {
        assertEquals(LocalDate(2026, 11, 7), MedicationSupplies.runOutDate(medication(), 6, today))
        assertEquals(LocalDate(2026, 11, 4), MedicationSupplies.runOutDate(medication(schedules = listOf(schedule(times = listOf("08:00", "20:00")))), 6, today))
        assertEquals(LocalDate(2026, 11, 6), MedicationSupplies.runOutDate(medication(schedules = listOf(schedule(Recurrence.EVERY_N_DAYS, interval = 2))), 3, today))
        // Mondays only: 2026-11-02 is a Monday, so 2 units last through the following Monday.
        assertEquals(LocalDate(2026, 11, 9), MedicationSupplies.runOutDate(medication(schedules = listOf(schedule(Recurrence.WEEKLY, days = setOf(DayOfWeek.MONDAY)))), 2, today))
    }

    @Test
    fun noEstimateWhenPausedUnscheduledOrEnded() {
        assertNull(MedicationSupplies.runOutDate(medication(active = false), 5, today))
        assertNull(MedicationSupplies.runOutDate(medication(schedules = emptyList()), 5, today))
        assertNull(MedicationSupplies.runOutDate(medication(schedules = listOf(schedule().copy(endDate = LocalDate(2026, 11, 3)))), 5, today))
    }

    @Test
    fun lowSupplyNoticeIsProjectedForTheCrossingDayThenImmediate() {
        val data = ProfileData(Profile("p", "Me", createdAt = counted), medications = listOf(medication(units = 10, threshold = 3)))
        val notice = MedicationSupplies.notices(data, now, zone).single()
        assertEquals(false, notice.lowNow)
        // 10 units, daily at 08:00: the 7th dose from 2026-11-02 brings it to 3 on 2026-11-08; 09:00 local.
        assertEquals(Instant.parse("2026-11-08T09:00:00Z"), notice.fireAt)
        assertEquals(now.epochSeconds + 60, MedicationSupplies.fireTime(notice.copy(lowNow = true, fireAt = now), null, now))

        val low = ProfileData(Profile("p", "Me", createdAt = counted), medications = listOf(medication(units = 3, threshold = 3)))
        val immediate = MedicationSupplies.notices(low, now, zone).single()
        assertTrue(immediate.lowNow)
        val first = MedicationSupplies.fireTime(immediate, null, now)
        assertEquals(now.epochSeconds + 60, first)
        assertEquals(first, MedicationSupplies.fireTime(immediate, first, now))
        // Already delivered by an earlier pass: not repeated.
        assertNull(MedicationSupplies.fireTime(immediate, now.epochSeconds - 5, now))
    }

    @Test
    fun noNoticeWithoutThresholdOrForPausedMedication() {
        fun notices(m: Medication) = MedicationSupplies.notices(ProfileData(Profile("p", "Me", createdAt = counted), medications = listOf(m)), now, zone)
        assertTrue(notices(medication(threshold = null)).isEmpty())
        assertTrue(notices(medication(threshold = 3, active = false)).isEmpty())
    }

    @Test
    fun editedKeepsTheCountStartWhenUnitsAreUnchanged() {
        val m = medication(units = 10)
        val kept = MedicationSupplies.edited(m, emptyList(), 10, 2, now)
        assertEquals(MedicationSupply(10, counted, 2), kept)
        assertEquals(MedicationSupply(4, now, null), MedicationSupplies.edited(m, emptyList(), 4, null, now))
        assertNull(MedicationSupplies.edited(m, emptyList(), null, null, now))
    }

    @Test
    fun oldDataWithoutSupplyStillLoads() {
        val legacy = """{"profileId":"p","medicationId":"m","displayName":"Aspirin","active":true}"""
        assertNull(Json.decodeFromString(Medication.serializer(), legacy).supply)
    }

    @Test
    fun supplySurvivesABackupRoundTrip() = runTest {
        val data = sampleProfileData("A").let { it.copy(medications = it.medications.map { m -> m.copy(supply = MedicationSupply(12, counted, 4)) }) }
        val service = BackupService()
        val pass = "correct horse battery".toCharArray()
        val opened = service.open(service.create(data, pass.copyOf()), pass.copyOf())
        assertEquals(MedicationSupply(12, counted, 4), opened.members.single().data.medications.single().supply)
        val household = service.open(service.createHousehold(listOf(app.vezuna.security.BackupMember(data)), pass.copyOf()), pass.copyOf())
        assertEquals(data, household.members.single().data)
    }

    // Controller

    private suspend fun controllerWithMedication(): Triple<InMemoryVezunaRepository, AppController, String> {
        val repository = InMemoryVezunaRepository()
        repository.createProfile(Profile("p", "Me", createdAt = now))
        val controller = AppController(
            repository, InstalledModelManager(ModelCatalog.unconfigured(), NoModels, NoLlm), LocalHealthChat(repository, NoLlm),
            clock = { now }, timeZone = { zone },
        )
        controller.initialize()
        controller.addMedication("Vitamin D", "", "", listOf(app.vezuna.ui.ScheduleDraft(times = listOf("08:00"), startDate = today)), "20", "5")
        return Triple(repository, controller, repository.readProfile("p").medications.single().medicationId)
    }

    @Test
    fun formStoresSupplyAndRefillAddsToWhatIsLeft() = runTest {
        val (repository, controller, id) = controllerWithMedication()
        assertEquals(MedicationSupply(20, now, 5), repository.readProfile("p").medications.single().supply)
        repository.addDoseEvent(DoseEvent("p", "d1", id, repository.readProfile("p").medications.single().schedules.single().scheduleId, now, DoseEventStatus.TAKEN, now))
        controller.switchProfile("p")
        controller.recordRefill(id, "30")
        assertEquals(49, repository.readProfile("p").medications.single().supply?.unitsOnHand)
    }

    @Test
    fun invalidSupplyInputIsRejectedAndRefillNeedsTrackedSupply() = runTest {
        val (repository, controller, id) = controllerWithMedication()
        controller.recordRefill(id, "0")
        assertEquals(L10nKey.SUPPLY_REFILL_INVALID, (controller.state.value.error as? UserText.Key)?.key)
        controller.updateMedication(id, "Vitamin D", "", "", listOf(app.vezuna.ui.ScheduleDraft.from(repository.readProfile("p").medications.single().schedules.single())), "x", "")
        assertEquals(L10nKey.SUPPLY_INVALID, (controller.state.value.error as? UserText.Key)?.key)
        controller.updateMedication(id, "Vitamin D", "", "", listOf(app.vezuna.ui.ScheduleDraft.from(repository.readProfile("p").medications.single().schedules.single())), "", "")
        assertNull(repository.readProfile("p").medications.single().supply)
        controller.recordRefill(id, "5")
        assertEquals(L10nKey.SUPPLY_NOT_TRACKED, (controller.state.value.error as? UserText.Key)?.key)
        assertNotNull(repository.readProfile("p").medications.single())
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
