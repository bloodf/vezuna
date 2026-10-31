package com.bloodf.vitahearth.export

import com.bloodf.vitahearth.domain.ConfirmationStatus
import com.bloodf.vitahearth.domain.LabComparator
import com.bloodf.vitahearth.domain.LabObservation
import com.bloodf.vitahearth.domain.Medication
import com.bloodf.vitahearth.domain.RangeAssessment
import com.bloodf.vitahearth.domain.ProfileData
import com.bloodf.vitahearth.domain.ReferenceRange
import com.bloodf.vitahearth.domain.UnitNormalizer
import com.bloodf.vitahearth.platform.currentInstant
import kotlinx.datetime.Instant

data class DoctorPdfDocument(
    val title: String,
    val lines: List<String>,
    val trends: List<DoctorTrendSeries> = emptyList(),
    val generatedAt: Instant = currentInstant(),
    val profileDisplayName: String = "",
    val resultRows: List<DoctorResultRow> = emptyList(),
    val medicationRows: List<DoctorMedicationRow> = emptyList(),
) {
    val hasStructuredContent: Boolean get() = profileDisplayName.isNotEmpty() || resultRows.isNotEmpty() || medicationRows.isNotEmpty()
}

data class DoctorResultRow(
    val collectedAt: Instant,
    val testName: String,
    val testCode: String,
    val originalValue: Double,
    val originalUnit: String,
    val normalizedValue: Double,
    val normalizedUnit: String,
    val referenceRange: ReferenceRange,
    val assessment: RangeAssessment,
)

data class DoctorMedicationRow(
    val displayName: String,
    val strength: String?,
    val instructions: String?,
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
    fun document(data: ProfileData, title: String = "Health summary"): DoctorPdfDocument {
        val confirmed = data.observations
            .filter { it.profileId == data.profile.profileId && it.confirmation == ConfirmationStatus.CONFIRMED }
            .sortedWith(compareBy({ it.collectedAt }, { it.observationId }))
        return DoctorPdfDocument(
            title = title,
            lines = lines(data),
            trends = trends(data),
            profileDisplayName = data.profile.displayName,
            resultRows = confirmed.map { observation ->
                DoctorResultRow(
                    collectedAt = observation.collectedAt,
                    testName = observation.displayName,
                    testCode = observation.testCode,
                    originalValue = observation.originalValue,
                    originalUnit = observation.originalUnit,
                    normalizedValue = observation.normalizedValue,
                    normalizedUnit = observation.normalizedUnit,
                    referenceRange = observation.referenceRange,
                    assessment = assessmentForExport(observation),
                )
            },
            medicationRows = data.medications
                .filter { it.profileId == data.profile.profileId }
                .sortedBy { it.displayName }
                .map { DoctorMedicationRow(it.displayName, it.strength, it.instructions) },
        )
    }

    private fun assessmentForExport(observation: LabObservation): RangeAssessment {
        val range = observation.referenceRange
        val originalUnitMatches = range.unit == null || range.unit.equals(observation.originalUnit, ignoreCase = true)
        return if (originalUnitMatches) {
            LabComparator.assess(observation.originalValue, range)
        } else {
            LabComparator.assess(observation)
        }
    }

    fun trends(data: ProfileData): List<DoctorTrendSeries> = data.observations
        .filter { it.profileId == data.profile.profileId && it.confirmation == ConfirmationStatus.CONFIRMED }
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

    fun lines(data: ProfileData): List<String> = buildList {
        add("Profile: ${data.profile.displayName}")
        add("Confirmed laboratory results")
        data.observations.filter { it.profileId == data.profile.profileId && it.confirmation == ConfirmationStatus.CONFIRMED }
            .sortedBy { it.collectedAt }
            .forEach {
                add("${it.collectedAt} | ${it.displayName} (${it.testCode}) | original ${it.originalValue} ${it.originalUnit} | normalized ${it.normalizedValue} ${it.normalizedUnit} | reference ${rangeText(it.referenceRange)}")
            }
        add("Medications (recorded, not recommendations)")
        data.medications.filter { it.profileId == data.profile.profileId }.forEach { medication: Medication ->
            add("${medication.displayName} | ${medication.strength.orEmpty()} | ${medication.instructions.orEmpty()}")
        }
    }

    fun rangeText(range: com.bloodf.vitahearth.domain.ReferenceRange): String = when {
        range.text != null -> range.text
        range.lower == null && range.upper == null -> "Not provided"
        range.lower == null -> "≤ ${DoctorReportFormat.value(range.upper!!)} ${range.unit.orEmpty()}".trim()
        range.upper == null -> "≥ ${DoctorReportFormat.value(range.lower)} ${range.unit.orEmpty()}".trim()
        else -> "${DoctorReportFormat.value(range.lower)}–${DoctorReportFormat.value(range.upper)} ${range.unit.orEmpty()}".trim()
    }
}

internal fun formatDoctorChartValue(value: Double): String {
    if (value == value.toLong().toDouble()) return value.toLong().toString()
    val rounded = kotlin.math.round(value * 1_000_000.0) / 1_000_000.0
    return rounded.toString()
}

class CsvDoctorExporter {
    fun export(data: ProfileData): String = buildString {
        appendLine("profile_id,collected_at,test_code,test_name,original_value,original_unit,normalized_value,normalized_unit,reference_lower,reference_upper,reference_unit,reference_text")
        data.observations
            .filter { it.profileId == data.profile.profileId && it.confirmation == ConfirmationStatus.CONFIRMED }
            .sortedBy { it.collectedAt }
            .forEach { observation ->
                val values = listOf(
                    observation.profileId,
                    observation.collectedAt.toString(),
                    observation.testCode,
                    observation.displayName,
                    observation.originalValue.toString(),
                    observation.originalUnit,
                    observation.normalizedValue.toString(),
                    observation.normalizedUnit,
                    observation.referenceRange.lower?.toString().orEmpty(),
                    observation.referenceRange.upper?.toString().orEmpty(),
                    observation.referenceRange.unit.orEmpty(),
                    observation.referenceRange.text.orEmpty(),
                )
                appendLine(values.joinToString(",") { escape(it) })
            }
    }

    internal fun escape(raw: String): String {
        val protected = if (raw.firstOrNull { !it.isWhitespace() } in setOf('=', '+', '-', '@')) "'$raw" else raw
        return "\"${protected.replace("\"", "\"\"")}\""
    }
}
