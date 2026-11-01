package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.domain.ChatMessage
import app.vezuna.domain.Profile
import app.vezuna.llm.LlmGenerationSettings
import app.vezuna.llm.LocalHealthChat
import app.vezuna.llm.LocalLlm
import app.vezuna.localization.VitaMessageException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StreamingChatTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private suspend fun repositoryWithProfile(): InMemoryVezunaRepository =
        InMemoryVezunaRepository().also { it.createProfile(Profile("p", "Ana", createdAt = now)) }

    @Test
    fun streamedChunksAreReportedThenStoredTogetherWithQuestion() = runTest {
        val repository = repositoryWithProfile()
        val llm = ScriptedLlm(flow { emit("Hel"); emit("lo") })
        val partials = mutableListOf<String>()

        LocalHealthChat(repository, llm).ask(repository.readProfile("p"), emptySet(), "  why?  ") { partials += it }

        assertEquals(listOf("Hel", "Hello"), partials)
        val chat = repository.readProfile("p").chat
        assertEquals(listOf(ChatMessage.Role.USER to "why?", ChatMessage.Role.ASSISTANT to "Hello"), chat.map { it.role to it.content })
    }

    @Test
    fun failedGenerationStoresNothingSoRetryDoesNotDuplicate() = runTest {
        val repository = repositoryWithProfile()
        val llm = ScriptedLlm(flow { emit("par"); error("engine failed") })

        assertFailsWith<IllegalStateException> { LocalHealthChat(repository, llm).ask(repository.readProfile("p"), emptySet(), "q") }

        assertEquals(emptyList(), repository.readProfile("p").chat)
    }

    @Test
    fun cancellationStoresNothing() = runTest {
        val repository = repositoryWithProfile()
        val gate = CompletableDeferred<Unit>()
        val llm = ScriptedLlm(flow { emit("par"); gate.await(); emit("tial") })
        val job = launch { LocalHealthChat(repository, llm).ask(repository.readProfile("p"), emptySet(), "q") }
        runCurrent()

        job.cancel()
        runCurrent()

        assertEquals(emptyList(), repository.readProfile("p").chat)
    }

    @Test
    fun blankAnswerIsRejectedAndNothingIsStored() = runTest {
        val repository = repositoryWithProfile()
        val llm = ScriptedLlm(flow { emit("   ") })

        assertFailsWith<VitaMessageException> { LocalHealthChat(repository, llm).ask(repository.readProfile("p"), emptySet(), "q") }

        assertEquals(emptyList(), repository.readProfile("p").chat)
    }
}

private class ScriptedLlm(private val stream: Flow<String>) : LocalLlm {
    override suspend fun load(modelPath: String, settings: LlmGenerationSettings) = Unit
    override suspend fun generate(prompt: String): String = ""
    override fun generateStream(prompt: String): Flow<String> = stream
    override suspend fun unload() = Unit
}
