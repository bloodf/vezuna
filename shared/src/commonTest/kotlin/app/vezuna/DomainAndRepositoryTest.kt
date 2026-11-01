package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.domain.ChangeDirection
import app.vezuna.domain.ClinicalDuplicateDetector
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.LabComparator
import app.vezuna.domain.LabObservation
import app.vezuna.domain.LabReport
import app.vezuna.domain.MedicationRecurrence
import app.vezuna.domain.Profile
import app.vezuna.domain.RangeAssessment
import app.vezuna.domain.Recurrence
import app.vezuna.domain.ReferenceRange
import app.vezuna.domain.SourceDocument
import app.vezuna.domain.UnitNormalizer
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DomainAndRepositoryTest {
    private val time = Instant.parse("2026-01-10T10:00:00Z")

    @Test
    fun profileIsolationIncludesChildrenAndDuplicateScope() = runTest {
        val repository = InMemoryVezunaRepository()
        repository.createProfile(Profile("alice", "Alice", createdAt = time))
        repository.createProfile(Profile("bob", "Bob", createdAt = time))
        listOf("alice", "bob").forEach { profileId ->
            repository.addDocument(SourceDocument(profileId, "document", "result.pdf", "application/pdf", "ab", time))
            repository.addReport(LabReport(profileId, "report", "document", importedAt = time))
        }
        val observation = observation("alice")
        assertTrue(repository.addObservation(observation))
        assertTrue(repository.addObservation(observation.copy(profileId = "bob")))
        assertFalse(repository.addObservation(observation.copy(observationId = "another")))
        assertEquals(listOf("alice"), repository.readProfile("alice").observations.map { it.profileId })
        assertEquals(listOf("bob"), repository.readProfile("bob").observations.map { it.profileId })
        assertFalse(repository.readProfile("alice").reports.any { it.profileId == "bob" })
    }

    @Test
    fun unitConversionComparisonAndRangeAreDeterministic() {
        val normalized = UnitNormalizer.normalize(13.5, " g/dL ")
        assertEquals(135.0, normalized.value)
        assertEquals("g/L", normalized.unit)
        val previous = observation("alice", value = 12.0, unit = "g/L")
        val current = observation("alice", value = 13.0, unit = "g/L")
        assertEquals(ChangeDirection.INCREASE, LabComparator.compare(previous, current).direction)
        assertEquals(RangeAssessment.ABOVE, LabComparator.assess(150.0, ReferenceRange(120.0, 140.0)))
        val convertedRange = current.copy(
            originalValue = 1.3,
            originalUnit = "g/dL",
            normalizedValue = 13.0,
            normalizedUnit = "g/L",
            referenceRange = ReferenceRange(1.2, 1.4, unit = "g/dL"),
        )
        assertEquals(RangeAssessment.WITHIN, LabComparator.assess(convertedRange))
        assertEquals(ChangeDirection.NOT_COMPARABLE, LabComparator.compare(previous, current.copy(normalizedUnit = "mg/L")).direction)
    }

    @Test
    fun duplicateFingerprintIgnoresSourceFormattingButNotClinicalFields() {
        val first = observation("alice")
        val same = first.copy(observationId = "other", displayName = "Hemoglobin", originalUnit = "g/dL")
        assertTrue(ClinicalDuplicateDetector.areDuplicates(first, same))
        assertFalse(ClinicalDuplicateDetector.areDuplicates(first, same.copy(profileId = "bob")))
        assertFalse(ClinicalDuplicateDetector.areDuplicates(first, same.copy(collectedAt = Instant.parse("2026-01-11T10:00:00Z"))))
        assertFalse(ClinicalDuplicateDetector.areDuplicates(first, same.copy(normalizedValue = 131.0)))
    }

    @Test
    fun medicationRecurrenceHonorsBoundsWeekdaysAndIntervals() {
        val start = LocalDate(2026, 1, 5)
        val weekly = DoseSchedule("p", "s", "m", Recurrence.WEEKLY, daysOfWeek = setOf(DayOfWeek.MONDAY), startDate = start, endDate = LocalDate(2026, 1, 19), timesLocal = listOf("08:00"))
        assertTrue(MedicationRecurrence.occursOn(weekly, start))
        assertFalse(MedicationRecurrence.occursOn(weekly, LocalDate(2026, 1, 6)))
        assertFalse(MedicationRecurrence.occursOn(weekly, LocalDate(2026, 1, 26)))
        val everyThreeDays = weekly.copy(recurrence = Recurrence.EVERY_N_DAYS, intervalDays = 3, daysOfWeek = emptySet(), endDate = null)
        assertTrue(MedicationRecurrence.occursOn(everyThreeDays, LocalDate(2026, 1, 8)))
        assertFalse(MedicationRecurrence.occursOn(everyThreeDays, LocalDate(2026, 1, 9)))
    }

    private fun observation(profileId: String, value: Double = 130.0, unit: String = "g/L") = LabObservation(
        profileId = profileId,
        observationId = "observation",
        reportId = "report",
        testCode = "HGB",
        displayName = "Hemoglobin",
        originalValue = value,
        originalUnit = unit,
        normalizedValue = value,
        normalizedUnit = unit,
        referenceRange = ReferenceRange(120.0, 140.0),
        collectedAt = time,
        confirmation = ConfirmationStatus.CONFIRMED,
    )
}
