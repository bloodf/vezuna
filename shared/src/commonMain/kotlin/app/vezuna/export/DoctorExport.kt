package app.vezuna.export

import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.DeviceVitals
import app.vezuna.domain.DoseEventStatus
import app.vezuna.domain.DoseEvents
import app.vezuna.domain.VitalMetric
import app.vezuna.domain.LabComparator
import app.vezuna.domain.LabObservation
import app.vezuna.domain.Medication
import app.vezuna.domain.MedicationRecurrence
import app.vezuna.domain.SourceDocument
import app.vezuna.domain.RangeAssessment
import app.vezuna.domain.ProfileData
import app.vezuna.domain.ReferenceRange
import app.vezuna.domain.UnitNormalizer
import app.vezuna.localization.ContentLanguage
import app.vezuna.platform.currentInstant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
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
    val family: ExportFamily = ExportFamily.LAB_HISTORY,
    val doseRows: List<DoctorDoseRow> = emptyList(),
    val deviceRows: List<DoctorDeviceRow> = emptyList(),
    /** Source reports behind the exported results, plus documents left out for unreviewed values (pdf-09). */
    val sourceRows: List<DoctorSourceRow> = emptyList(),
) {
    val hasStructuredContent: Boolean get() = profileDisplayName.isNotEmpty() || resultRows.isNotEmpty() || medicationRows.isNotEmpty() ||
        doseRows.isNotEmpty() || deviceRows.isNotEmpty()
}

/** One recorded dose outcome; [date] and [time] are local to the device zone at export time. */
data class DoctorDoseRow(
    val date: LocalDate,
    val time: String,
    val medication: String,
    val status: DoseEventStatus,
    /** Local "HH:mm" the user recorded the action; null for missed doses or when not recorded. */
    val recordedTime: String? = null,
)

/**
 * One source report of the appendix. [results] confirmed results from it are in the export; a row with no results
 * lists a document left out because its [unreviewed] values were never confirmed. Never carries the values themselves.
 */
data class DoctorSourceRow(
    val collectedAt: Instant,
    val documentName: String?,
    val laboratoryName: String?,
    val orderedAt: Instant?,
    /** Captured photo pages; null when unknown (PDF originals are not paged here, or no original is stored). */
    val pages: Int?,
    /** True only when the original file is in this device's vault; restored backups carry no originals. */
    val originalStored: Boolean,
    val manualEntry: Boolean,
    val results: Int,
    val unreviewed: Int = 0,
)

/** Planned dose times of one calendar day, from the medication's schedules (not dose history). */
data class DoctorScheduleDay(val date: LocalDate, val times: List<String>)

/** One device reading: a day total (steps), a daily average (heart rate) or a single reading (weight, with [time]). */
data class DoctorDeviceRow(val metric: VitalMetric, val date: LocalDate, val value: Double, val time: String? = null)

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
    /** Laboratory and order date of the source report, when known; platform exporters decide how to show them. */
    val laboratoryName: String? = null,
    val orderedAt: Instant? = null,
)

data class DoctorMedicationRow(
    val displayName: String,
    val strength: String?,
    val instructions: String?,
    val active: Boolean = true,
    /** Local "HH:mm" times of every schedule. */
    val times: List<String> = emptyList(),
    val startDate: LocalDate? = null,
    /** Latest schedule end; null when any schedule is open-ended or none is recorded. */
    val endDate: LocalDate? = null,
    /** The seven days from the export date with their planned times; empty for inactive or unscheduled medications. */
    val week: List<DoctorScheduleDay> = emptyList(),
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
    val labsIncluded: Boolean = true,
    val doseHistoryIncluded: Boolean = false,
    val deviceIncluded: Boolean = false,
    /** Last device import, printed with the device section. */
    val deviceLastSync: Instant? = null,
    val sourceAppendixIncluded: Boolean = false,
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
        zone: TimeZone = TimeZone.currentSystemDefault(),
        /** Export time; also the first day of each medication's seven-day schedule strip. */
        generatedAt: Instant = currentInstant(),
    ): DoctorPdfDocument {
        val selected = options.apply(data)
        val available = data.observations.filter { it.profileId == data.profile.profileId && it.confirmation == ConfirmationStatus.CONFIRMED }
        val scope = DoctorExportScope(
            selectedFrom = options.from,
            selectedTo = options.to,
            availableFirst = available.minOfOrNull { it.collectedAt },
            availableLast = available.maxOfOrNull { it.collectedAt },
            excludedUnreviewed = options.unreviewedCount(data),
            medicationsIncluded = options.includeMedicationsPdf,
            labsIncluded = options.includeLabsPdf,
            doseHistoryIncluded = options.includeDoseHistory,
            deviceIncluded = options.includeDeviceData,
            deviceLastSync = data.vitals.lastSync,
            sourceAppendixIncluded = options.includeSourceAppendix,
        )
        val built = build(selected, title, language, generatedAt, ExportOptions.ageYears(data.profile.birthDate, generatedAt))
        return built.copy(
            birthDate = data.profile.birthDate,
            paper = options.paper,
            grayscale = options.grayscale,
            scope = scope,
            medicationRows = if (options.includeMedicationsPdf) medicationRows(selected, generatedAt.toLocalDateTime(zone).date) else emptyList(),
            resultRows = if (options.includeLabsPdf) built.resultRows else emptyList(),
            family = options.family,
            doseRows = if (options.includeDoseHistory) doseRows(data, options, zone) else emptyList(),
            deviceRows = if (options.includeDeviceData) deviceRows(data, options, zone) else emptyList(),
            sourceRows = if (options.includeSourceAppendix) sourceRows(data, selected, options) else emptyList(),
        )
    }

    /** Reports of the exported confirmed results, then documents whose values all await review (listed, never printed). */
    fun sourceRows(data: ProfileData, selected: ProfileData, options: ExportOptions): List<DoctorSourceRow> {
        val profileId = data.profile.profileId
        val documents = data.documents.filter { it.profileId == profileId }.associateBy { it.documentId }
        val reports = data.reports.filter { it.profileId == profileId }.associateBy { it.reportId }
        val unreviewed = options.unreviewedByDocument(data)
        fun pages(document: SourceDocument?) =
            document?.takeIf { it.vaultKey != null && it.mediaType.startsWith("image/") }?.let { 1 + it.pageVaultKeys.size }
        val confirmed = if (!options.includeLabsPdf) emptyList() else selected.observations.filter { it.profileId == profileId && it.confirmation == ConfirmationStatus.CONFIRMED }
        val groups = confirmed.groupBy { reports[it.reportId] }
        val included = groups.map { (report, rows) ->
            val document = report?.sourceDocumentId?.let(documents::get)
            DoctorSourceRow(
                collectedAt = rows.minOf { it.collectedAt }, documentName = document?.displayName?.takeIf { it.isNotBlank() },
                laboratoryName = report?.laboratoryName?.takeIf { it.isNotBlank() }, orderedAt = report?.orderedAt, pages = pages(document),
                originalStored = document?.vaultKey != null, manualEntry = report?.isManual == true, results = rows.size,
                unreviewed = document?.let { unreviewed[it.documentId]?.size } ?: 0,
            )
        }
        val usedDocuments = groups.keys.mapNotNull { it?.sourceDocumentId }.toSet()
        val excluded = unreviewed.mapNotNull { (id, dates) ->
            val document = documents[id]?.takeIf { it.documentId !in usedDocuments } ?: return@mapNotNull null
            DoctorSourceRow(
                collectedAt = dates.min(), documentName = document.displayName, laboratoryName = null, orderedAt = null, pages = pages(document),
                originalStored = document.vaultKey != null, manualEntry = false, results = 0, unreviewed = dates.size,
            )
        }
        return included.sortedWith(compareBy({ it.collectedAt }, { it.documentName.orEmpty() })) + excluded.sortedBy { it.collectedAt }
    }

    private fun localTime(instant: Instant, zone: TimeZone): String =
        instant.toLocalDateTime(zone).let { "${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}" }

    private fun inPeriod(day: LocalDate, options: ExportOptions) = (options.from == null || day >= options.from) && (options.to == null || day <= options.to)

    /** Terminal dose events of the profile inside the period, one per dose (the same collapse as the dose history screen). */
    fun doseRows(data: ProfileData, options: ExportOptions, zone: TimeZone): List<DoctorDoseRow> {
        val names = data.medications.filter { it.profileId == data.profile.profileId }.associate { it.medicationId to it.displayName }
        val medications = if (options.activeMedicationsOnly) data.medications.filter { it.active }.map { it.medicationId }.toSet() else names.keys
        return DoseEvents.collapse(data.doseEvents)
            .filter { it.profileId == data.profile.profileId && DoseEvents.isTerminal(it.status) && it.medicationId in medications }
            .filter { inPeriod(it.scheduledFor.toLocalDateTime(zone).date, options) }
            .sortedWith(compareBy({ it.scheduledFor }, { names[it.medicationId].orEmpty() }))
            .map { event ->
                DoctorDoseRow(
                    date = event.scheduledFor.toLocalDateTime(zone).date,
                    time = localTime(event.scheduledFor, zone),
                    medication = names[event.medicationId].orEmpty(),
                    status = event.status,
                    recordedTime = event.recordedAt?.takeIf { event.status != DoseEventStatus.MISSED }?.let { localTime(it, zone) },
                )
            }
    }

    /** Daily steps, daily average heart rate and every weight reading of the profile inside the period. */
    fun deviceRows(data: ProfileData, options: ExportOptions, zone: TimeZone): List<DoctorDeviceRow> {
        val vitals = data.vitals
        return buildList {
            vitals.steps.filter { inPeriod(it.date, options) }.sortedBy { it.date }.forEach { add(DoctorDeviceRow(VitalMetric.STEPS, it.date, it.count.toDouble())) }
            DeviceVitals.series(vitals, VitalMetric.HEART_RATE_BPM, zone).forEach { (epoch, avg) ->
                val day = Instant.fromEpochSeconds(epoch).toLocalDateTime(zone).date
                if (inPeriod(day, options)) add(DoctorDeviceRow(VitalMetric.HEART_RATE_BPM, day, avg))
            }
            vitals.weights.sortedBy { it.time }.forEach { reading ->
                val day = reading.time.toLocalDateTime(zone).date
                if (inPeriod(day, options)) add(DoctorDeviceRow(VitalMetric.WEIGHT_KG, day, reading.kg, localTime(reading.time, zone)))
            }
        }
    }

    /** "vezuna-ana-silva-lab-history-2026-11-01.pdf": ASCII only, no path separators. */
    fun fileName(document: DoctorPdfDocument): String =
        fileName(document.profileDisplayName, document.family.slug, document.generatedAt, "pdf")

    /** "vezuna-ana-silva-lab-results-2026-11-01.csv" for the confirmed-results spreadsheet. */
    fun csvFileName(profileName: String, generatedAt: Instant): String = fileName(profileName, "lab-results", generatedAt, "csv")

    fun fileName(profileName: String, kind: String, generatedAt: Instant, extension: String): String {
        val slug = profileName.lowercase()
            .map { FOLDED[it] ?: it }.joinToString("")
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-')
        val day = generatedAt.toLocalDateTime(TimeZone.UTC).date
        return listOf("vezuna", slug, kind, day.toString()).filter { it.isNotEmpty() }.joinToString("-") + ".$extension"
    }

    private val FOLDED = mapOf(
        'á' to 'a', 'à' to 'a', 'â' to 'a', 'ã' to 'a', 'ä' to 'a', 'é' to 'e', 'ê' to 'e', 'è' to 'e', 'ë' to 'e',
        'í' to 'i', 'ì' to 'i', 'î' to 'i', 'ï' to 'i', 'ó' to 'o', 'ò' to 'o', 'ô' to 'o', 'õ' to 'o', 'ö' to 'o',
        'ú' to 'u', 'ù' to 'u', 'û' to 'u', 'ü' to 'u', 'ç' to 'c', 'ñ' to 'n',
    )

    private fun medicationRows(data: ProfileData, today: LocalDate? = null): List<DoctorMedicationRow> = data.medications
        .filter { it.profileId == data.profile.profileId }
        .sortedBy { it.displayName }
        .map { medication ->
            val week = if (today == null || !medication.active || medication.schedules.isEmpty()) emptyList() else (0 until 7).map { offset ->
                val day = today.plus(offset, DateTimeUnit.DAY)
                DoctorScheduleDay(day, medication.schedules.filter { MedicationRecurrence.occursOn(it, day) }.flatMap { it.timesLocal }.distinct().sorted())
            }
            DoctorMedicationRow(
                displayName = medication.displayName,
                strength = medication.strength,
                instructions = medication.instructions,
                active = medication.active,
                times = medication.schedules.flatMap { it.timesLocal }.distinct().sorted(),
                startDate = medication.schedules.minOfOrNull { it.startDate },
                endDate = medication.schedules.takeIf { list -> list.isNotEmpty() && list.all { it.endDate != null } }?.maxOf { it.endDate!! },
                week = week,
            )
        }

    private fun build(data: ProfileData, title: String, language: ContentLanguage, generatedAt: Instant, ageYears: Int?): DoctorPdfDocument {
        val confirmed = data.observations
            .filter { it.profileId == data.profile.profileId && it.confirmation == ConfirmationStatus.CONFIRMED }
            .sortedWith(compareBy({ it.collectedAt }, { it.observationId }))
        val reports = data.reports.associateBy { it.reportId }
        val documentNames = data.documents.associate { it.documentId to it.displayName }
        return DoctorPdfDocument(
            title = title,
            lines = lines(data, language),
            trends = trends(data),
            profileDisplayName = data.profile.displayName,
            ageYears = ageYears,
            generatedAt = generatedAt,
            resultRows = confirmed.map { observation ->
                val report = reports[observation.reportId]
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
                    sourceName = report?.sourceDocumentId?.let(documentNames::get)?.takeIf { it.isNotBlank() },
                    manualEntry = report?.isManual == true,
                    laboratoryName = report?.laboratoryName?.takeIf { it.isNotBlank() },
                    orderedAt = report?.orderedAt,
                )
            },
            medicationRows = medicationRows(data),
            language = language,
        )
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

    /** [decimals] prints numeric bounds at that precision (12.0–16.0); null prints the shortest exact value. */
    fun rangeText(range: app.vezuna.domain.ReferenceRange, language: ContentLanguage = ContentLanguage.EN, decimals: Int? = null): String {
        fun v(value: Double) = DoctorReportFormat.recorded(value, decimals, language)
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
