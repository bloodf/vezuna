package app.vezuna.llm

import app.vezuna.data.VezunaRepository
import app.vezuna.domain.ChatMessage
import app.vezuna.domain.ProfileData
import app.vezuna.localization.ContentLanguage
import app.vezuna.localization.L10nKey
import app.vezuna.localization.failMessage
import app.vezuna.platform.currentInstant
import app.vezuna.platform.randomId

class LocalHealthChat(
    private val repository: VezunaRepository,
    private val llm: LocalLlm,
    private val language: () -> ContentLanguage = ContentLanguage::device,
) {
    /**
     * Streams the answer through [onPartial]. The question and answer are stored together only when generation
     * completes, so a failed or cancelled request leaves no half exchange behind and the caller can retry.
     */
    suspend fun ask(
        data: ProfileData,
        selectedObservationIds: Set<String>,
        question: String,
        onPartial: (String) -> Unit = {},
    ): ChatMessage {
        if (question.isBlank()) failMessage(L10nKey.ENTER_QUESTION)
        val profileId = data.profile.profileId
        val prompt = HealthPromptBuilder.build(data, selectedObservationIds, question, language())
        val text = StringBuilder()
        llm.generateStream(prompt).collect { chunk ->
            text.append(chunk)
            onPartial(text.toString())
        }
        repository.addChatMessage(ChatMessage(profileId, randomId(), ChatMessage.Role.USER, question.trim(), currentInstant()))
        val answer = ChatMessage(profileId, randomId(), ChatMessage.Role.ASSISTANT, text.toString(), currentInstant())
        repository.addChatMessage(answer)
        return answer
    }
}
