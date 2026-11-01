package app.vezuna.llm

import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.ProfileData
import app.vezuna.export.DoctorExportContent
import app.vezuna.localization.ContentLanguage

object HealthPromptBuilder {
    private val SAFETY_RULES = """
        You are Vezuna's on-device health education assistant.
        FACTS below are user-confirmed records. EDUCATION is general information, not a patient-specific conclusion.
        Keep record facts and general education in separate labeled sections.
        Never diagnose, calculate new clinical values, recommend starting/stopping medication, or change doses.
        Do not infer missing results. Tell the user to consult a qualified clinician for interpretation or treatment.
        If asked for diagnosis, dose, or a calculation, decline that portion and offer general education instead.
    """.trimIndent()

    /** Safety rules stay in English (the instruction language); only the answer language varies. */
    internal fun languageRule(language: ContentLanguage): String {
        val name = when (language) {
            ContentLanguage.EN -> "English"
            ContentLanguage.PT -> "Brazilian Portuguese"
            ContentLanguage.ES -> "Spanish"
        }
        return "Write your entire answer in $name, whatever language the records or the question use. The safety rules above apply in every language."
    }

    fun build(
        data: ProfileData,
        selectedObservationIds: Set<String>,
        question: String,
        language: ContentLanguage = ContentLanguage.EN,
    ): String {
        val selected = data.observations.filter {
            it.profileId == data.profile.profileId &&
                it.observationId in selectedObservationIds &&
                it.confirmation == ConfirmationStatus.CONFIRMED
        }.sortedBy { it.collectedAt }
        val facts = if (selected.isEmpty()) {
            "No confirmed records were selected. Do not invent patient facts."
        } else {
            selected.joinToString("\n") {
                if (it.isQualitative) return@joinToString "- ${it.collectedAt}: ${it.displayName} (${it.testCode}), qualitative result ${it.qualitativeValue}, report reference ${DoctorExportContent.rangeText(it.referenceRange)}"
                "- ${it.collectedAt}: ${it.displayName} (${it.testCode}), original ${it.originalValue} ${it.originalUnit}, normalized ${it.normalizedValue} ${it.normalizedUnit}, report reference ${DoctorExportContent.rangeText(it.referenceRange)}"
            }
        }
        return buildString {
            appendLine("SYSTEM RULES")
            appendLine(SAFETY_RULES)
            appendLine(languageRule(language))
            appendLine("CONFIRMED RECORD FACTS")
            appendLine(facts)
            appendLine("EDUCATION BOUNDARY")
            appendLine("General education must not be presented as a finding from the records above.")
            appendLine("USER QUESTION")
            append(question.trim())
        }
    }
}
