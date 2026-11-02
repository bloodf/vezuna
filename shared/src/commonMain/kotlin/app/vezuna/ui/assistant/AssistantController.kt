package app.vezuna.ui.assistant

import app.vezuna.data.VezunaRepository
import app.vezuna.llm.LocalHealthChat
import app.vezuna.localization.L10nKey
import app.vezuna.localization.message
import app.vezuna.ui.ControllerCore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class AssistantController(
    private val core: ControllerCore,
    private val repository: VezunaRepository,
    private val chat: LocalHealthChat,
) {
    /** Returns true when the answer was stored; on failure (or cancellation of the calling job) the caller keeps the user's question. */
    suspend fun ask(question: String): Boolean {
        var persisted = false
        try {
            core.operation {
                val data = core.requireData()
                val before = data.chat.size
                core.mutableState.value = core.mutableState.value.copy(streamingAnswer = "")
                try {
                    chat.ask(data, core.mutableState.value.selectedObservationIds, question) { partial ->
                        core.mutableState.value = core.mutableState.value.copy(streamingAnswer = partial)
                    }
                } finally {
                    core.mutableState.value = core.mutableState.value.copy(streamingAnswer = null)
                    // Stop may arrive after the exchange was saved: reload so the screen shows what was stored.
                    withContext(NonCancellable) {
                        core.loadProfile(data.profile.profileId)
                        persisted = (core.mutableState.value.data?.chat?.size ?: before) > before
                    }
                }
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // The answer is already stored, so the caller should clear the input as for a normal success.
            if (persisted) return true
            throw cancellation
        }
        return core.mutableState.value.error == null
    }

    /** Permanently deletes every chat message of the current profile through the repository. Records are untouched. */
    suspend fun clearChatHistory() = core.operation {
        val data = core.requireData()
        try {
            data.chat.forEach { repository.deleteChatMessage(data.profile.profileId, it.messageId) }
        } finally {
            withContext(NonCancellable) { core.loadProfile(data.profile.profileId) }
        }
        core.mutableState.value = core.mutableState.value.copy(notice = message(L10nKey.CHAT_HISTORY_CLEARED))
    }
}
