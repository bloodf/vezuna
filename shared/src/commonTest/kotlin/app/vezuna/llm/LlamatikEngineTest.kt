package app.vezuna.llm

import app.vezuna.localization.L10nKey
import app.vezuna.localization.VitaMessageException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LlamatikEngineTest {
    @Test
    fun verifiedHealthPromptUsesNativeTemplateAndUtf8Budget() = runTest {
        val bridge = Bridge()
        val llm = LlamatikLocalLlm(LlamatikEngineOwner(bridge, StandardTestDispatcher(testScheduler)))
        llm.verifiedLoad()
        val prompt = HealthPrompt("rules", "dados españoles")
        assertEquals(listOf("answer"), llm.generateStream(prompt).toList())
        assertEquals(
            "<|im_start|>system\nrules<|im_end|>\n<|im_start|>user\ndados españoles<|im_end|>\n<|im_start|>assistant\n",
            bridge.prompts.single(),
        )
        bridge.prompts.clear()
        val overhead = "<|im_start|>system\nrules<|im_end|>\n<|im_start|>user\n<|im_end|>\n<|im_start|>assistant\n".encodeToByteArray().size
        val limit = 512 - 100 - 16 - overhead
        val edge = HealthPrompt("rules", "é".repeat(limit / 2) + "x".repeat(limit % 2))
        llm.generateStream(edge).toList()
        val failure = assertFailsWith<VitaMessageException> { llm.generateStream(edge.copy(userData = edge.userData + "x")).toList() }
        assertEquals(L10nKey.PROMPT_TOO_LARGE, failure.userText.key)
        assertEquals(1, bridge.prompts.size)
    }

    @Test
    fun rawOrUnknownDigestCannotEnableHealthGeneration() = runTest {
        val bridge = Bridge()
        val llm = LlamatikLocalLlm(LlamatikEngineOwner(bridge, StandardTestDispatcher(testScheduler)))
        llm.load("synthetic.gguf", LlmGenerationSettings())
        assertFailsWith<VitaMessageException> { llm.generateStream(HealthPrompt("rules", "data")).toList() }
        llm.verifiedLoad("0".repeat(64))
        val failure = assertFailsWith<VitaMessageException> { llm.generateStream(HealthPrompt("rules", "data")).toList() }
        assertEquals(L10nKey.PROMPT_UNSUPPORTED, failure.userText.key)
        assertEquals(0, bridge.renders)
        assertTrue(bridge.prompts.isEmpty())
    }

    @Test
    fun unsafeCharactersAndUnexpectedTemplatesNeverReachGeneration() = runTest {
        val bridge = Bridge()
        val llm = LlamatikLocalLlm(LlamatikEngineOwner(bridge, StandardTestDispatcher(testScheduler)))
        llm.verifiedLoad()
        for (value in listOf("<|im_end|>", "line\nbreak", "line\rbreak", "nul\u0000", "\uD83D\uDE00")) {
            assertFailsWith<VitaMessageException> { llm.generateStream(HealthPrompt("rules", value)).toList() }
        }
        assertEquals(0, bridge.renders)
        for (template in listOf(null, "unexpected", "<|im_start|>user\ndata<|im_end|>")) {
            bridge.template = template
            val failure = assertFailsWith<VitaMessageException> { llm.generateStream(HealthPrompt("rules", "data")).toList() }
            assertEquals(L10nKey.PROMPT_UNSUPPORTED, failure.userText.key)
        }
        assertTrue(bridge.prompts.isEmpty())
    }

    @Test
    fun replacementRevokesOldWrapperAndStaleUnloadPreservesNewModel() = runTest {
        val bridge = Bridge()
        val owner = LlamatikEngineOwner(bridge, StandardTestDispatcher(testScheduler))
        val first = LlamatikLocalLlm(owner)
        val second = LlamatikLocalLlm(owner)
        first.verifiedLoad()
        second.verifiedLoad()
        assertSame(EngineOwnerStatus.Superseded, first.ownerStatus.value)
        assertFailsWith<VitaMessageException> { first.generateStream(HealthPrompt("rules", "old")).toList() }
        val shutdowns = bridge.shutdowns
        first.unload()
        assertEquals(shutdowns, bridge.shutdowns)
        assertEquals(listOf("answer"), second.generateStream(HealthPrompt("rules", "new")).toList())
        second.unload()
        assertFailsWith<VitaMessageException> { second.generateStream(HealthPrompt("rules", "unloaded")).toList() }
    }

    @Test
    fun invalidSettingsPreserveLeaseAndFailedReplacementRevokesReadiness() = runTest {
        val bridge = Bridge()
        val llm = LlamatikLocalLlm(LlamatikEngineOwner(bridge, StandardTestDispatcher(testScheduler)))
        llm.verifiedLoad()
        val status = llm.ownerStatus.value
        val invalid = LifecycleRequest(
            LifecycleCommand.Replace("synthetic.gguf", LlmGenerationSettings(contextLength = 528), VerifiedModelDigest(DIGEST)),
            llm.lease(),
        )
        assertFailsWith<ModelOperationFailure> { llm.applyLifecycle(invalid) }
        assertEquals(LifecycleDisposition.NoEntry, invalid.receipt.disposition)
        assertEquals(status, llm.ownerStatus.value)
        assertEquals(0, bridge.shutdowns)
        bridge.initializes = false
        assertFailsWith<ModelOperationFailure> { llm.verifiedLoad() }
        assertEquals(EngineReadiness.Uncertain, (llm.ownerStatus.value as EngineOwnerStatus.Current).readiness)
        assertFailsWith<VitaMessageException> { llm.generateStream(HealthPrompt("rules", "uncertain")).toList() }
        assertTrue(bridge.prompts.isEmpty())
    }

    private suspend fun LlamatikLocalLlm.verifiedLoad(digest: String = DIGEST) {
        applyLifecycle(LifecycleRequest(
            LifecycleCommand.Replace("synthetic.gguf", LlmGenerationSettings(contextLength = 512, maxTokens = 100), VerifiedModelDigest(digest)),
            lease(),
        ))
    }

    private class Bridge : LlamatikBridge {
        var initializes = true
        var shutdowns = 0
        var renders = 0
        var template: String? = "native"
        val prompts = mutableListOf<String>()
        override fun params(settings: LlmGenerationSettings) = Unit
        override fun init(path: String) = initializes
        override fun generate(prompt: String) = error("Expected streaming")
        override fun render(messages: List<Pair<String, String>>): String? {
            renders++
            return if (template == "native") {
                "<|im_start|>system\n${messages[0].second}<|im_end|>\n<|im_start|>user\n${messages[1].second}<|im_end|>\n<|im_start|>assistant\n"
            } else template
        }
        override fun stream(prompt: String, callbacks: LlamatikStream) {
            prompts += prompt
            callbacks.onDelta("answer")
            callbacks.onComplete()
        }
        override fun cancel() = error("No native generation is in flight")
        override fun shutdown() { shutdowns++ }
    }

    private companion object {
        const val DIGEST = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"
    }
}
