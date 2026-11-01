package app.vezuna

import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabObservation
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.RangeAssessment
import app.vezuna.domain.ReferenceRange
import app.vezuna.export.DoctorExamSections
import app.vezuna.export.DoctorExportContent
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DoctorExamSectionsTest {
    @Test
    fun groupsCanonicalCodeAndUnitAndSelectsLatestResult() {
        val document = DoctorExportContent.document(
            data(
                observation("p", "old", code = "hb-a", timestamp = "2026-01-01T00:00:00Z", value = 110.0),
                observation("p", "latest", code = "HB A", timestamp = "2026-03-01T00:00:00Z", value = 130.0),
                observation("p", "other-unit", code = "HBA", timestamp = "2026-04-01T00:00:00Z", value = 5.0, unit = "mmol/L"),
            ),
        )
        val sections = DoctorExamSections.from(document)
        assertEquals(2, sections.size)
        val hemoglobin = sections.single { it.unit == "g/L" }
        assertEquals(130.0, hemoglobin.latest.originalValue)
        assertEquals(listOf(110.0, 130.0), hemoglobin.history.map { it.originalValue })
        assertEquals(5.0, sections.single { it.unit == "mmol/L" }.latest.originalValue)
    }

    @Test
    fun historyKeepsEachDatesOwnReferenceRange() {
        val section = DoctorExamSections.from(
            DoctorExportContent.document(
                data(
                    observation("p", "one", timestamp = "2026-01-01T00:00:00Z", range = ReferenceRange(100.0, 120.0)),
                    observation("p", "two", timestamp = "2026-02-01T00:00:00Z", value = 125.0, range = ReferenceRange(110.0, 140.0)),
                    observation("p", "three", timestamp = "2026-03-01T00:00:00Z", value = 135.0, range = ReferenceRange()),
                ),
            ),
        ).single()
        assertEquals(listOf(100.0, 110.0, null), section.history.map { it.referenceRange.lower })
        assertEquals(listOf(120.0, 140.0, null), section.history.map { it.referenceRange.upper })
        assertEquals(3, section.trend?.points?.size)
    }

    @Test
    fun excludesUnconfirmedAndOtherProfilesFromSections() {
        val document = DoctorExportContent.document(
            data(
                observation("p", "confirmed", confirmation = ConfirmationStatus.CONFIRMED),
                observation("p", "pending", confirmation = ConfirmationStatus.UNREVIEWED),
                observation("other", "secret", confirmation = ConfirmationStatus.CONFIRMED),
            ),
        )
        val sections = DoctorExamSections.from(document)
        assertEquals(1, sections.size)
        assertEquals(1, sections.single().history.size)
        assertEquals(110.0, sections.single().latest.originalValue)
    }

    @Test
    fun singleResultHasHistoryButNoChartSeriesRequirement() {
        val section = DoctorExamSections.from(DoctorExportContent.document(data(observation("p", "one")))).single()
        assertEquals(1, section.history.size)
        assertEquals(1, section.trend?.points?.size)
        assertNull(section.history.single().assessment.takeIf { it == RangeAssessment.BELOW })
    }

    @Test
    fun originalUnitAssessmentCoversAboveBelowAndWithin() {
        val rows = DoctorExportContent.document(
            data(
                observation("p", "above", unit = "mg/dL", value = 34.0, range = ReferenceRange(5.0, 30.0, unit = "mg/dL")).copy(normalizedUnit = "mmol/L"),
                observation("p", "within", unit = "mg/dL", value = 20.0, range = ReferenceRange(5.0, 30.0)).copy(normalizedUnit = "mmol/L"),
                observation("p", "below", unit = "mg/dL", value = 4.0, range = ReferenceRange(5.0, 30.0, unit = "mg/dL")).copy(normalizedUnit = "mmol/L"),
            ),
        ).resultRows
        assertEquals(
            mapOf(34.0 to RangeAssessment.ABOVE, 20.0 to RangeAssessment.WITHIN, 4.0 to RangeAssessment.BELOW),
            rows.associate { it.originalValue to it.assessment },
        )
    }

    @Test
    fun originalUnitChangingRangesRemainAttachedToTheirOwnResults() {
        val section = DoctorExamSections.from(
            DoctorExportContent.document(
                data(
                    observation("p", "first", timestamp = "2026-01-01T00:00:00Z", unit = "mg/dL", value = 34.0, range = ReferenceRange(5.0, 30.0, unit = "mg/dL")).copy(normalizedUnit = "mmol/L"),
                    observation("p", "second", timestamp = "2026-02-01T00:00:00Z", unit = "mg/dL", value = 25.0, range = ReferenceRange(20.0, 40.0, unit = "mg/dL")).copy(normalizedUnit = "mmol/L"),
                ),
            ),
        ).single()
        assertEquals(listOf(RangeAssessment.ABOVE, RangeAssessment.WITHIN), section.history.map { it.assessment })
        assertEquals(listOf(30.0, 40.0), section.history.map { it.referenceRange.upper })
    }

    private fun data(vararg observations: LabObservation) = ProfileData(
        Profile("p", "Patient", createdAt = Instant.parse("2026-01-01T00:00:00Z")),
        observations = observations.toList(),
    )

    private fun observation(
        profileId: String,
        id: String,
        code: String = "HGB",
        timestamp: String = "2026-01-01T00:00:00Z",
        unit: String = "g/L",
        value: Double = 110.0,
        range: ReferenceRange = ReferenceRange(100.0, 120.0),
        confirmation: ConfirmationStatus = ConfirmationStatus.CONFIRMED,
    ) = LabObservation(
        profileId = profileId,
        observationId = id,
        reportId = "report-$id",
        testCode = code,
        displayName = "Hemoglobin",
        originalValue = value,
        originalUnit = unit,
        normalizedValue = value,
        normalizedUnit = unit,
        referenceRange = range,
        collectedAt = Instant.parse(timestamp),
        confirmation = confirmation,
    )
}
