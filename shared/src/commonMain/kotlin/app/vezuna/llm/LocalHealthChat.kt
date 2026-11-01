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
    suspend fun ask(data: ProfileData, selectedObservationIds: Set<String>, question: String): ChatMessage {
        if (question.isBlank()) failMessage(L10nKey.ENTER_QUESTION)
        val profileId = data.profile.profileId
        repository.addChatMessage(ChatMessage(profileId, randomId(), ChatMessage.Role.USER, question.trim(), currentInstant()))
        val prompt = HealthPromptBuilder.build(data, selectedObservationIds, question, language())
        val answer = ChatMessage(profileId, randomId(), ChatMessage.Role.ASSISTANT, llm.generate(prompt), currentInstant())
        repository.addChatMessage(answer)
        return answer
    }
}
