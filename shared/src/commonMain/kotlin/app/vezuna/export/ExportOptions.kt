package app.vezuna.export

import app.vezuna.domain.LabObservation
import app.vezuna.domain.ProfileData
import app.vezuna.domain.UnitNormalizer
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.Instant

/** What a doctor export contains. The defaults export everything. */
data class ExportOptions(
    /** Inclusive collection-date bounds (UTC calendar dates); null means unbounded. */
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    /** Canonical test codes (see [UnitNormalizer.canonicalTestCode]); empty means every test. */
    val testCodes: Set<String> = emptySet(),
    val activeMedicationsOnly: Boolean = false,
    val includeMedicationsCsv: Boolean = false,
) {
    init {
        require(from == null || to == null || from <= to) { "Export start date is after the end date" }
    }

    /** The profile data restricted to this selection; results and medications are filtered, nothing else is touched. */
    fun apply(data: ProfileData): ProfileData = data.copy(
        observations = data.observations.filter(::includes),
        medications = data.medications.filter { !activeMedicationsOnly || it.active },
    )

    private fun includes(observation: LabObservation): Boolean {
        val day = observation.collectedAt.toLocalDateTime(TimeZone.UTC).date
        return (from == null || day >= from) &&
            (to == null || day <= to) &&
            (testCodes.isEmpty() || UnitNormalizer.canonicalTestCode(observation.testCode) in testCodes)
    }

    companion object {
        /** Blank or malformed text yields null; callers decide whether that is an error. */
        fun parseDate(text: String): LocalDate? = try {
            LocalDate.parse(text.trim())
        } catch (error: IllegalArgumentException) {
            null
        }

        /** Completed years on [on]; null when no birth date is recorded or it lies in the future. */
        fun ageYears(birthDate: LocalDate?, on: Instant): Int? {
            if (birthDate == null) return null
            val today = on.toLocalDateTime(TimeZone.UTC).date
            if (birthDate > today) return null
            val hadBirthday = today.monthNumber * 100 + today.dayOfMonth >= birthDate.monthNumber * 100 + birthDate.dayOfMonth
            return today.year - birthDate.year - if (hadBirthday) 0 else 1
        }
    }
}
