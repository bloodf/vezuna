package app.vezuna.ui.assistant

import app.vezuna.data.VezunaRepository
import app.vezuna.llm.LocalHealthChat
import app.vezuna.localization.L10nKey
import app.vezuna.localization.message
import app.vezuna.ui.ControllerCore
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

internal class AssistantController(
    private val core: ControllerCore,
    private val repository: VezunaRepository,
    private val chat: LocalHealthChat,
) {
    private var askJob: Job? = null

    /** A profile switch ends a running answer: it belongs to the previous profile. */
    fun cancelAsk() { askJob?.cancel() }

    private fun isOpen(profileId: String) = core.mutableState.value.data?.profile?.profileId == profileId

    /** Returns true when the answer was stored; on failure (or cancellation of the calling job) the caller keeps the user's question. */
    suspend fun ask(question: String): Boolean {
        var persisted = false
        askJob = currentCoroutineContext()[Job]
        try {
            core.operation {
                val data = core.requireData()
                val before = data.chat.size
                val profileId = data.profile.profileId
                core.mutableState.value = core.mutableState.value.copy(streamingAnswer = "")
                try {
                    chat.ask(data, core.mutableState.value.selectedObservationIds, question) { partial ->
                        if (isOpen(profileId)) core.mutableState.value = core.mutableState.value.copy(streamingAnswer = partial)
                    }
                } finally {
                    if (isOpen(profileId)) core.mutableState.value = core.mutableState.value.copy(streamingAnswer = null)
                    // Stop may arrive after the exchange was saved: reload so the screen shows what was stored.
                    withContext(NonCancellable) {
                        core.reloadCurrentProfile()
                        persisted = isOpen(profileId) && (core.mutableState.value.data?.chat?.size ?: before) > before
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
            withContext(NonCancellable) { core.reloadCurrentProfile() }
        }
        core.mutableState.value = core.mutableState.value.copy(notice = message(L10nKey.CHAT_HISTORY_CLEARED))
    }
}
