package app.vezuna.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.until
import kotlin.math.abs
import kotlin.math.roundToLong

object UnitNormalizer {
    private val units = mapOf(
        "g/l" to UnitScale("g/L", 1.0),
        "g/dl" to UnitScale("g/L", 10.0),
        "mg/dl" to UnitScale("mg/L", 10.0),
        "mg/l" to UnitScale("mg/L", 1.0),
        "ug/l" to UnitScale("mg/L", 0.001),
        "µg/l" to UnitScale("mg/L", 0.001),
        "μg/l" to UnitScale("mg/L", 0.001),
        "ng/ml" to UnitScale("µg/L", 1.0),
        "ug/ml" to UnitScale("mg/L", 1.0),
        "mg" to UnitScale("g", 0.001),
        "g" to UnitScale("g", 1.0),
        "kg" to UnitScale("g", 1_000.0),
        "mmol/l" to UnitScale("mmol/L", 1.0),
        "umol/l" to UnitScale("mmol/L", 0.001),
        "µmol/l" to UnitScale("mmol/L", 0.001),
        "μmol/l" to UnitScale("mmol/L", 0.001),
        "10^9/l" to UnitScale("10^9/L", 1.0),
        "10^12/l" to UnitScale("10^12/L", 1.0),
        "%" to UnitScale("%", 1.0),
    )

    fun normalize(value: Double, unit: String): NormalizedQuantity {
        require(value.isFinite()) { "Lab value must be finite" }
        val key = unit.trim().lowercase().replace(" ", "")
        val scale = units[key] ?: return NormalizedQuantity(value, unit.trim())
        return NormalizedQuantity(value * scale.factor, scale.unit)
    }

    fun areComparable(first: LabObservation, second: LabObservation): Boolean =
        !first.isQualitative && !second.isQualitative &&
            first.profileId == second.profileId &&
            canonicalTestCode(first.testCode) == canonicalTestCode(second.testCode) &&
            first.normalizedUnit.equals(second.normalizedUnit, ignoreCase = true)

    fun canonicalTestCode(code: String): String = code.trim().uppercase().replace(Regex("[^A-Z0-9]"), "")

    private data class UnitScale(val unit: String, val factor: Double)
}

data class NormalizedQuantity(val value: Double, val unit: String)

enum class RangeAssessment { BELOW, WITHIN, ABOVE, UNKNOWN }
enum class ChangeDirection { INCREASE, DECREASE, UNCHANGED, NOT_COMPARABLE }

data class ObservationComparison(
    val direction: ChangeDirection,
    val delta: Double?,
    val currentRange: RangeAssessment,
)

object LabComparator {
    private const val EPSILON = 1e-9

    fun assess(value: Double, range: ReferenceRange): RangeAssessment = when {
        range.lower != null && value < range.lower -> RangeAssessment.BELOW
        range.upper != null && value > range.upper -> RangeAssessment.ABOVE
        range.lower != null || range.upper != null -> RangeAssessment.WITHIN
        else -> RangeAssessment.UNKNOWN
    }

    fun assess(observation: LabObservation): RangeAssessment {
        if (observation.isQualitative) return RangeAssessment.UNKNOWN
        val range = observation.referenceRange
        val rangeUnit = range.unit ?: observation.originalUnit
        val lower = range.lower?.let { UnitNormalizer.normalize(it, rangeUnit) }
        val upper = range.upper?.let { UnitNormalizer.normalize(it, rangeUnit) }
        if (listOfNotNull(lower, upper).any { !it.unit.equals(observation.normalizedUnit, ignoreCase = true) }) {
            return RangeAssessment.UNKNOWN
        }
        return assess(
            observation.normalizedValue,
            ReferenceRange(lower?.value, upper?.value, range.text, observation.normalizedUnit),
        )
    }

    fun compare(previous: LabObservation, current: LabObservation): ObservationComparison {
        if (!UnitNormalizer.areComparable(previous, current)) {
            return ObservationComparison(ChangeDirection.NOT_COMPARABLE, null, assess(current))
        }
        val delta = current.normalizedValue - previous.normalizedValue
        val tolerance = EPSILON * maxOf(1.0, abs(previous.normalizedValue), abs(current.normalizedValue))
        val direction = when {
            delta > tolerance -> ChangeDirection.INCREASE
            delta < -tolerance -> ChangeDirection.DECREASE
            else -> ChangeDirection.UNCHANGED
        }
        return ObservationComparison(direction, delta, assess(current))
    }
}

object ClinicalDuplicateDetector {
    fun fingerprint(observation: LabObservation): String {
        observation.qualitativeValue?.let {
            return listOf(
                observation.profileId,
                UnitNormalizer.canonicalTestCode(observation.testCode),
                observation.collectedAt.toString(),
                "qualitative",
                it.trim().lowercase(),
            ).joinToString("|")
        }
        val normalized = UnitNormalizer.normalize(observation.normalizedValue, observation.normalizedUnit)
        val canonicalValue = (normalized.value * 1_000_000_000).roundToLong()
        return listOf(
            observation.profileId,
            UnitNormalizer.canonicalTestCode(observation.testCode),
            observation.collectedAt.toString(),
            canonicalValue.toString(),
            UnitNormalizer.normalize(1.0, normalized.unit).unit,
        ).joinToString("|")
    }

    fun areDuplicates(first: LabObservation, second: LabObservation): Boolean =
        first.profileId == second.profileId && fingerprint(first) == fingerprint(second)
}

data class TrendPoint(val observation: LabObservation, val xEpochSeconds: Long, val y: Double)
data class TrendSeries(val profileId: String, val testCode: String, val unit: String, val points: List<TrendPoint>)

object TrendPreparation {
    fun forTest(profileId: String, testCode: String, observations: List<LabObservation>): TrendSeries? {
        val matching = observations.filter {
            it.profileId == profileId &&
                it.confirmation == ConfirmationStatus.CONFIRMED && !it.isQualitative &&
                UnitNormalizer.canonicalTestCode(it.testCode) == UnitNormalizer.canonicalTestCode(testCode)
        }
        val unit = matching.firstOrNull()?.normalizedUnit ?: return null
        val points = matching.filter { it.normalizedUnit.equals(unit, ignoreCase = true) }
            .sortedBy { it.collectedAt }
            .map { TrendPoint(it, it.collectedAt.epochSeconds, it.normalizedValue) }
        return TrendSeries(profileId, UnitNormalizer.canonicalTestCode(testCode), unit, points)
    }
}

/** Plain-text history of qualitative results for one test, oldest first. */
data class QualitativeTimeline(val testCode: String, val displayName: String, val entries: List<LabObservation>)

object QualitativeTimelines {
    fun forProfile(profileId: String, observations: List<LabObservation>): List<QualitativeTimeline> = observations
        .filter { it.profileId == profileId && it.confirmation == ConfirmationStatus.CONFIRMED && it.isQualitative }
        .groupBy { UnitNormalizer.canonicalTestCode(it.testCode) }
        .map { (code, rows) ->
            val ordered = rows.sortedWith(compareBy({ it.collectedAt }, { it.observationId }))
            QualitativeTimeline(code, ordered.last().displayName, ordered)
        }
        .sortedBy { it.displayName.lowercase() }
}

object MedicationRecurrence {
    fun occursOn(schedule: DoseSchedule, date: LocalDate): Boolean {
        if (date < schedule.startDate || (schedule.endDate != null && date > schedule.endDate)) return false
        return when (schedule.recurrence) {
            Recurrence.DAILY -> true
            Recurrence.WEEKLY -> date.dayOfWeek in schedule.daysOfWeek
            Recurrence.EVERY_N_DAYS -> daysBetween(schedule.startDate, date) % schedule.intervalDays == 0L
        }
    }

    private fun daysBetween(start: LocalDate, end: LocalDate): Long = start.until(end, DateTimeUnit.DAY)
}
