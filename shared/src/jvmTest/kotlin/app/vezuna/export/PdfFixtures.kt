package app.vezuna.export

import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.LabObservation
import app.vezuna.domain.LabReport
import app.vezuna.domain.Medication
import app.vezuna.domain.OcrCandidate
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.Recurrence
import app.vezuna.domain.ReferenceRange
import app.vezuna.domain.SourceDocument
import app.vezuna.localization.ContentLanguage
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.DatePeriod
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper

/** Synthetic household from the redesign pack's DATA_AND_COPY (Ana Silva, 8 confirmed results, 3 pending values). */
object PdfFixtures {
    const val PROFILE = "ana"
    val generatedAt: Instant = Instant.parse("2026-11-01T12:41:00Z")

    private fun at(date: String) = Instant.parse("${date}T11:00:00Z")

    private val reports = listOf(
        Triple("report-apr", "April blood work", "2026-04-12"),
        Triple("report-jul", "July blood work", "2026-07-15"),
        Triple("report-lipid-oct", "Lipid panel", "2026-10-14"),
        Triple("report-cbc-oct", "Blood count", "2026-10-28"),
        Triple("report-thyroid-oct", "Thyroid function", "2026-10-28"),
        Triple("report-hbsag-oct", "Hepatitis B screening", "2026-10-28"),
    )

    private fun numeric(id: String, report: String, date: String, code: String, name: String, value: Double, decimals: Int, unit: String, normalized: Double, normalizedUnit: String, range: ReferenceRange) =
        LabObservation(
            profileId = PROFILE, observationId = id, reportId = report, testCode = code, displayName = name,
            originalValue = value, originalUnit = unit, normalizedValue = normalized, normalizedUnit = normalizedUnit,
            referenceRange = range, collectedAt = at(date), confirmation = ConfirmationStatus.CONFIRMED, valueDecimals = decimals,
        )

    private val hgbRange = ReferenceRange(12.0, 16.0, text = "12.0–16.0 g/dL", unit = "g/dL")
    private val ldlRange = ReferenceRange(upper = 130.0, text = "Under 130 mg/dL", unit = "mg/dL")

    fun canonical(): ProfileData = ProfileData(
        profile = Profile(PROFILE, "Ana Silva", LocalDate(1988, 5, 18), createdAt = at("2026-04-01")),
        documents = reports.map { (id, title, date) -> SourceDocument(PROFILE, "doc-$id", title, "application/pdf", "0".repeat(64), Instant.parse("${date}T21:00:00Z")) },
        reports = reports.map { (id, _, date) -> LabReport(PROFILE, id, "doc-$id", "Example Laboratory", null, Instant.parse("${date}T21:00:00Z")) },
        observations = listOf(
            numeric("hgb-2026-04", "report-apr", "2026-04-12", "HGB", "Hemoglobin", 13.0, 1, "g/dL", 130.0, "g/L", hgbRange),
            numeric("hgb-2026-07", "report-jul", "2026-07-15", "HGB", "Hemoglobin", 12.8, 1, "g/dL", 128.0, "g/L", hgbRange),
            numeric("hgb-2026-10", "report-cbc-oct", "2026-10-28", "HGB", "Hemoglobin", 13.2, 1, "g/dL", 132.0, "g/L", hgbRange),
            numeric("ldl-2026-04", "report-apr", "2026-04-12", "LDL", "LDL cholesterol", 146.0, 0, "mg/dL", 1460.0, "mg/L", ldlRange),
            numeric("ldl-2026-07", "report-jul", "2026-07-15", "LDL", "LDL cholesterol", 140.0, 0, "mg/dL", 1400.0, "mg/L", ldlRange),
            numeric("ldl-2026-10", "report-lipid-oct", "2026-10-14", "LDL", "LDL cholesterol", 132.0, 0, "mg/dL", 1320.0, "mg/L", ldlRange),
            numeric("tsh-2026-10", "report-thyroid-oct", "2026-10-28", "TSH", "TSH", 2.1, 1, "mIU/L", 2.1, "mIU/L", ReferenceRange(0.4, 4.0, text = "0.4–4.0 mIU/L", unit = "mIU/L")),
            LabObservation(
                profileId = PROFILE, observationId = "hbsag-2026-10", reportId = "report-hbsag-oct", testCode = "HBsAg",
                displayName = "Hepatitis B surface antigen (HBsAg)", originalValue = 0.0, originalUnit = "", normalizedValue = 0.0,
                normalizedUnit = "", referenceRange = ReferenceRange(text = "Not detected"), collectedAt = at("2026-10-28"),
                confirmation = ConfirmationStatus.CONFIRMED, qualitativeValue = "Not detected",
            ),
        ),
        ocrCandidates = listOf("Fasting glucose" to "96", "Ferritin" to "42", "Vitamin D" to "31").mapIndexed { index, (name, value) ->
            OcrCandidate(PROFILE, "candidate-$index", "doc-october", name.uppercase(), name, value, "mg/dL", collectedAt = at("2026-10-30"))
        },
        medications = listOf(
            Medication(
                PROFILE, "vitamin-d", "Vitamin D", "1,000 IU", "1 capsule",
                schedules = listOf(DoseSchedule(PROFILE, "vitamin-d-daily", "vitamin-d", Recurrence.DAILY, startDate = LocalDate(2026, 4, 1), timesLocal = listOf("08:00"))),
            ),
        ),
    )

    /** Stress fixture: [count] hemoglobin readings ten days apart, long names and a long free-text reference. */
    fun longHistory(count: Int = 120): ProfileData {
        val base = canonical()
        val start = LocalDate(2023, 1, 5)
        val readings = (0 until count).map { index ->
            val day = start.plus(DatePeriod(days = index * 10))
            LabObservation(
                profileId = PROFILE, observationId = "long-$index", reportId = "report-apr", testCode = "HGB", displayName = "Hemoglobin",
                originalValue = 12.0 + (index % 17) / 10.0, originalUnit = "g/dL", normalizedValue = 120.0 + index % 17, normalizedUnit = "g/L",
                referenceRange = if (index % 40 == 39) {
                    ReferenceRange(12.0, 16.0, text = "12.0–16.0 g/dL for adult women; laboratory reference printed on the source report with a long free-text note", unit = "g/dL")
                } else hgbRange,
                collectedAt = day.atStartOfDayIn(TimeZone.UTC), confirmation = ConfirmationStatus.CONFIRMED, valueDecimals = 1,
            )
        }
        return base.copy(
            observations = readings + base.observations.filterNot { it.testCode == "HGB" },
            medications = base.medications + Medication(PROFILE, "long", "Very long medication name that wraps across the full content width of the page", "250 mg", "Take one tablet with water after breakfast and another after dinner; do not change without asking the prescriber who recorded it"),
        )
    }

    fun document(data: ProfileData = canonical(), language: ContentLanguage = ContentLanguage.EN, options: ExportOptions = ExportOptions()) =
        DoctorExportContent.document(data, ExportText.of(language).title, language, options).copy(generatedAt = generatedAt)

    fun pdfBytes(document: DoctorPdfDocument): ByteArray = NativePdfExporter().export(document).bytes

    fun <T> open(bytes: ByteArray, block: (PDDocument) -> T): T = Loader.loadPDF(bytes).use(block)

    fun pageTexts(bytes: ByteArray): List<String> = open(bytes) { pdf ->
        (1..pdf.numberOfPages).map { page ->
            PDFTextStripper().apply { startPage = page; endPage = page }.getText(pdf)
        }
    }
}
