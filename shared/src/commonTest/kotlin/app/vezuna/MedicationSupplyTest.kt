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
import app.vezuna.domain.withMedication
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
    private val early = Instant.parse("2026-11-02T06:00:00Z") // before the 08:00 dose

    private fun schedule(recurrence: Recurrence = Recurrence.DAILY, times: List<String> = listOf("08:00"), interval: Int = 1, days: Set<DayOfWeek> = emptySet()) =
        DoseSchedule("p", "s", "m", recurrence, interval, days, LocalDate(2026, 10, 1), null, times)

    private fun medication(units: Int = 10, threshold: Int? = null, schedules: List<DoseSchedule> = listOf(schedule()), active: Boolean = true) =
        Medication("p", "m", "Vitamin D", schedules = schedules, active = active, supply = MedicationSupply(units, counted, threshold))

    private fun event(id: String, status: DoseEventStatus, at: Instant = counted.plus(kotlin.time.Duration.parse("1h")), recorded: Instant? = at) =
        DoseEvent("p", id, "m", "s", at, status, recorded)

    @Test
    fun aSnoozedDoseTakenTwiceUsesOneUnit() {
        val original = counted.plus(kotlin.time.Duration.parse("1h"))
        val snoozedAt = original.plus(kotlin.time.Duration.parse("10m"))
        val snoozedAgain = snoozedAt.plus(kotlin.time.Duration.parse("10m"))
        val snooze = DoseEvent("p", "sn", "m", "s", snoozedAt, DoseEventStatus.SCHEDULED, original, replacesScheduledFor = original)
        val snoozeAgain = DoseEvent("p", "sn2", "m", "s", snoozedAgain, DoseEventStatus.SCHEDULED, snoozedAt, replacesScheduledFor = snoozedAt)
        val med = medication(units = 10)
        // notification TAKEN at the original time plus a later TAKEN at the snoozed time: one dose
        assertEquals(9, MedicationSupplies.remaining(med, listOf(snooze, event("a", DoseEventStatus.TAKEN, original), event("b", DoseEventStatus.TAKEN, snoozedAt, snoozedAt))))
        // chained snoozes collapse to the original too
        assertEquals(9, MedicationSupplies.remaining(med, listOf(snooze, snoozeAgain, event("a", DoseEventStatus.TAKEN, original), event("c", DoseEventStatus.TAKEN, snoozedAgain, snoozedAgain))))
        // another schedule at the same time is a different dose
        val other = event("o", DoseEventStatus.TAKEN, original).copy(scheduleId = "s2")
        assertEquals(8, MedicationSupplies.remaining(med, listOf(snooze, event("a", DoseEventStatus.TAKEN, original), other)))
        // the snoozed dose alone still uses its unit
        assertEquals(9, MedicationSupplies.remaining(med, listOf(snooze, event("b", DoseEventStatus.TAKEN, snoozedAt, snoozedAt))))
        // a different dose of the same schedule is a different unit
        assertEquals(8, MedicationSupplies.remaining(med, listOf(snooze, event("a", DoseEventStatus.TAKEN, original), event("d", DoseEventStatus.TAKEN, original.plus(kotlin.time.Duration.parse("24h"))))))
    }

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
        assertEquals(LocalDate(2026, 11, 7), MedicationSupplies.runOutDate(medication(), 6, early, zone))
        assertEquals(LocalDate(2026, 11, 4), MedicationSupplies.runOutDate(medication(schedules = listOf(schedule(times = listOf("08:00", "20:00")))), 6, early, zone))
        assertEquals(LocalDate(2026, 11, 6), MedicationSupplies.runOutDate(medication(schedules = listOf(schedule(Recurrence.EVERY_N_DAYS, interval = 2))), 3, early, zone))
        // Mondays only: 2026-11-02 is a Monday, so 2 units last through the following Monday.
        assertEquals(LocalDate(2026, 11, 9), MedicationSupplies.runOutDate(medication(schedules = listOf(schedule(Recurrence.WEEKLY, days = setOf(DayOfWeek.MONDAY)))), 2, early, zone))
    }

    @Test
    fun todaysDosesAlreadyPastAreNotCountedAhead() {
        // 10:00: the 08:00 dose is behind, so 6 units last through the dose on 2026-11-08.
        assertEquals(LocalDate(2026, 11, 8), MedicationSupplies.runOutDate(medication(), 6, now, zone))
        assertEquals(LocalDate(2026, 11, 5), MedicationSupplies.runOutDate(medication(schedules = listOf(schedule(times = listOf("08:00", "20:00")))), 6, now, zone))
        // Dose taken this morning (remaining 3 of 4, threshold 2): not low yet, notice waits for tomorrow.
        val taken = ProfileData(
            Profile("p", "Me", createdAt = counted),
            medications = listOf(medication(units = 4, threshold = 2)),
            doseEvents = listOf(event("t", DoseEventStatus.TAKEN, at = Instant.parse("2026-11-02T08:00:00Z"))),
        )
        val notice = MedicationSupplies.notices(taken, now, zone).single()
        assertEquals(3, notice.remaining)
        assertEquals(false, notice.lowNow)
        assertEquals(Instant.parse("2026-11-03T09:00:00Z"), notice.fireAt)
    }

    @Test
    fun removingAScheduleKeepsItsTakenDosesCountedAsUsed() {
        val extra = schedule().copy(scheduleId = "s2")
        val m = medication(units = 10, schedules = listOf(schedule(), extra))
        val data = ProfileData(
            Profile("p", "Me", createdAt = counted),
            medications = listOf(m),
            doseEvents = listOf(event("a", DoseEventStatus.TAKEN), event("b", DoseEventStatus.TAKEN).copy(scheduleId = "s2")),
        )
        assertEquals(8, MedicationSupplies.remaining(m, data.doseEvents))
        val after = data.withMedication(m.copy(schedules = listOf(schedule())))
        assertEquals(8, MedicationSupplies.remaining(after.medications.single(), after.doseEvents))
    }

    @Test
    fun unchangedUnitsAreComparedWithWhatTheFormShowed() {
        val m = medication(units = 10)
        val takenWhileOpen = listOf(event("t", DoseEventStatus.TAKEN))
        // The form showed 10; a dose was taken meanwhile; saving "10" keeps the count start, so the dose stays counted.
        val saved = MedicationSupplies.edited(m, takenWhileOpen, 10, null, now, shown = 10)
        assertEquals(counted, saved?.countedAt)
    }

    @Test
    fun noEstimateWhenPausedUnscheduledOrEnded() {
        assertNull(MedicationSupplies.runOutDate(medication(active = false), 5, now, zone))
        assertNull(MedicationSupplies.runOutDate(medication(schedules = emptyList()), 5, now, zone))
        assertNull(MedicationSupplies.runOutDate(medication(schedules = listOf(schedule().copy(endDate = LocalDate(2026, 11, 3)))), 5, now, zone))
    }

    @Test
    fun lowSupplyNoticeIsProjectedForTheCrossingDayThenImmediate() {
        val data = ProfileData(Profile("p", "Me", createdAt = counted), medications = listOf(medication(units = 10, threshold = 3)))
        val notice = MedicationSupplies.notices(data, now, zone).single()
        assertEquals(false, notice.lowNow)
        // 10 units, daily at 08:00: the 7th dose from 2026-11-02 brings it to 3 on 2026-11-08; 09:00 local.
        assertEquals(Instant.parse("2026-11-09T09:00:00Z"), notice.fireAt)
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

    @Test
    fun refillAboveTheLimitIsRejected() = runTest {
        val (repository, controller, id) = controllerWithMedication()
        controller.recordRefill(id, "100000")
        assertEquals(L10nKey.SUPPLY_REFILL_INVALID, (controller.state.value.error as? UserText.Key)?.key)
        assertEquals(20, repository.readProfile("p").medications.single().supply?.unitsOnHand)
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
