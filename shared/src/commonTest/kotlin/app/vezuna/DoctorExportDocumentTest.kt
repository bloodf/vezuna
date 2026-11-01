package app.vezuna

import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabObservation
import app.vezuna.domain.Medication
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.ReferenceRange
import app.vezuna.export.DoctorChartGeometryFactory
import app.vezuna.export.DoctorExportContent
import app.vezuna.export.DoctorReportFormat
import app.vezuna.export.DoctorResultRow
import app.vezuna.domain.RangeAssessment
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DoctorExportDocumentTest {
    private val start = Instant.parse("2026-02-01T08:30:00Z")

    @Test
    fun documentScopesConfirmedResultsAndMedicationsToCurrentProfile() {
        val data = ProfileData(
            profile = Profile("current", "Ana Ribeiro", createdAt = start),
            observations = listOf(
                observation("current", "included"),
                observation("current", "unconfirmed", confirmation = ConfirmationStatus.UNREVIEWED),
                observation("other", "cross-profile", displayName = "Other secret"),
            ),
            medications = listOf(
                Medication("current", "included-med", "Medicação"),
                Medication("other", "other-med", "Other medication"),
            ),
        )
        val document = DoctorExportContent.document(data)
        assertEquals(1, document.trends.single().points.size)
        val text = document.lines.joinToString("\n")
        assertTrue(text.contains("Ana Ribeiro"))
        assertTrue(text.contains("Medicação"))
        assertTrue(text.contains("original"))
        assertFalse(text.contains("Other secret"))
        assertFalse(text.contains("Other medication"))
        assertFalse(text.contains("unconfirmed"))
    }

    @Test
    fun incompatibleUnitsRemainInSeparateSeries() {
        val data = data(
            observation("p", "one", unit = "g/L"),
            observation("p", "two", unit = "mmol/L", value = 5.2),
        )
        val series = DoctorExportContent.document(data).trends
        assertEquals(2, series.size)
        assertEquals(setOf("g/L", "mmol/L"), series.map { it.unit }.toSet())
        assertTrue(series.all { it.points.size == 1 })
    }

    @Test
    fun everyPointKeepsItsOwnReferenceRangeAndMissingRangeStaysMissing() {
        val data = data(
            observation("p", "one", range = ReferenceRange(100.0, 120.0)),
            observation("p", "two", timestamp = "2026-02-02T08:30:00Z", range = ReferenceRange(110.0, 130.0)),
            observation("p", "three", timestamp = "2026-02-03T08:30:00Z", range = ReferenceRange()),
        )
        val points = DoctorExportContent.document(data).trends.single().points
        assertEquals(100.0, points[0].referenceRange.lower)
        assertEquals(110.0, points[1].referenceRange.lower)
        assertNull(points[2].referenceRange.lower)
        assertNull(points[2].referenceRange.upper)
        val geometry = DoctorChartGeometryFactory.create(DoctorExportContent.document(data).trends.single())
        assertEquals(100.0, geometry.points[0].referenceLower)
        assertEquals(110.0, geometry.points[1].referenceLower)
        assertNull(geometry.points[2].referenceLower)
    }

    @Test
    fun chartDataIsChronologicalWithoutInterpolation() {
        val data = data(
            observation("p", "later", timestamp = "2026-02-03T08:30:00Z", value = 130.0),
            observation("p", "earlier", timestamp = "2026-02-01T08:30:00Z", value = 110.0),
            observation("p", "middle", timestamp = "2026-02-02T08:30:00Z", value = 120.0),
        )
        val series = DoctorExportContent.document(data).trends.single()
        assertEquals(listOf(110.0, 120.0, 130.0), series.points.map { it.value })
        assertEquals(
            listOf("2026-02-01T08:30:00Z", "2026-02-02T08:30:00Z", "2026-02-03T08:30:00Z"),
            series.points.map { it.timestamp.toString() },
        )
        val geometry = DoctorChartGeometryFactory.create(series)
        assertEquals(listOf(0.0, 0.5, 1.0), geometry.points.map { it.xFraction })
    }

    @Test
    fun singlePointSeriesIsExplicitAndCentered() {
        val series = DoctorExportContent.document(data(observation("p", "one"))).trends.single()
        val geometry = DoctorChartGeometryFactory.create(series)
        assertEquals(1, series.points.size)
        assertEquals(0.5, geometry.points.single().xFraction)
        assertTrue(geometry.range > 0.0)
    }

    @Test
    fun structuredRowsContainOnlyCurrentProfileConfirmedData() {
        val document = DoctorExportContent.document(
            ProfileData(
                Profile("p", "Ana Ribeiro", createdAt = start),
                observations = listOf(
                    observation("p", "confirmed"),
                    observation("p", "pending", confirmation = ConfirmationStatus.UNREVIEWED),
                    observation("other", "secret"),
                ),
                medications = listOf(Medication("p", "med", "Medicação"), Medication("other", "secret-med", "Secret")),
            ),
        )
        assertEquals("Ana Ribeiro", document.profileDisplayName)
        assertEquals(1, document.resultRows.size)
        assertEquals("HGB", document.resultRows.single().testCode)
        assertEquals(listOf("Medicação"), document.medicationRows.map { it.displayName })
    }

    @Test
    fun normalizedValueAppearsOnlyWhenItAddsInformation() {
        val same = resultRow(originalUnit = "g/L", normalizedUnit = "g/L", original = 130.0, normalized = 130.0)
        val converted = resultRow(originalUnit = "g/dL", normalizedUnit = "g/L", original = 13.0, normalized = 130.0)
        assertNull(DoctorReportFormat.normalizedValue(same))
        assertEquals("130 g/L normalized", DoctorReportFormat.normalizedValue(converted))
        assertEquals("13 g/dL", DoctorReportFormat.originalValue(converted))
    }

    @Test
    fun clinicalFormattingUsesReadableDatesRangesAndRoundedValues() {
        assertEquals("01 Feb 2026", DoctorReportFormat.date(start))
        assertEquals("1.23", DoctorReportFormat.value(1.2300000000000002))
        assertEquals("0", DoctorReportFormat.value(-0.0001))
        assertEquals("Not provided", DoctorExportContent.rangeText(ReferenceRange()))
        assertEquals("100–120 g/L", DoctorExportContent.rangeText(ReferenceRange(100.0, 120.0, unit = "g/L")))
    }

    private fun resultRow(originalUnit: String, normalizedUnit: String, original: Double, normalized: Double) = DoctorResultRow(
        collectedAt = start,
        testName = "Hemoglobin",
        testCode = "HGB",
        originalValue = original,
        originalUnit = originalUnit,
        normalizedValue = normalized,
        normalizedUnit = normalizedUnit,
        referenceRange = ReferenceRange(),
        assessment = RangeAssessment.UNKNOWN,
    )

    private fun data(vararg observations: LabObservation) = ProfileData(
        Profile("p", "Patient", createdAt = start),
        observations = observations.toList(),
    )

    private fun observation(
        profileId: String,
        id: String,
        timestamp: String = "2026-02-01T08:30:00Z",
        unit: String = "g/L",
        value: Double = 110.0,
        displayName: String = "Hemoglobin",
        range: ReferenceRange = ReferenceRange(100.0, 120.0),
        confirmation: ConfirmationStatus = ConfirmationStatus.CONFIRMED,
    ) = LabObservation(
        profileId = profileId,
        observationId = id,
        reportId = "report-$id",
        testCode = "HGB",
        displayName = displayName,
        originalValue = value,
        originalUnit = unit,
        normalizedValue = value,
        normalizedUnit = unit,
        referenceRange = range,
        collectedAt = Instant.parse(timestamp),
        confirmation = confirmation,
    )
}
