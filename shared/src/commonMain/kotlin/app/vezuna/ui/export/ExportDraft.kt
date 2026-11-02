package app.vezuna.ui.export

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.ProfileData
import app.vezuna.domain.UnitNormalizer
import app.vezuna.export.DoctorExportContent
import app.vezuna.export.ExportFamily
import app.vezuna.export.ExportOptions
import app.vezuna.export.PaperSize
import app.vezuna.localization.ContentLanguage
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus

enum class ExportPeriod { ALL, LAST_3_MONTHS, LAST_12_MONTHS, CUSTOM }

/** Route kind for the CSV spreadsheet; every other kind is an [ExportFamily] name. */
const val CSV_KIND = "CSV"

fun familyOf(kind: String): ExportFamily? = ExportFamily.entries.firstOrNull { it.name == kind }

/**
 * The export being prepared, shared by options, test selection and preview. In memory only (never in a saved
 * Bundle) and bound to one profile: [ExportSession.draft] starts a fresh one for any other profile or kind.
 */
class ExportDraft(val profileId: String, val kind: String, paper: PaperSize) {
    val family: ExportFamily? = familyOf(kind)
    private val defaults = family?.defaults(paper) ?: ExportOptions(paper = paper)

    var language by mutableStateOf<ContentLanguage?>(null)
    var period by mutableStateOf(ExportPeriod.ALL)
    var fromText by mutableStateOf("")
    var toText by mutableStateOf("")
    var testCodes by mutableStateOf(emptySet<String>())
    var includeLabs by mutableStateOf(defaults.includeLabsPdf)
    var includeMedications by mutableStateOf(defaults.includeMedicationsPdf)
    var includeDoses by mutableStateOf(defaults.includeDoseHistory)
    var includeDevice by mutableStateOf(defaults.includeDeviceData)
    var includeSources by mutableStateOf(defaults.includeSourceAppendix)
    var activeMedicationsOnly by mutableStateOf(false)
    var medicationsInCsv by mutableStateOf(false)
    var paper by mutableStateOf(paper)
    var grayscale by mutableStateOf(false)

    val fromInvalid get() = period == ExportPeriod.CUSTOM && fromText.isNotBlank() && ExportOptions.parseDate(fromText) == null
    val toInvalid get() = period == ExportPeriod.CUSTOM && toText.isNotBlank() && ExportOptions.parseDate(toText) == null

    /** Bounds of [period] ending [today]; custom reads the typed dates. */
    fun bounds(today: LocalDate): Pair<LocalDate?, LocalDate?> = when (period) {
        ExportPeriod.ALL -> null to null
        ExportPeriod.LAST_3_MONTHS -> today.minus(3, DateTimeUnit.MONTH) to today
        ExportPeriod.LAST_12_MONTHS -> today.minus(12, DateTimeUnit.MONTH) to today
        ExportPeriod.CUSTOM -> ExportOptions.parseDate(fromText) to ExportOptions.parseDate(toText)
    }

    val reversed: Boolean get() {
        val from = ExportOptions.parseDate(fromText)
        val to = ExportOptions.parseDate(toText)
        return period == ExportPeriod.CUSTOM && from != null && to != null && from > to
    }

    /** Null while a typed date is malformed or the range is reversed: nothing can be exported then. */
    fun options(today: LocalDate): ExportOptions? {
        if (fromInvalid || toInvalid || reversed) return null
        val (from, to) = bounds(today)
        return ExportOptions(
            from = from,
            to = to,
            testCodes = testCodes,
            activeMedicationsOnly = activeMedicationsOnly,
            includeMedicationsCsv = medicationsInCsv,
            paper = paper,
            grayscale = grayscale,
            includeMedicationsPdf = includeMedications,
            family = family ?: ExportFamily.LAB_HISTORY,
            includeLabsPdf = includeLabs,
            includeDoseHistory = includeDoses,
            includeDeviceData = includeDevice,
            includeSourceAppendix = includeSources,
        )
    }
}

/** Holds the one draft in progress. */
object ExportSession {
    private var current: ExportDraft? = null

    fun draft(profileId: String, kind: String, paper: PaperSize): ExportDraft =
        current?.takeIf { it.profileId == profileId && it.kind == kind } ?: ExportDraft(profileId, kind, paper).also { current = it }

    /** The draft for this profile and kind, if one was started. */
    fun existing(profileId: String, kind: String): ExportDraft? = current?.takeIf { it.profileId == profileId && it.kind == kind }
}

/** A confirmed test the user can include, with its latest confirmed reading. */
data class ExportTestChoice(val code: String, val name: String, val latestValue: String, val latestAt: kotlin.time.Instant, val count: Int)

object ExportScope {
    fun tests(data: ProfileData): List<ExportTestChoice> = data.observations
        .filter { it.profileId == data.profile.profileId && it.confirmation == ConfirmationStatus.CONFIRMED }
        .groupBy { UnitNormalizer.canonicalTestCode(it.testCode) }
        .map { (code, rows) ->
            val latest = rows.maxBy { it.collectedAt }
            val value = latest.qualitativeValue ?: "${app.vezuna.export.DoctorReportFormat.recorded(latest.originalValue, latest.valueDecimals)} ${latest.originalUnit}".trim()
            ExportTestChoice(code, latest.displayName, value, latest.collectedAt, rows.size)
        }
        .sortedBy { it.name.lowercase() }

    /** Confirmed results the options include (zero when the lab section is off). */
    fun confirmedCount(data: ProfileData, options: ExportOptions): Int =
        if (!options.includeLabsPdf) 0 else options.apply(data).observations.count { it.profileId == data.profile.profileId && it.confirmation == ConfirmationStatus.CONFIRMED }

    /** False when every selected section would print nothing; the preview then offers to change the selection. */
    fun hasContent(data: ProfileData, options: ExportOptions, csv: Boolean, zone: TimeZone): Boolean {
        val confirmed = options.apply(data).observations.any { it.profileId == data.profile.profileId && it.confirmation == ConfirmationStatus.CONFIRMED }
        if (csv) return confirmed || (options.includeMedicationsCsv && data.medications.any { it.profileId == data.profile.profileId })
        return (options.includeLabsPdf && confirmed) ||
            (options.includeMedicationsPdf && options.apply(data).medications.any { it.profileId == data.profile.profileId }) ||
            (options.includeDoseHistory && DoctorExportContent.doseRows(data, options, zone).isNotEmpty()) ||
            (options.includeDeviceData && DoctorExportContent.deviceRows(data, options, zone).isNotEmpty())
    }
}

/** One previewed lab row of the CSV, read back from the exact text that will be saved. */
data class CsvPreviewRow(val collectedAt: String, val test: String, val value: String, val unit: String)

object CsvPreview {
    /** Lab rows of [csv] as written by CsvDoctorExporter (every field quoted); stops at the medications block. */
    fun rows(csv: String): List<CsvPreviewRow> = csv.lineSequence().drop(1).takeWhile { it.isNotBlank() }.map(::fields)
        .filter { it.size >= 13 }
        .map { cells ->
            val value = cells[12].ifEmpty { cells[4] }
            CsvPreviewRow(cells[1], cells[3], value.removePrefix("'"), if (cells[12].isEmpty()) cells[5] else "")
        }.toList()

    /** Splits one CSV line; quotes may contain commas and doubled quotes. */
    internal fun fields(line: String): List<String> {
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                quoted && char == '"' && line.getOrNull(index + 1) == '"' -> { cell.append('"'); index++ }
                char == '"' -> quoted = !quoted
                !quoted && char == ',' -> { out += cell.toString(); cell.clear() }
                else -> cell.append(char)
            }
            index++
        }
        out += cell.toString()
        return out
    }
}
