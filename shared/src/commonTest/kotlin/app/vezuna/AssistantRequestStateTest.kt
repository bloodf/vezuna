package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.data.VezunaRepository
import app.vezuna.domain.ProfileData
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.domain.Profile
import app.vezuna.llm.HealthPrompt
import app.vezuna.llm.LlmGenerationSettings
import app.vezuna.llm.LocalHealthChat
import app.vezuna.llm.LocalLlm
import app.vezuna.ui.ControllerCore
import app.vezuna.ui.assistant.AssistantController
import app.vezuna.ui.assistant.AssistantDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class AssistantRequestStateTest {
    @Test
    fun completedSubmissionClearsOnlyTheUneditedDraft() {
        val draft = AssistantDraft()
        draft.edit("question")
        draft.clearSubmitted(draft.version)
        assertEquals("", draft.text)

        draft.edit("question")
        val submitted = draft.version
        draft.edit("new draft")
        draft.clearSubmitted(submitted)
        assertEquals("new draft", draft.text)
        draft.edit("question")
        draft.clearSubmitted(submitted)
        assertEquals("question", draft.text)
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    @Test
    fun cancelledPredecessorCannotClearSuccessorStreamLoadingOrError() = runTest {
        val oldCleanup = CompletableDeferred<Unit>()
        val finishSuccessor = CompletableDeferred<Unit>()
        var calls = 0
        val llm = object : LocalLlm {
            override suspend fun load(modelPath: String, settings: LlmGenerationSettings) = Unit
            override suspend fun unload() = Unit
            override suspend fun generate(prompt: String) = ""
            override fun generateStream(prompt: HealthPrompt): Flow<String> {
                val call = ++calls
                return object : Flow<String> {
                    override suspend fun collect(collector: FlowCollector<String>) {
                        if (call == 1) {
                            collector.emit("old")
                            try {
                                awaitCancellation()
                            } finally {
                                // Deliberately emulate a bridge delivering a callback after cancellation.
                                withContext(NonCancellable) {
                                    oldCleanup.await()
                                    collector.emit("late old partial")
                                }
                                error("late predecessor failure")
                            }
                        } else {
                            collector.emit("successor")
                            finishSuccessor.await()
                            collector.emit(" answer")
                        }
                    }
                }
            }
        }
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val repository = InMemoryVezunaRepository()
        repository.createProfile(Profile("p", "Test", createdAt = now))
        val core = ControllerCore(repository, { now }, { TimeZone.UTC }, null)
        core.openProfile("p")
        val controller = AssistantController(core, repository, LocalHealthChat(repository, llm))
        var oldSuccess = true
        val old = launch { oldSuccess = controller.ask("old question") }
        runCurrent()
        assertEquals("old", core.mutableState.value.streamingAnswer)
        var successorSuccess = false
        val successor = launch { successorSuccess = controller.ask("new question") }
        runCurrent()
        assertEquals("successor", core.mutableState.value.streamingAnswer)
        oldCleanup.complete(Unit)
        old.join()
        assertFalse(oldSuccess)
        assertEquals("successor", core.mutableState.value.streamingAnswer)
        assertTrue(core.mutableState.value.loading)
        assertNull(core.mutableState.value.error)
        finishSuccessor.complete(Unit)
        successor.join()
        assertTrue(successorSuccess)
        assertFalse(core.mutableState.value.loading)
        assertNull(core.mutableState.value.streamingAnswer)
        assertEquals(listOf("new question", "successor answer"), core.mutableState.value.data!!.chat.map { it.content })
    }

    @Test
    fun stopAndFailureKeepDraftAndAllowRetry() = runTest {
        var calls = 0
        val llm = object : LocalLlm {
            override suspend fun load(modelPath: String, settings: LlmGenerationSettings) = Unit
            override suspend fun unload() = Unit
            override suspend fun generate(prompt: String) = ""
            override fun generateStream(prompt: HealthPrompt) = flow {
                when (++calls) {
                    1 -> { emit("partial"); awaitCancellation() }
                    2 -> error("generation failed")
                    else -> emit("retry answer")
                }
            }
        }
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val repository = InMemoryVezunaRepository()
        repository.createProfile(Profile("p", "Test", createdAt = now))
        val core = ControllerCore(repository, { now }, { TimeZone.UTC }, null)
        core.openProfile("p")
        val controller = AssistantController(core, repository, LocalHealthChat(repository, llm))
        val draft = AssistantDraft()
        draft.edit("question")
        val stopped = launch { if (controller.ask(draft.text)) draft.clearSubmitted(draft.version) }
        runCurrent()
        controller.cancelAsk()
        stopped.join()
        assertEquals("question", draft.text)
        assertFalse(core.mutableState.value.loading)
        assertNull(core.mutableState.value.streamingAnswer)
        assertFalse(controller.ask(draft.text))
        assertEquals("question", draft.text)
        assertTrue(repository.readProfile("p").chat.isEmpty())
        assertTrue(controller.ask(draft.text))
        assertEquals(listOf("question", "retry answer"), repository.readProfile("p").chat.map { it.content })
    }

    @Test
    fun postGenerationReloadFailureKeepsDraftAndReportsLocalizedError() = runTest {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val storage = InMemoryVezunaRepository()
        storage.createProfile(Profile("p", "Test", createdAt = now))
        val repository = object : VezunaRepository by storage {
            override suspend fun peekProfile(profileId: String): ProfileData = error("reload failed")
        }
        val llm = object : LocalLlm {
            override suspend fun load(modelPath: String, settings: LlmGenerationSettings) = Unit
            override suspend fun unload() = Unit
            override suspend fun generate(prompt: String) = ""
            override fun generateStream(prompt: HealthPrompt) = flow { emit("answer") }
        }
        val core = ControllerCore(repository, { now }, { TimeZone.UTC }, null)
        core.openProfile("p")
        val controller = AssistantController(core, repository, LocalHealthChat(repository, llm))
        val draft = AssistantDraft()
        draft.edit("question")
        val submitted = draft.version
        val success = controller.ask(draft.text)
        if (success) draft.clearSubmitted(submitted)

        assertFalse(success)
        assertEquals("question", draft.text)
        assertEquals(UserText.Key(L10nKey.ACTION_FAILED), core.mutableState.value.error)
        assertFalse(core.mutableState.value.loading)
        assertNull(core.mutableState.value.streamingAnswer)
        assertEquals(listOf("question", "answer"), storage.readProfile("p").chat.map { it.content })
    }

    @Test
    fun obsoleteReloadFailureCannotEscapeOrChangeSuccessorState() = runTest {
        val releaseReload = CompletableDeferred<Unit>()
        val finishSuccessor = CompletableDeferred<Unit>()
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val storage = InMemoryVezunaRepository()
        storage.createProfile(Profile("p", "Test", createdAt = now))
        var reloads = 0
        val repository = object : VezunaRepository by storage {
            override suspend fun peekProfile(profileId: String): ProfileData {
                if (++reloads == 1) {
                    releaseReload.await()
                    error("obsolete reload failed")
                }
                return storage.peekProfile(profileId)
            }
        }
        var calls = 0
        val llm = object : LocalLlm {
            override suspend fun load(modelPath: String, settings: LlmGenerationSettings) = Unit
            override suspend fun unload() = Unit
            override suspend fun generate(prompt: String) = ""
            override fun generateStream(prompt: HealthPrompt) = flow {
                if (++calls == 1) emit("old answer") else {
                    emit("successor")
                    finishSuccessor.await()
                    emit(" answer")
                }
            }
        }
        val core = ControllerCore(repository, { now }, { TimeZone.UTC }, null)
        core.openProfile("p")
        val controller = AssistantController(core, repository, LocalHealthChat(repository, llm))
        var oldSuccess = true
        val old = launch { oldSuccess = controller.ask("old question") }
        runCurrent()
        assertEquals(1, reloads)
        var successorSuccess = false
        val successor = launch { successorSuccess = controller.ask("new question") }
        runCurrent()
        assertEquals("successor", core.mutableState.value.streamingAnswer)
        releaseReload.complete(Unit)
        old.join()

        assertFalse(oldSuccess)
        assertNull(core.mutableState.value.error)
        assertTrue(core.mutableState.value.loading)
        assertEquals("successor", core.mutableState.value.streamingAnswer)
        finishSuccessor.complete(Unit)
        successor.join()
        assertTrue(successorSuccess)
        assertNull(core.mutableState.value.error)
        assertFalse(core.mutableState.value.loading)
        assertNull(core.mutableState.value.streamingAnswer)
        assertEquals(listOf("old question", "old answer", "new question", "successor answer"), core.mutableState.value.data!!.chat.map { it.content })
    }

}
