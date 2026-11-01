package app.vezuna

import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabObservation
import app.vezuna.domain.Medication
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.ReferenceRange
import app.vezuna.export.CsvDoctorExporter
import app.vezuna.export.DoctorExportContent
import app.vezuna.export.ExportOptions
import app.vezuna.localization.ContentLanguage
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExportOptionsTest {
    private val created = Instant.parse("2026-01-01T00:00:00Z")

    private fun observation(id: String, code: String, at: String) = LabObservation(
        profileId = "p", observationId = id, reportId = "r", testCode = code, displayName = code,
        originalValue = 1.0, originalUnit = "g/L", normalizedValue = 1.0, normalizedUnit = "g/L",
        referenceRange = ReferenceRange(0.5, 2.0), collectedAt = Instant.parse(at), confirmation = ConfirmationStatus.CONFIRMED,
    )

    private val data = ProfileData(
        Profile("p", "Ana", birthDate = LocalDate(1990, 6, 15), createdAt = created),
        observations = listOf(
            observation("a", "HGB", "2026-01-10T10:00:00Z"),
            observation("b", "glu", "2026-02-10T10:00:00Z"),
            observation("c", "HGB", "2026-03-10T10:00:00Z"),
        ),
        medications = listOf(
            Medication("p", "m1", "Metformin", active = true),
            Medication("p", "m2", "Old drug", active = false),
        ),
    )

    private fun ids(options: ExportOptions) = options.apply(data).observations.map { it.observationId }

    @Test
    fun defaultsKeepEverything() {
        assertEquals(listOf("a", "b", "c"), ids(ExportOptions()))
        assertEquals(2, ExportOptions().apply(data).medications.size)
    }

    @Test
    fun dateRangeIsInclusiveOnBothEnds() {
        assertEquals(listOf("b"), ids(ExportOptions(from = LocalDate(2026, 2, 10), to = LocalDate(2026, 2, 10))))
        assertEquals(listOf("b", "c"), ids(ExportOptions(from = LocalDate(2026, 2, 1))))
        assertEquals(listOf("a"), ids(ExportOptions(to = LocalDate(2026, 1, 31))))
    }

    @Test
    fun selectedTestsMatchCanonicalCodes() {
        assertEquals(listOf("b"), ids(ExportOptions(testCodes = setOf("GLU"))))
        assertEquals(listOf("a", "c"), ids(ExportOptions(testCodes = setOf("HGB"))))
    }

    @Test
    fun activeOnlyDropsStoppedMedications() {
        assertEquals(listOf("Metformin"), ExportOptions(activeMedicationsOnly = true).apply(data).medications.map { it.displayName })
    }

    @Test
    fun reversedRangeIsRejected() {
        assertFailsWith<IllegalArgumentException> { ExportOptions(from = LocalDate(2026, 3, 1), to = LocalDate(2026, 2, 1)) }
    }

    @Test
    fun parseDateAcceptsIsoAndRejectsGarbage() {
        assertEquals(LocalDate(2026, 2, 3), ExportOptions.parseDate(" 2026-02-03 "))
        assertNull(ExportOptions.parseDate("03/02/2026"))
        assertNull(ExportOptions.parseDate(""))
    }

    @Test
    fun ageCountsCompletedYearsAndIsOmittedWithoutBirthDate() {
        val birth = LocalDate(1990, 6, 15)
        assertEquals(35, ExportOptions.ageYears(birth, Instant.parse("2026-06-14T00:00:00Z")))
        assertEquals(36, ExportOptions.ageYears(birth, Instant.parse("2026-06-15T00:00:00Z")))
        assertNull(ExportOptions.ageYears(null, created))
        assertNull(ExportOptions.ageYears(LocalDate(2030, 1, 1), created))
    }

    @Test
    fun documentCarriesFilteredRowsAndAge() {
        val document = DoctorExportContent.document(data, options = ExportOptions(testCodes = setOf("GLU"), activeMedicationsOnly = true))
        assertEquals(listOf("glu"), document.resultRows.map { it.testCode })
        assertEquals(listOf("Metformin"), document.medicationRows.map { it.displayName })
        assertTrue(document.ageYears != null && document.ageYears!! >= 35)
        assertNull(DoctorExportContent.document(data.copy(profile = data.profile.copy(birthDate = null))).ageYears)
    }

    @Test
    fun csvMedicationsSectionIsOptionalAndLocalized() {
        val plain = CsvDoctorExporter().export(data)
        assertTrue("medication," !in plain && "Metformin" !in plain)
        val withMeds = CsvDoctorExporter().export(data, ContentLanguage.PT, ExportOptions(includeMedicationsCsv = true, activeMedicationsOnly = true))
        val lines = withMeds.trimEnd().lines()
        assertEquals("medicamento,concentracao,instrucoes,ativo", lines[lines.size - 2])
        assertEquals("\"Metformin\",\"\",\"\",\"true\"", lines.last())
        assertTrue("Old drug" !in withMeds)
    }

    @Test
    fun csvFieldsStartingWithFormulaOrControlCharsAreNeutralised() {
        val exporter = CsvDoctorExporter()
        assertEquals("\"'=SUM(A1)\"", exporter.escape("=SUM(A1)"))
        assertEquals("\"' @x\"", exporter.escape(" @x"))
        assertEquals("\"'\tcmd\"", exporter.escape("\tcmd"))
        assertEquals("\"'\rcmd\"", exporter.escape("\rcmd"))
        assertEquals("\"plain\"", exporter.escape("plain"))
    }
}
