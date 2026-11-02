package app.vezuna.export

import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabComparator
import app.vezuna.domain.LabObservation
import app.vezuna.domain.Medication
import app.vezuna.domain.RangeAssessment
import app.vezuna.domain.ProfileData
import app.vezuna.domain.ReferenceRange
import app.vezuna.domain.UnitNormalizer
import app.vezuna.localization.ContentLanguage
import app.vezuna.platform.currentInstant
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

data class DoctorPdfDocument(
    val title: String,
    val lines: List<String>,
    val trends: List<DoctorTrendSeries> = emptyList(),
    val generatedAt: Instant = currentInstant(),
    val profileDisplayName: String = "",
    /** Completed years at [generatedAt]; null when the profile has no birth date. */
    val ageYears: Int? = null,
    val resultRows: List<DoctorResultRow> = emptyList(),
    val medicationRows: List<DoctorMedicationRow> = emptyList(),
    /** Language of every heading, status, disclaimer and number in the rendered PDF. */
    val language: ContentLanguage = ContentLanguage.EN,
    val birthDate: LocalDate? = null,
    val paper: PaperSize = PaperSize.A4,
    /** True black and gray only; every meaning already has text, so nothing is lost. */
    val grayscale: Boolean = false,
    val scope: DoctorExportScope = DoctorExportScope(),
) {
    val hasStructuredContent: Boolean get() = profileDisplayName.isNotEmpty() || resultRows.isNotEmpty() || medicationRows.isNotEmpty()
}

data class DoctorResultRow(
    val collectedAt: Instant,
    val testName: String,
    val testCode: String,
    /** Null for qualitative rows, which carry [qualitativeValue] instead. */
    val originalValue: Double?,
    val originalUnit: String,
    val normalizedValue: Double?,
    val normalizedUnit: String,
    val referenceRange: ReferenceRange,
    val assessment: RangeAssessment,
    val qualitativeValue: String? = null,
    /** Fraction digits the source printed (5.70 -> 2); null when unknown. */
    val valueDecimals: Int? = null,
    /** Name of the source report file, or null when not recorded. */
    val sourceName: String? = null,
    val manualEntry: Boolean = false,
)

data class DoctorMedicationRow(
    val displayName: String,
    val strength: String?,
    val instructions: String?,
    val active: Boolean = true,
    /** Local "HH:mm" times of every schedule. */
    val times: List<String> = emptyList(),
    val startDate: LocalDate? = null,
)

/** What the export selected and left out, so the PDF can state its own scope. */
data class DoctorExportScope(
    val selectedFrom: LocalDate? = null,
    val selectedTo: LocalDate? = null,
    /** Collection dates of every confirmed result of the profile, ignoring the selection. */
    val availableFirst: Instant? = null,
    val availableLast: Instant? = null,
    /** Unreviewed imported values (observations and OCR candidates) inside the selection; never printed as data. */
    val excludedUnreviewed: Int = 0,
    val medicationsIncluded: Boolean = true,
)

data class DoctorTrendSeries(
    val testName: String,
    val testCode: String,
    val unit: String,
    val points: List<DoctorTrendPoint>,
)

data class DoctorTrendPoint(
    val timestamp: Instant,
    val value: Double,
    val referenceRange: ReferenceRange,
)

data class ExportedPdf(val fileName: String, val bytes: ByteArray)

expect class NativePdfExporter() {
    fun export(document: DoctorPdfDocument): ExportedPdf
}

object DoctorExportContent {
    fun document(
        data: ProfileData,
        title: String = ExportText.of(ContentLanguage.EN).title,
        language: ContentLanguage = ContentLanguage.EN,
        options: ExportOptions = ExportOptions(),
    ): DoctorPdfDocument {
        val generatedAt = currentInstant()
        val selected = options.apply(data)
        val available = data.observations.filter { it.profileId == data.profile.profileId && it.confirmation == ConfirmationStatus.CONFIRMED }
        val scope = DoctorExportScope(
            selectedFrom = options.from,
            selectedTo = options.to,
            availableFirst = available.minOfOrNull { it.collectedAt },
            availableLast = available.maxOfOrNull { it.collectedAt },
            excludedUnreviewed = options.unreviewedCount(data),
            medicationsIncluded = options.includeMedicationsPdf,
        )
        return build(selected, title, language, generatedAt, ExportOptions.ageYears(data.profile.birthDate, generatedAt)).copy(
            birthDate = data.profile.birthDate,
            paper = options.paper,
            grayscale = options.grayscale,
            scope = scope,
            medicationRows = if (options.includeMedicationsPdf) medicationRows(selected) else emptyList(),
        )
    }

    /** "vezuna-ana-silva-lab-history-2026-11-01.pdf": ASCII only, no path separators. */
    fun fileName(document: DoctorPdfDocument): String {
        val slug = document.profileDisplayName.lowercase()
            .map { FOLDED[it] ?: it }.joinToString("")
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-')
        val day = document.generatedAt.toLocalDateTime(TimeZone.UTC).date
        return listOf("vezuna", slug, "lab-history", day.toString()).filter { it.isNotEmpty() }.joinToString("-") + ".pdf"
    }

    private val FOLDED = mapOf(
        'á' to 'a', 'à' to 'a', 'â' to 'a', 'ã' to 'a', 'ä' to 'a', 'é' to 'e', 'ê' to 'e', 'è' to 'e', 'ë' to 'e',
        'í' to 'i', 'ì' to 'i', 'î' to 'i', 'ï' to 'i', 'ó' to 'o', 'ò' to 'o', 'ô' to 'o', 'õ' to 'o', 'ö' to 'o',
        'ú' to 'u', 'ù' to 'u', 'û' to 'u', 'ü' to 'u', 'ç' to 'c', 'ñ' to 'n',
    )

    private fun medicationRows(data: ProfileData): List<DoctorMedicationRow> = data.medications
        .filter { it.profileId == data.profile.profileId }
        .sortedBy { it.displayName }
        .map { medication ->
            DoctorMedicationRow(
                displayName = medication.displayName,
                strength = medication.strength,
                instructions = medication.instructions,
                active = medication.active,
                times = medication.schedules.flatMap { it.timesLocal }.distinct().sorted(),
                startDate = medication.schedules.minOfOrNull { it.startDate },
            )
        }

    private fun build(data: ProfileData, title: String, language: ContentLanguage, generatedAt: Instant, ageYears: Int?): DoctorPdfDocument {
        val confirmed = data.observations
            .filter { it.profileId == data.profile.profileId && it.confirmation == ConfirmationStatus.CONFIRMED }
            .sortedWith(compareBy({ it.collectedAt }, { it.observationId }))
        return DoctorPdfDocument(
            title = title,
            lines = lines(data, language),
            trends = trends(data),
            profileDisplayName = data.profile.displayName,
            ageYears = ageYears,
            generatedAt = generatedAt,
            resultRows = confirmed.map { observation ->
                DoctorResultRow(
                    collectedAt = observation.collectedAt,
                    testName = observation.displayName,
                    testCode = observation.testCode,
                    originalValue = observation.originalValue.takeUnless { observation.isQualitative },
                    originalUnit = observation.originalUnit,
                    normalizedValue = observation.normalizedValue.takeUnless { observation.isQualitative },
                    normalizedUnit = observation.normalizedUnit,
                    referenceRange = observation.referenceRange,
                    assessment = assessmentForExport(observation),
                    qualitativeValue = observation.qualitativeValue,
                    valueDecimals = observation.valueDecimals,
                    sourceName = sourceName(data, observation),
                    manualEntry = data.reports.firstOrNull { it.reportId == observation.reportId }?.isManual == true,
                )
            },
            medicationRows = medicationRows(data),
            language = language,
        )
    }

    private fun sourceName(data: ProfileData, observation: LabObservation): String? {
        val documentId = data.reports.firstOrNull { it.reportId == observation.reportId }?.sourceDocumentId ?: return null
        return data.documents.firstOrNull { it.documentId == documentId }?.displayName?.takeIf { it.isNotBlank() }
    }

    private fun assessmentForExport(observation: LabObservation): RangeAssessment {
        if (observation.isQualitative) return RangeAssessment.UNKNOWN
        val range = observation.referenceRange
        val originalUnitMatches = range.unit == null || range.unit.equals(observation.originalUnit, ignoreCase = true)
        return if (originalUnitMatches) {
            LabComparator.assess(observation.originalValue, range)
        } else {
            LabComparator.assess(observation)
        }
    }

    fun trends(data: ProfileData): List<DoctorTrendSeries> = data.observations
        .filter { it.profileId == data.profile.profileId && it.confirmation == ConfirmationStatus.CONFIRMED && !it.isQualitative }
        .groupBy { observation ->
            UnitNormalizer.canonicalTestCode(observation.testCode) to observation.normalizedUnit.lowercase()
        }
        .map { (_, observations) -> series(observations) }
        .sortedWith(compareBy({ it.testCode }, { it.unit }))

    private fun series(observations: List<LabObservation>): DoctorTrendSeries {
        val ordered = observations.sortedWith(compareBy({ it.collectedAt }, { it.observationId }))
        val first = ordered.first()
        return DoctorTrendSeries(
            testName = first.displayName,
            testCode = first.testCode,
            unit = first.normalizedUnit,
            points = ordered.map { observation ->
                DoctorTrendPoint(
                    timestamp = observation.collectedAt,
                    value = observation.normalizedValue,
                    referenceRange = observation.referenceRange.copy(
                        unit = observation.referenceRange.unit ?: observation.originalUnit,
                    ),
                )
            },
        )
    }

    fun lines(data: ProfileData, language: ContentLanguage = ContentLanguage.EN): List<String> = buildList {
        val text = ExportText.of(language)
        fun num(value: Double) = DoctorReportFormat.localizeDecimal(value.toString(), language)
        add("${text.profile}: ${data.profile.displayName}")
        add(text.confirmedResults)
        data.observations.filter { it.profileId == data.profile.profileId && it.confirmation == ConfirmationStatus.CONFIRMED }
            .sortedBy { it.collectedAt }
            .forEach {
                if (it.isQualitative) {
                    add("${it.collectedAt} | ${it.displayName} (${it.testCode}) | ${text.result.lowercase()} ${it.qualitativeValue} | ${text.reference.lowercase()} ${rangeText(it.referenceRange, language)}")
                    return@forEach
                }
                add("${it.collectedAt} | ${it.displayName} (${it.testCode}) | ${text.original} ${num(it.originalValue)} ${it.originalUnit} | ${text.normalized} ${num(it.normalizedValue)} ${it.normalizedUnit} | ${text.reference.lowercase()} ${rangeText(it.referenceRange, language)}")
            }
        add(text.medicationsRecorded)
        data.medications.filter { it.profileId == data.profile.profileId }.forEach { medication: Medication ->
            add("${medication.displayName} | ${medication.strength.orEmpty()} | ${medication.instructions.orEmpty()}")
        }
    }

    fun rangeText(range: app.vezuna.domain.ReferenceRange, language: ContentLanguage = ContentLanguage.EN): String {
        fun v(value: Double) = DoctorReportFormat.value(value, language)
        return when {
            range.text != null -> range.text
            range.lower == null && range.upper == null -> ExportText.of(language).notProvided
            range.lower == null -> "≤ ${v(range.upper!!)} ${range.unit.orEmpty()}".trim()
            range.upper == null -> "≥ ${v(range.lower)} ${range.unit.orEmpty()}".trim()
            else -> "${v(range.lower)}–${v(range.upper)} ${range.unit.orEmpty()}".trim()
        }
    }
}

/**
 * Headers follow the export language. Numeric columns, units and timestamps stay machine-readable
 * (canonical decimal point, ISO-8601) in every language, so a spreadsheet or script can read any export.
 */
class CsvDoctorExporter {
    fun export(data: ProfileData, language: ContentLanguage = ContentLanguage.EN, options: ExportOptions = ExportOptions()): String =
        exportFiltered(options.apply(data), language, options.includeMedicationsCsv)

    private fun exportFiltered(data: ProfileData, language: ContentLanguage, includeMedications: Boolean): String = buildString {
        appendLine(ExportText.of(language).csvHeader)
        data.observations
            .filter { it.profileId == data.profile.profileId && it.confirmation == ConfirmationStatus.CONFIRMED }
            .sortedBy { it.collectedAt }
            .forEach { observation ->
                val values = listOf(
                    observation.profileId,
                    observation.collectedAt.toString(),
                    observation.testCode,
                    observation.displayName,
                    if (observation.isQualitative) "" else observation.originalValue.toString(),
                    observation.originalUnit,
                    if (observation.isQualitative) "" else observation.normalizedValue.toString(),
                    observation.normalizedUnit,
                    observation.referenceRange.lower?.toString().orEmpty(),
                    observation.referenceRange.upper?.toString().orEmpty(),
                    observation.referenceRange.unit.orEmpty(),
                    observation.referenceRange.text.orEmpty(),
                    observation.qualitativeValue.orEmpty(),
                )
                appendLine(values.joinToString(",") { escape(it) })
            }
        if (includeMedications) appendMedications(data, language)
    }

    /** A blank line then a second header: spreadsheets show it as a separate medications block. */
    private fun StringBuilder.appendMedications(data: ProfileData, language: ContentLanguage) {
        appendLine()
        appendLine(ExportText.of(language).csvMedicationsHeader)
        data.medications.filter { it.profileId == data.profile.profileId }.sortedBy { it.displayName }.forEach { medication ->
            val values = listOf(medication.displayName, medication.strength.orEmpty(), medication.instructions.orEmpty(), medication.active.toString())
            appendLine(values.joinToString(",") { escape(it) })
        }
    }

    internal fun escape(raw: String): String {
        // Spreadsheets run a cell as a formula after =, +, -, @ (even behind blanks) and after a leading tab or carriage return.
        val protected = if (raw.firstOrNull() in setOf('\t', '\r') || raw.firstOrNull { !it.isWhitespace() } in setOf('=', '+', '-', '@')) "'$raw" else raw
        return "\"${protected.replace("\"", "\"\"")}\""
    }
}
