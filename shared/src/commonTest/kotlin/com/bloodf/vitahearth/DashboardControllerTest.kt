package com.bloodf.vitahearth

import com.bloodf.vitahearth.data.InMemoryVitaHearthRepository
import com.bloodf.vitahearth.domain.DoseEventStatus
import com.bloodf.vitahearth.domain.DoseSchedule
import com.bloodf.vitahearth.domain.Medication
import com.bloodf.vitahearth.domain.Profile
import com.bloodf.vitahearth.domain.Recurrence
import com.bloodf.vitahearth.llm.InstalledModel
import com.bloodf.vitahearth.llm.InstalledModelManager
import com.bloodf.vitahearth.llm.LlmGenerationSettings
import com.bloodf.vitahearth.llm.LocalHealthChat
import com.bloodf.vitahearth.llm.LocalLlm
import com.bloodf.vitahearth.llm.ModelCatalog
import com.bloodf.vitahearth.llm.ModelFileStore
import com.bloodf.vitahearth.platform.currentInstant
import com.bloodf.vitahearth.ui.AppController
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DashboardControllerTest {
    @Test
    fun takenSkipAndSnoozePersistProfileScopedEventsWithoutDuplicates() = runTest {
        val repository = InMemoryVitaHearthRepository()
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
        val repository = InMemoryVitaHearthRepository()
        repository.createProfile(Profile("p", "Current", createdAt = currentInstant()))
        repository.addMedication(medication("p"))
        val controller = AppController(repository, InstalledModelManager(ModelCatalog.unconfigured(), EmptyModels, FakeLlm), LocalHealthChat(repository, FakeLlm))
        controller.initialize()
        val next = requireNotNull(controller.state.value.dashboard?.nextDose)
        repository.addDoseEvent(
            com.bloodf.vitahearth.domain.DoseEvent(
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
        override suspend fun write(model: InstalledModel, bytes: ByteArray) = Unit
        override suspend fun readBytes(model: InstalledModel): ByteArray = ByteArray(0)
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
