package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.domain.ProfileData
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.ui.AppController
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabResultEditTest {
    private val day1 = Instant.parse("2026-03-10T12:00:00Z")
    private val day2 = Instant.parse("2026-04-10T12:00:00Z")

    private suspend fun start(): Triple<InMemoryVezunaRepository, AppController, String> {
        val repository = InMemoryVezunaRepository()
        val controller = labController(repository)
        controller.initialize()
        return Triple(repository, controller, repository.profiles.value.single().profileId)
    }

    private suspend fun AppController.manual(value: String, date: Instant, code: String = "HGB", unit: String = "g/dL", range: String? = null) =
        addManualResult(code, code, value, unit, "12", "15,5", range, date)

    @Test
    fun editAppendsARevisionWithTheEarlierValues() = runTest {
        val (repository, controller, profileId) = start()
        controller.manual("13,4", day1)
        val id = repository.readProfile(profileId).observations.single().observationId

        controller.editObservation(id, "14,20", "g/dL", "11", "16", "11 a 16 g/dL", day2)

        assertNull(controller.state.value.error)
        val edited = repository.readProfile(profileId).observations.single()
        assertEquals(14.2, edited.originalValue)
        assertEquals(2, edited.valueDecimals)
        assertEquals(142.0, edited.normalizedValue)
        assertEquals(day2, edited.collectedAt)
        assertEquals(11.0, edited.referenceRange.lower)
        assertEquals("11 a 16 g/dL", edited.referenceRange.text)
        val revision = edited.revisions.single()
        assertEquals(13.4, revision.previousValue)
        assertEquals("g/dL", revision.previousUnit)
        assertEquals(day1, revision.previousCollectedAt)
        assertEquals(12.0, revision.previousReferenceRange.lower)
        assertEquals(15.5, revision.previousReferenceRange.upper)
        assertEquals(L10nKey.RESULT_UPDATED, (controller.state.value.notice as UserText.Key).key)
    }

    @Test
    fun everyEditAddsOneRevisionOldestFirst() = runTest {
        val (repository, controller, profileId) = start()
        controller.manual("10", day1)
        val id = repository.readProfile(profileId).observations.single().observationId
        controller.editObservation(id, "11", "g/dL", "12", "15,5", null, day1)
        controller.editObservation(id, "12", "g/dL", "12", "15,5", null, day1)
        assertEquals(listOf(10.0, 11.0), repository.readProfile(profileId).observations.single().revisions.map { it.previousValue })
    }

    @Test
    fun savingWithoutChangesAddsNoRevision() = runTest {
        val (repository, controller, profileId) = start()
        controller.manual("13,4", day1)
        val id = repository.readProfile(profileId).observations.single().observationId
        controller.editObservation(id, "13,4", "g/dL", "12", "15,5", null, day1)
        assertTrue(repository.readProfile(profileId).observations.single().revisions.isEmpty())
        assertEquals(L10nKey.RESULT_UNCHANGED, (controller.state.value.notice as UserText.Key).key)
    }

    @Test
    fun editRerunsDuplicateDetectionAgainstOtherResults() = runTest {
        val (repository, controller, profileId) = start()
        controller.manual("13", day1)
        controller.manual("13", day2)
        val later = repository.readProfile(profileId).observations.first { it.collectedAt == day2 }

        controller.editObservation(later.observationId, "13", "g/dL", "12", "15,5", null, day1)

        assertEquals(L10nKey.DUPLICATE_CLINICAL_RESULT, (controller.state.value.error as UserText.Key).key)
        val unchanged = repository.readProfile(profileId).observations.first { it.observationId == later.observationId }
        assertEquals(day2, unchanged.collectedAt)
        assertTrue(unchanged.revisions.isEmpty())
    }

    @Test
    fun repositoryRefusesAnUpdateThatDuplicatesAnotherResult() = runTest {
        val (repository, controller, profileId) = start()
        controller.manual("13", day1)
        controller.manual("14", day1)
        val data = repository.readProfile(profileId)
        val second = data.observations.first { it.originalValue == 14.0 }
        val clash = second.copy(originalValue = 13.0, normalizedValue = 130.0)
        kotlin.test.assertFailsWith<IllegalArgumentException> { repository.updateObservation(clash) }
    }

    @Test
    fun textResultsEditTheirTextAndDateIsRequired() = runTest {
        val (repository, controller, profileId) = start()
        controller.addManualResult("HIV", "HIV", "Negativo", "", "", "", null, day1, asText = true)
        val id = repository.readProfile(profileId).observations.single().observationId

        controller.editObservation(id, "Positivo", "", "", "", null, null)
        assertEquals(L10nKey.COLLECTION_DATE_REQUIRED, (controller.state.value.error as UserText.Key).key)

        controller.editObservation(id, "Positivo", "", "", "", null, day1)
        val edited = repository.readProfile(profileId).observations.single()
        assertEquals("Positivo", edited.qualitativeValue)
        assertEquals("Negativo", edited.revisions.single().previousQualitativeValue)
    }

    @Test
    fun revisionsSurviveBackupSerialization() = runTest {
        val (repository, controller, profileId) = start()
        controller.manual("10", day1)
        controller.editObservation(repository.readProfile(profileId).observations.single().observationId, "11", "g/dL", "", "", null, day1)
        val data = repository.readProfile(profileId)
        val json = Json { encodeDefaults = true }
        val restored = json.decodeFromString(ProfileData.serializer(), json.encodeToString(ProfileData.serializer(), data))
        assertEquals(data.observations.single().revisions, restored.observations.single().revisions)
        assertTrue(restored.observations.single().revisions.isNotEmpty())
    }
}
