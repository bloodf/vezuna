package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.data.VezunaRepository
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.ObservationRevision
import app.vezuna.domain.OcrCandidate
import app.vezuna.domain.ReferenceRange
import app.vezuna.ui.AppScreen
import app.vezuna.ui.ExtractedCandidate
import app.vezuna.ui.PickedSource
import app.vezuna.ui.entry.FieldError
import app.vezuna.ui.entry.ImportFailure
import app.vezuna.ui.entry.ImportFailureReason
import app.vezuna.ui.entry.ImportPhase
import app.vezuna.ui.entry.NumberReading
import app.vezuna.ui.entry.ResultDraft
import app.vezuna.ui.entry.SourceKind
import app.vezuna.ui.entry.corrections
import app.vezuna.ui.entry.needsNumberChoice
import app.vezuna.ui.entry.nextPending
import app.vezuna.ui.entry.readingInput
import app.vezuna.ui.entry.reviewGroups
import app.vezuna.ui.entry.sameDayResults
import app.vezuna.ui.entry.toDraft
import app.vezuna.ui.entry.validate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lane L3: the shared import journey, review helpers and the one result editor's validation. */
class ImportEntryTest {
    private val day = Instant.parse("2026-10-30T12:00:00Z")
    private val numeric = ResultDraft("Hemoglobin", "HGB", false, "13,2", "g/dL", "12", "16", "", day)

    private suspend fun started(): Pair<InMemoryVezunaRepository, app.vezuna.ui.AppController> {
        val repository = InMemoryVezunaRepository()
        val controller = labController(repository)
        controller.initialize()
        return repository to controller
    }

    private fun source(vararg candidates: ExtractedCandidate) =
        PickedSource("october.pdf", "application/pdf", "bytes".encodeToByteArray(), candidates.toList())

    @Test
    fun validDraftsHaveNoErrorsAndDecimalCommaIsANumber() {
        assertTrue(numeric.validate(requireIdentity = true).isEmpty)
        assertTrue(numeric.copy(value = "13.2", lower = "", upper = "").validate(true).isEmpty)
    }

    @Test
    fun everyRuleReportsBesideItsField() {
        val errors = ResultDraft(value = "abc", unit = "", lower = "16", upper = "12").validate(requireIdentity = true)
        assertEquals(FieldError.REQUIRED, errors.name)
        assertEquals(FieldError.REQUIRED, errors.code)
        assertEquals(FieldError.NOT_A_NUMBER, errors.value)
        assertEquals(FieldError.UNIT_REQUIRED, errors.unit)
        assertEquals(FieldError.RANGE_REVERSED, errors.lower)
        assertEquals(FieldError.RANGE_REVERSED, errors.upper)
        assertEquals(FieldError.DATE_REQUIRED, errors.date)
        assertEquals(7, errors.count)
        assertEquals(FieldError.NOT_A_NUMBER, numeric.copy(lower = "low").validate(true).lower)
    }

    @Test
    fun typedAmbiguousThousandsIsRefusedButAnUntouchedPrefillIsNot() {
        assertEquals(FieldError.AMBIGUOUS, numeric.copy(value = "1.020").validate(true).value)
        assertNull(numeric.copy(value = "1.020").validate(true, prefills = listOf("1.020")).value)
    }

    @Test
    fun textResultsNeedOnlyTextAndNeverAUnit() {
        val text = ResultDraft("Urine protein", "UPRO", asText = true, value = "Negative", date = day)
        assertTrue(text.validate(true).isEmpty)
        assertEquals(FieldError.REQUIRED, text.copy(value = " ").validate(true).value)
        assertTrue(text.copy(lower = "x").validate(true).isEmpty, "bounds are not used for a text result")
    }

    @Test
    fun identityIsOptionalWhenTheEditorDoesNotShowIt() {
        assertTrue(numeric.copy(name = "", code = "").validate(requireIdentity = false).isEmpty)
    }

    @Test
    fun eachReadingOfAnAmbiguousTokenIsReadOneWayByTheDomain() = runTest {
        assertEquals("1020", readingInput("1.020", NumberReading.WHOLE))
        assertEquals("1,020", readingInput("1.020", NumberReading.DECIMAL))
        val (repository, controller) = started()
        controller.importSource(source(
            ExtractedCandidate("PLT", "Platelets", "1020", "mil", collectedAt = day, rawToken = "1.020"),
            ExtractedCandidate("SG", "Specific gravity", "1020", "g/mL", collectedAt = day, rawToken = "1.020"),
        ))
        val candidates = repository.readProfile(repository.profiles.value.single().profileId).ocrCandidates
        assertTrue(candidates.all { it.needsNumberChoice })
        val (whole, decimal) = candidates
        controller.confirmCandidate(whole.candidateId, readingInput("1.020", NumberReading.WHOLE), "mil", "", "", day)
        controller.confirmCandidate(decimal.candidateId, readingInput("1.020", NumberReading.DECIMAL), "g/mL", "", "", day)
        assertNull(controller.state.value.error)
        val observations = repository.readProfile(repository.profiles.value.single().profileId).observations
        assertEquals(1020.0, observations.single { it.testCode == "PLT" }.originalValue)
        val gravity = observations.single { it.testCode == "SG" }
        assertEquals(1.02, gravity.originalValue)
        assertEquals(3, gravity.valueDecimals, "the printed precision is kept")
    }

    @Test
    fun cancelledPickerReturnsSilentlyAndStoresNothing() = runTest {
        val (repository, controller) = started()
        controller.importFrom(SourceKind.CAMERA) { null }
        assertEquals(ImportPhase.Idle, controller.importSession.phase.value)
        assertNull(controller.state.value.error)
        assertTrue(repository.readProfile(repository.profiles.value.single().profileId).documents.isEmpty())
    }

    @Test
    fun stoppingBeforeTheSaveBeginsStoresNothing() = runTest {
        val (repository, controller) = started()
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            controller.importFrom(SourceKind.FILE) {
                kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
                source(ExtractedCandidate("GLU", "Fasting glucose", "96", "mg/dL"), ExtractedCandidate("HGB", "Hemoglobin", "13.5", "g/dL"))
            }
        }
        job.join()
        assertEquals(ImportPhase.Idle, controller.importSession.phase.value)
        val data = repository.readProfile(repository.profiles.value.single().profileId)
        assertTrue(data.documents.isEmpty() && data.reports.isEmpty() && data.ocrCandidates.isEmpty())
    }

    @Test
    fun onceSavingBeginsStopIsHiddenAndTheSaveIsAllOrNothing() = runTest {
        val (repository, controller) = started()
        controller.importFrom(SourceKind.FILE) {
            source(ExtractedCandidate("GLU", "Fasting glucose", "96", "mg/dL"), ExtractedCandidate("HGB", "Hemoglobin", "13.5", "g/dL"))
        }
        val data = repository.readProfile(repository.profiles.value.single().profileId)
        assertEquals(2, data.ocrCandidates.size)
        assertEquals(1, data.documents.size)
        val session = app.vezuna.ui.entry.ImportSession()
        session.start(SourceKind.FILE)
        session.reading("a.pdf")
        session.saving()
        assertEquals(ImportPhase.Reading("a.pdf", 0, 0, cancellable = false), session.phase.value)
    }

    @Test
    fun cancellingWhileTheSaveIsUnderwayStillStoresEverythingOrNothing() = runTest {
        val inner = InMemoryVezunaRepository()
        val release = CompletableDeferred<Unit>()
        var added = 0
        val repository = object : VezunaRepository by inner {
            override suspend fun addCandidate(candidate: OcrCandidate) {
                inner.addCandidate(candidate)
                if (++added == 1) release.await()
            }
        }
        val controller = labController(repository)
        controller.initialize()
        val job = launch {
            controller.importFrom(SourceKind.FILE) {
                source(ExtractedCandidate("GLU", "Fasting glucose", "96", "mg/dL"), ExtractedCandidate("HGB", "Hemoglobin", "13.5", "g/dL"))
            }
        }
        runCurrent()
        assertEquals(1, added, "paused after the first candidate")
        job.cancel()
        release.complete(Unit)
        job.join()
        val data = inner.readProfile(inner.profiles.value.single().profileId)
        assertEquals(2, data.ocrCandidates.size)
        assertEquals(1, data.documents.size)
    }

    @Test
    fun pickerFailuresKeepTheirReasonAndTheChosenSource() = runTest {
        val (_, controller) = started()
        controller.importFrom(SourceKind.FILE) { throw ImportFailure(ImportFailureReason.TOO_LARGE, "long-report.pdf") }
        assertEquals(ImportPhase.Failed(ImportFailureReason.TOO_LARGE, "long-report.pdf", SourceKind.FILE), controller.importSession.phase.value)
        controller.importFrom(SourceKind.PHOTOS) { error("decoder crashed") }
        assertEquals(ImportPhase.Failed(ImportFailureReason.UNREADABLE, null, SourceKind.PHOTOS), controller.importSession.phase.value)
    }

    @Test
    fun aSourceWithoutResultsIsNeverRecorded() = runTest {
        val (repository, controller) = started()
        controller.importFrom(SourceKind.PHOTOS) { source() }
        assertEquals(ImportPhase.NoResults("october.pdf", SourceKind.PHOTOS), controller.importSession.phase.value)
        val data = repository.readProfile(repository.profiles.value.single().profileId)
        assertTrue(data.documents.isEmpty() && data.reports.isEmpty() && data.ocrCandidates.isEmpty())
    }

    @Test
    fun successfulImportAddsUnconfirmedDraftsAndOpensTheQueueInPlaceOfTheSourceScreen() = runTest {
        val (repository, controller) = started()
        controller.navigate(AppScreen.ImportSource)
        controller.importFrom(SourceKind.FILE) { source(ExtractedCandidate("GLU", "Fasting glucose", "96", "mg/dL")) }
        assertEquals(ImportPhase.Idle, controller.importSession.phase.value)
        assertEquals(AppScreen.ImportReview, controller.navigator.current)
        controller.back()
        assertEquals(AppScreen.Dashboard, controller.navigator.current, "back from review skips the finished source step")
        val data = repository.readProfile(repository.profiles.value.single().profileId)
        val candidate = data.ocrCandidates.single()
        assertEquals(ConfirmationStatus.UNREVIEWED, candidate.status)
        assertTrue(data.observations.isEmpty(), "nothing is confirmed by importing")
        assertNull(candidate.toDraft(null).date, "the import time is never offered as the collection date")
    }

    @Test
    fun aSourcePickedBeforeAProfileSwitchIsDroppedNotFiledUnderTheNewProfile() = runTest {
        val (repository, controller) = started()
        controller.importFrom(SourceKind.FILE) {
            controller.createProfile("Bruno")
            source(ExtractedCandidate("GLU", "Fasting glucose", "96", "mg/dL", collectedAt = day))
        }
        assertEquals(ImportPhase.Idle, controller.importSession.phase.value)
        assertEquals(app.vezuna.localization.L10nKey.ENTRY_PROFILE_CHANGED, (controller.state.value.notice as app.vezuna.localization.UserText.Key).key)
        repository.profiles.value.forEach { profile ->
            assertTrue(repository.readProfile(profile.profileId).documents.isEmpty(), "nothing imported for ${profile.displayName}")
        }
    }

    @Test
    fun switchingProfileClearsTheImportSession() = runTest {
        val (repository, controller) = started()
        controller.importSession.chooseDate(listOf("c1"), day)
        controller.importSession.confirmed()
        controller.createProfile("Bruno")
        assertTrue(controller.importSession.chosenDates.value.isEmpty())
        assertEquals(0, controller.importSession.confirmedCount.value)
        assertEquals(2, repository.profiles.value.size)
    }

    @Test
    fun duplicateConfirmationIsRecognisedAndTheCandidateStaysInReview() = runTest {
        val (repository, controller) = started()
        controller.addManualResult("HGB", "Hemoglobin", "13.2", "g/dL", "", "", null, day)
        controller.importSource(source(ExtractedCandidate("HGB", "Hemoglobin", "13.2", "g/dL", collectedAt = day)))
        val data = repository.readProfile(repository.profiles.value.single().profileId)
        val candidate = data.ocrCandidates.single()
        assertEquals(1, data.sameDayResults("hgb", day).size)
        controller.confirmCandidate(candidate.candidateId, "13.2", "g/dL", "", "", day)
        assertTrue(controller.lastErrorWasDuplicate)
        val after = repository.readProfile(repository.profiles.value.single().profileId)
        assertEquals(ConfirmationStatus.UNREVIEWED, after.ocrCandidates.single().status)
        assertEquals(1, after.observations.size)
    }

    @Test
    fun reviewGroupsHoldOnlyUnconfirmedDraftsAndNextPrefersTheSameSource() = runTest {
        val (repository, controller) = started()
        controller.importSource(source(
            ExtractedCandidate("GLU", "Fasting glucose", "96", "mg/dL", collectedAt = day),
            ExtractedCandidate("FER", "Ferritin", "42", "ng/mL", collectedAt = day),
            ExtractedCandidate("VITD", "Vitamin D", "31", "ng/mL", collectedAt = day),
        ))
        controller.importSource(PickedSource("other.pdf", "application/pdf", "other".encodeToByteArray(), listOf(ExtractedCandidate("K", "Potassium", "4.4", "mmol/L", collectedAt = day))))
        var data = repository.readProfile(repository.profiles.value.single().profileId)
        val october = data.ocrCandidates.filter { it.testCode != "K" }
        controller.confirmCandidate(october[0].candidateId, "96", "mg/dL", "", "", day)
        controller.rejectCandidate(october[1].candidateId)
        data = repository.readProfile(repository.profiles.value.single().profileId)
        val groups = data.reviewGroups()
        assertEquals(2, groups.size)
        val octoberGroup = groups.single { it.document?.displayName == "october.pdf" }
        assertEquals(listOf("VITD"), octoberGroup.pending.map { it.testCode })
        assertEquals(listOf("FER"), octoberGroup.rejected.map { it.testCode })
        assertEquals("VITD", data.nextPending(october[0].candidateId)?.testCode)
        assertEquals("K", data.nextPending(october[2].candidateId)?.testCode)
    }

    @Test
    fun correctionsListEachEarlierStateWithWhatItBecameNewestFirst() {
        val candidate = OcrCandidate("p", "c", "d", "HGB", "Hemoglobin", "13", "g/dL", collectedAt = day)
        assertFalse(candidate.needsNumberChoice)
        val first = ObservationRevision("r1", Instant.parse("2026-10-29T10:15:00Z"), 13.0, "g/dL", 1, ReferenceRange(12.0, 16.0), day, null)
        val second = ObservationRevision("r2", Instant.parse("2026-10-31T09:00:00Z"), 13.2, "g/dL", 1, ReferenceRange(12.0, 16.0), day, null)
        val observation = app.vezuna.domain.LabObservation(
            "p", "o", "r", "HGB", "Hemoglobin", 13.4, "g/dL", 13.4, "g/dL", ReferenceRange(12.0, 16.0), day,
            ConfirmationStatus.CONFIRMED, valueDecimals = 1, revisions = listOf(first, second),
        )
        val corrections = observation.corrections()
        assertEquals(listOf("r2", "r1").map { id -> listOf(first, second).single { it.revisionId == id }.revisedAt }, corrections.map { it.revisedAt })
        assertTrue(corrections[0].after.startsWith("13") && corrections[0].after.endsWith("4 g/dL"))
        assertTrue(corrections[1].before.endsWith("0 g/dL"))
        assertEquals(corrections[0].before, corrections[1].after, "each state links to the next")
    }

    @Test
    fun qualitativeCandidatesAreNeverOfferedANumberChoice() {
        val text = OcrCandidate("p", "c", "d", "UPRO", "Urine protein", "Negative", "", collectedAt = day, qualitative = true, rawToken = "1.020")
        assertFalse(text.needsNumberChoice)
        assertTrue(text.toDraft(null).asText)
    }
}
