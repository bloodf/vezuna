package com.bloodf.vitahearth.llm

import com.bloodf.vitahearth.data.VitaHearthRepository
import com.bloodf.vitahearth.domain.ChatMessage
import com.bloodf.vitahearth.domain.ProfileData
import com.bloodf.vitahearth.localization.L10nKey
import com.bloodf.vitahearth.localization.failMessage
import com.bloodf.vitahearth.platform.currentInstant
import com.bloodf.vitahearth.platform.randomId

class LocalHealthChat(
    private val repository: VitaHearthRepository,
    private val llm: LocalLlm,
) {
    suspend fun ask(data: ProfileData, selectedObservationIds: Set<String>, question: String): ChatMessage {
        if (question.isBlank()) failMessage(L10nKey.ENTER_QUESTION)
        val profileId = data.profile.profileId
        repository.addChatMessage(ChatMessage(profileId, randomId(), ChatMessage.Role.USER, question.trim(), currentInstant()))
        val prompt = HealthPromptBuilder.build(data, selectedObservationIds, question)
        val answer = ChatMessage(profileId, randomId(), ChatMessage.Role.ASSISTANT, llm.generate(prompt), currentInstant())
        repository.addChatMessage(answer)
        return answer
    }
}
