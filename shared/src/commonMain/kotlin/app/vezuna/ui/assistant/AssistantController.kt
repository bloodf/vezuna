package app.vezuna.ui.assistant

import app.vezuna.data.VezunaRepository
import app.vezuna.llm.LocalHealthChat
import app.vezuna.localization.L10nKey
import app.vezuna.localization.message
import app.vezuna.localization.toUserText
import kotlinx.coroutines.CancellationException
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
    private var activeRequest: Any? = null

    /** A profile switch ends a running answer: it belongs to the previous profile. */
    fun cancelAsk() {
        activeRequest = null
        val job = askJob ?: return
        job.cancel()
        askJob = null
        core.mutableState.value = core.mutableState.value.copy(streamingAnswer = null, loading = false)
    }

    private fun isOpen(profileId: String) = core.mutableState.value.data?.profile?.profileId == profileId

    /** Only the current request may update the composer or report a persisted answer. */
    suspend fun ask(question: String): Boolean {
        val request = Any()
        askJob?.cancel()
        activeRequest = request
        askJob = currentCoroutineContext()[Job]
        var persisted = false
        var cancellation: CancellationException? = null
        var profileId: String? = null
        var before = 0
        fun ownsRequest() = activeRequest === request && profileId?.let(::isOpen) != false
        core.mutableState.value = core.mutableState.value.copy(loading = true, error = null, notice = null, streamingAnswer = "")
        try {
            val data = core.requireData()
            profileId = data.profile.profileId
            before = data.chat.size
            chat.ask(data, core.mutableState.value.selectedObservationIds, question) { partial ->
                if (ownsRequest()) core.mutableState.value = core.mutableState.value.copy(streamingAnswer = partial)
            }
            persisted = true
        } catch (cancelled: CancellationException) {
            // Stop can arrive after the exchange was saved. Reload below before deciding whether to clear the draft.
            cancellation = cancelled
        } catch (error: Throwable) {
            if (ownsRequest()) core.mutableState.value = core.mutableState.value.copy(error = error.toUserText())
        } finally {
            try {
                if (ownsRequest() && profileId != null) {
                    withContext(NonCancellable) {
                        core.reloadCurrentProfile()
                        if (ownsRequest() && cancellation != null) {
                            persisted = (core.mutableState.value.data?.chat?.size ?: before) > before
                        }
                    }
                }
            } finally {
                if (activeRequest === request) {
                    core.mutableState.value = core.mutableState.value.copy(streamingAnswer = null, loading = false)
                    askJob = null
                }
            }
        }
        if (cancellation != null && !(ownsRequest() && persisted)) throw cancellation
        return ownsRequest() && persisted
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
