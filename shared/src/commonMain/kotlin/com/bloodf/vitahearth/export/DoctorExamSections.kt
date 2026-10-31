package com.bloodf.vitahearth.export

import com.bloodf.vitahearth.domain.UnitNormalizer

data class DoctorExamSection(
    val testName: String,
    val testCode: String,
    val unit: String,
    val latest: DoctorResultRow,
    val history: List<DoctorResultRow>,
    val trend: DoctorTrendSeries?,
)

object DoctorExamSections {
    fun from(document: DoctorPdfDocument): List<DoctorExamSection> = document.resultRows
        .groupBy { row ->
            UnitNormalizer.canonicalTestCode(row.testCode) to row.normalizedUnit.lowercase()
        }
        .map { (_, rows) ->
            val ordered = rows.sortedBy { it.collectedAt }
            val latest = ordered.last()
            val canonicalCode = UnitNormalizer.canonicalTestCode(latest.testCode)
            DoctorExamSection(
                testName = latest.testName,
                testCode = latest.testCode,
                unit = latest.normalizedUnit,
                latest = latest,
                history = ordered,
                trend = document.trends.firstOrNull { trend ->
                    UnitNormalizer.canonicalTestCode(trend.testCode) == canonicalCode &&
                        trend.unit.equals(latest.normalizedUnit, ignoreCase = true)
                },
            )
        }
        .sortedWith(compareBy({ it.testName }, { it.testCode }, { it.unit }))
}
