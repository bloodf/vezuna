package com.bloodf.vitahearth.llm

import com.bloodf.vitahearth.domain.ConfirmationStatus
import com.bloodf.vitahearth.domain.ProfileData
import com.bloodf.vitahearth.export.DoctorExportContent

object HealthPromptBuilder {
    private val SAFETY_RULES = """
        You are VitaHearth's on-device health education assistant.
        FACTS below are user-confirmed records. EDUCATION is general information, not a patient-specific conclusion.
        Keep record facts and general education in separate labeled sections.
        Never diagnose, calculate new clinical values, recommend starting/stopping medication, or change doses.
        Do not infer missing results. Tell the user to consult a qualified clinician for interpretation or treatment.
        If asked for diagnosis, dose, or a calculation, decline that portion and offer general education instead.
    """.trimIndent()

    fun build(data: ProfileData, selectedObservationIds: Set<String>, question: String): String {
        val selected = data.observations.filter {
            it.profileId == data.profile.profileId &&
                it.observationId in selectedObservationIds &&
                it.confirmation == ConfirmationStatus.CONFIRMED
        }.sortedBy { it.collectedAt }
        val facts = if (selected.isEmpty()) {
            "No confirmed records were selected. Do not invent patient facts."
        } else {
            selected.joinToString("\n") {
                "- ${it.collectedAt}: ${it.displayName} (${it.testCode}), original ${it.originalValue} ${it.originalUnit}, normalized ${it.normalizedValue} ${it.normalizedUnit}, report reference ${DoctorExportContent.rangeText(it.referenceRange)}"
            }
        }
        return buildString {
            appendLine("SYSTEM RULES")
            appendLine(SAFETY_RULES)
            appendLine("CONFIRMED RECORD FACTS")
            appendLine(facts)
            appendLine("EDUCATION BOUNDARY")
            appendLine("General education must not be presented as a finding from the records above.")
            appendLine("USER QUESTION")
            append(question.trim())
        }
    }
}
