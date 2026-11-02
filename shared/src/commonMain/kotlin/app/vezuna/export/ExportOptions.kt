package app.vezuna.export

import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.ProfileAge
import app.vezuna.domain.LabObservation
import app.vezuna.domain.ProfileData
import app.vezuna.domain.UnitNormalizer
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** What a doctor export contains. The defaults export everything. */
data class ExportOptions(
    /** Inclusive collection-date bounds (UTC calendar dates); null means unbounded. */
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    /** Canonical test codes (see [UnitNormalizer.canonicalTestCode]); empty means every test. */
    val testCodes: Set<String> = emptySet(),
    val activeMedicationsOnly: Boolean = false,
    val includeMedicationsCsv: Boolean = false,
    val paper: PaperSize = PaperSize.A4,
    val grayscale: Boolean = false,
    /** PDF medication section; when false the PDF says the section was not selected. */
    val includeMedicationsPdf: Boolean = true,
    /** Which PDF layout and title; also the file name. */
    val family: ExportFamily = ExportFamily.LAB_HISTORY,
    /** PDF laboratory section (confirmed results only). */
    val includeLabsPdf: Boolean = true,
    /** PDF dose-event table (#56): recorded doses inside the period, counts only, never a percentage. */
    val includeDoseHistory: Boolean = false,
    /** PDF device section: daily steps, daily average heart rate and weight readings inside the period. */
    val includeDeviceData: Boolean = false,
) {
    init {
        require(from == null || to == null || from <= to) { "Export start date is after the end date" }
    }

    /** The profile data restricted to this selection; results and medications are filtered, nothing else is touched. */
    fun apply(data: ProfileData): ProfileData = data.copy(
        observations = data.observations.filter(::includes),
        medications = data.medications.filter { !activeMedicationsOnly || it.active },
    )

    /** Unreviewed imported values of the profile inside this selection: excluded from the export, but counted in its scope. */
    fun unreviewedCount(data: ProfileData): Int {
        val profileId = data.profile.profileId
        val observations = data.observations.count {
            it.profileId == profileId && it.confirmation == ConfirmationStatus.UNREVIEWED && includes(it)
        }
        val candidates = data.ocrCandidates.count {
            it.profileId == profileId && it.status == ConfirmationStatus.UNREVIEWED && includes(it.testCode, it.collectedAt)
        }
        return observations + candidates
    }

    private fun includes(observation: LabObservation): Boolean = includes(observation.testCode, observation.collectedAt)

    private fun includes(testCode: String, collectedAt: Instant): Boolean {
        val day = collectedAt.toLocalDateTime(TimeZone.UTC).date
        return (from == null || day >= from) &&
            (to == null || day <= to) &&
            (testCodes.isEmpty() || UnitNormalizer.canonicalTestCode(testCode) in testCodes)
    }

    companion object {
        /** Blank or malformed text yields null; callers decide whether that is an error. */
        fun parseDate(text: String): LocalDate? = try {
            LocalDate.parse(text.trim())
        } catch (error: IllegalArgumentException) {
            null
        }

        /** Completed years on [on]; null when no birth date is recorded or it lies in the future. */
        fun ageYears(birthDate: LocalDate?, on: Instant): Int? = ProfileAge.years(birthDate, on.toLocalDateTime(TimeZone.UTC).date)
    }
}

/** The four PDF layouts the export hub offers. [slug] names the file. */
enum class ExportFamily(val slug: String) {
    HEALTH_SUMMARY("health-summary"),
    LAB_HISTORY("lab-history"),
    MEDICATION_RECORD("medication-record"),
    DEVICE_MEASUREMENTS("device-measurements"),
    ;

    /** Starting sections for this family; the user can change every one before exporting. */
    fun defaults(paper: PaperSize = PaperSize.A4): ExportOptions = when (this) {
        HEALTH_SUMMARY, LAB_HISTORY -> ExportOptions(family = this, paper = paper)
        MEDICATION_RECORD -> ExportOptions(family = this, paper = paper, includeLabsPdf = false, includeDoseHistory = true)
        DEVICE_MEASUREMENTS -> ExportOptions(family = this, paper = paper, includeLabsPdf = false, includeMedicationsPdf = false, includeDeviceData = true)
    }
}
