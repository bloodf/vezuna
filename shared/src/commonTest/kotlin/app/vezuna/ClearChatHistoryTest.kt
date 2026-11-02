package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.domain.ChatMessage
import app.vezuna.domain.Profile
import app.vezuna.llm.InstalledModel
import app.vezuna.llm.InstalledModelManager
import app.vezuna.llm.LlmGenerationSettings
import app.vezuna.llm.LocalHealthChat
import app.vezuna.llm.LocalLlm
import app.vezuna.llm.ModelCatalog
import app.vezuna.llm.ModelFileStore
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.ui.AppController
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClearChatHistoryTest {
    private val now = Instant.parse("2026-11-02T01:00:00Z")

    private object NoModels : ModelFileStore {
        override suspend fun installed() = emptyList<InstalledModel>()
        override suspend fun install(model: InstalledModel, sourcePath: String) = Unit
        override suspend fun delete(model: InstalledModel) = Unit
        override suspend fun saveSelection(modelId: String?) = Unit
        override suspend fun savedSelection(): String? = null
        override suspend fun verifyIntegrity(model: InstalledModel) = true
    }

    private object NoLlm : LocalLlm {
        override suspend fun load(modelPath: String, settings: LlmGenerationSettings) = Unit
        override suspend fun generate(prompt: String): String = ""
        override suspend fun unload() = Unit
    }

    @Test
    fun clearDeletesOnlyThisProfilesChat() = runTest {
        val repository = InMemoryVezunaRepository()
        repository.createProfile(Profile("a", "A", createdAt = now))
        repository.createProfile(Profile("b", "B", createdAt = now))
        repository.addChatMessage(ChatMessage("a", "a1", ChatMessage.Role.USER, "q", now))
        repository.addChatMessage(ChatMessage("a", "a2", ChatMessage.Role.ASSISTANT, "r", now))
        repository.addChatMessage(ChatMessage("b", "b1", ChatMessage.Role.USER, "q", now))
        val controller = AppController(
            repository, InstalledModelManager(ModelCatalog.unconfigured(), NoModels, NoLlm), LocalHealthChat(repository, NoLlm),
            clock = { now },
        )
        controller.initialize()
        controller.switchProfile("a")
        controller.clearChatHistory()
        assertTrue(repository.readProfile("a").chat.isEmpty())
        assertEquals(1, repository.readProfile("b").chat.size)
        assertTrue(controller.state.value.data!!.chat.isEmpty())
        assertEquals(L10nKey.CHAT_HISTORY_CLEARED, (controller.state.value.notice as UserText.Key).key)
    }
}
