package app.vezuna

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabObservation
import app.vezuna.domain.Medication
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.ReferenceRange
import app.vezuna.export.CsvDoctorExporter
import app.vezuna.export.DoctorExportContent
import app.vezuna.export.ExportOptions
import app.vezuna.export.PaperSize
import app.vezuna.export.NativePdfExporter
import kotlinx.datetime.Instant
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DoctorExportInstrumentedTest {
    @Test
    fun generatesUnicodeMultiPageDoctorPdfAndCsv() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = Profile("synthetic-profile", "Paciente de Teste — Medicação", createdAt = Instant.parse("2026-01-01T00:00:00Z"))
        val observations = listOf(
            observation("hgb-1", "Hemoglobina", 118.0, "2025-08-20T00:00:00Z", ReferenceRange(120.0, 140.0, unit = "g/L")),
            observation("hgb-2", "Hemoglobina", 126.0, "2026-02-10T00:00:00Z", ReferenceRange(115.0, 145.0, unit = "g/L")),
            observation("hgb-3", "Hemoglobina", 133.0, "2026-09-18T00:00:00Z", ReferenceRange(120.0, 140.0, unit = "g/L")),
            observation("tsh-1", "TSH", 2.1, "2026-09-18T00:00:00Z", ReferenceRange(0.4, 4.0, unit = "µUI/mL"), unit = "µUI/mL"),
            observation("pending", "Não confirmado", 999.0, "2026-09-18T00:00:00Z", ReferenceRange(), status = ConfirmationStatus.UNREVIEWED),
            observation("other-profile", "Outro perfil", 888.0, "2026-09-18T00:00:00Z", ReferenceRange(), profileId = "other-profile"),
        )
        val data = ProfileData(
            profile = profile,
            observations = observations,
            medications = listOf(Medication(profile.profileId, "med-1", "Medicamento fictício", "10 mg", "Uso conforme prescrição")),
        )

        val pdf = NativePdfExporter().export(DoctorExportContent.document(data))
        val csv = CsvDoctorExporter().export(data)
        val directory = context.getExternalFilesDir(null)!!
        File(directory, "doctor-report-smoke.pdf").writeBytes(pdf.bytes)
        File(directory, "doctor-report-smoke.csv").writeText(csv)

        assertTrue(pdf.bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x25, 0x50, 0x44, 0x46)))
        assertTrue(pdf.bytes.size > 2_000)
        assertTrue(csv.contains("Hemoglobina"))
        assertTrue(!csv.contains("Não confirmado"))
        assertTrue(!csv.contains("Outro perfil"))
        println("DOCTOR_EXPORT_SMOKE_PASSED pdfBytes=${pdf.bytes.size} csvChars=${csv.length}")
    }

    @Test
    fun generatesLongClinicalReportForPaginationInspection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = Profile("synthetic-profile", "Paciente de Teste — Medicação", createdAt = Instant.parse("2026-01-01T00:00:00Z"))
        val analytes = listOf(
            Triple("Hemoglobina", "HGB", "g/L"),
            Triple("Leucócitos", "WBC", "10^9/L"),
            Triple("Ferritina sérica", "FERR", "ng/mL"),
            Triple("Proteína C Reativa ultrassensível", "PCR", "mg/L"),
            Triple("25-Hidroxivitamina D", "VITD", "ng/mL"),
            Triple("Tireotrofina", "TSH", "µUI/mL"),
            Triple("Triglicérides", "TG", "mg/dL"),
            Triple("Creatinina", "CREA", "mg/dL"),
        )
        val dates = listOf("2024-01-15T00:00:00Z", "2024-08-15T00:00:00Z", "2025-08-20T00:00:00Z", "2026-09-18T00:00:00Z")
        val observations = buildList {
            analytes.forEachIndexed { analyteIndex, analyte ->
                dates.forEachIndexed { dateIndex, date ->
                    val value = 10.0 + analyteIndex * 3.0 + dateIndex
                    add(observation("long-$analyteIndex-$dateIndex", analyte.first, value, date, ReferenceRange(5.0, 30.0, unit = analyte.third), unit = analyte.third, code = analyte.second))
                }
            }
        }
        val data = ProfileData(profile, observations = observations)
        val pdf = NativePdfExporter().export(DoctorExportContent.document(data))
        File(context.getExternalFilesDir(null), "doctor-report-long.pdf").writeBytes(pdf.bytes)
        assertTrue(pdf.bytes.size > 20_000)
        println("DOCTOR_LONG_EXPORT_PASSED results=${observations.size} pdfBytes=${pdf.bytes.size}")
    }

    /** Sample for visual review: A4 and Letter grayscale through the real Android canvas, pulled with adb. */
    @Test
    fun writesLetterGrayscaleAndA4Samples() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = Profile("synthetic-profile", "Ana Silva", createdAt = Instant.parse("2026-01-01T00:00:00Z"))
        val range = ReferenceRange(12.0, 16.0, text = "12.0–16.0 g/dL", unit = "g/dL")
        val data = ProfileData(
            profile,
            observations = listOf("2026-04-12" to 13.0, "2026-07-15" to 12.8, "2026-10-28" to 13.2).mapIndexed { index, (date, value) ->
                observation("hgb-$index", "Hemoglobin", value, "${date}T11:00:00Z", range, unit = "g/dL").copy(valueDecimals = 1, normalizedValue = value * 10, normalizedUnit = "g/L")
            },
            medications = listOf(Medication(profile.profileId, "vitamin-d", "Vitamin D", "1,000 IU", "1 capsule")),
        )
        listOf(ExportOptions() to "a4", ExportOptions(paper = PaperSize.LETTER, grayscale = true) to "letter-grayscale").forEach { (options, name) ->
            val pdf = NativePdfExporter().export(DoctorExportContent.document(data, options = options))
            File(context.getExternalFilesDir(null), "vezuna-android-lab-history-$name.pdf").writeBytes(pdf.bytes)
            assertTrue(pdf.fileName.startsWith("vezuna-ana-silva-lab-history-"))
        }
    }

    private fun observation(
        id: String,
        name: String,
        value: Double,
        date: String,
        range: ReferenceRange,
        unit: String = "g/L",
        status: ConfirmationStatus = ConfirmationStatus.CONFIRMED,
        profileId: String = "synthetic-profile",
        code: String = if (name == "TSH") "TSH" else "HGB",
    ) = LabObservation(
        profileId = profileId,
        observationId = id,
        reportId = "synthetic-report",
        testCode = code,
        displayName = name,
        originalValue = value,
        originalUnit = unit,
        normalizedValue = value,
        normalizedUnit = unit,
        referenceRange = range,
        collectedAt = Instant.parse(date),
        confirmation = status,
    )
}
