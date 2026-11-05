package app.vezuna

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.vezuna.llm.LlamatikLocalLlm
import app.vezuna.llm.LlmGenerationSettings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalLlmInstrumentedTest {
    @Test
    fun loadsVerifiedGgufAndGeneratesOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        // Skipped (not failed) unless tools/release/stage_smoke_model.py staged the smoke model; models/ is never committed.
        assumeTrue("smoke.gguf not staged; run tools/release/stage_smoke_model.py", assets.list("")?.contains("smoke.gguf") == true)
        val model = File(context.cacheDir, "smoke.gguf")
        model.outputStream().use { output ->
            InstrumentationRegistry.getInstrumentation().context.assets.open("smoke.gguf").use { input -> input.copyTo(output) }
        }
        assertTrue("Smoke model was not staged from test assets", model.isFile)
        assertTrue("Smoke model must be a GGUF file", model.inputStream().use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && magic.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))
        })

        val llm = LlamatikLocalLlm()
        try {
            llm.load(model.absolutePath, LlmGenerationSettings(contextLength = 2048, maxTokens = 48, temperature = 0.0f))
            val answer = llm.generate(
                "<|im_start|>system\nYou are a concise offline assistant. Answer with exactly: LOCAL MODEL READY<|im_end|>\n" +
                    "<|im_start|>user\nReport your readiness.<|im_end|>\n<|im_start|>assistant\n",
            )
            assertTrue("Local model returned no text", answer.isNotBlank())
            println("LOCAL_LLM_SMOKE_PASSED responseChars=${answer.length}")
        } finally {
            llm.unload()
        }
    }

    @Test
    fun cancelsCollectorAfterPartialAndAllowsNextGeneration() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        assumeTrue("smoke.gguf not staged; run tools/release/stage_smoke_model.py", assets.list("")?.contains("smoke.gguf") == true)
        val model = File(instrumentation.targetContext.cacheDir, "smoke.gguf")
        model.outputStream().use { output -> assets.open("smoke.gguf").use { it.copyTo(output) } }

        val llm = LlamatikLocalLlm()
        try {
            withTimeout(60_000) {
                llm.load(model.absolutePath, LlmGenerationSettings(contextLength = 2048, maxTokens = 1024, temperature = 0.0f))
            }
            val partial = CompletableDeferred<Unit>()
            val collector = launch {
                llm.generateStream(
                    "<|im_start|>user\nWrite a long numbered list of simple outdoor activities.<|im_end|>\n<|im_start|>assistant\n",
                ).collect { chunk ->
                    if (chunk.isNotEmpty()) {
                        partial.complete(Unit)
                        // Hold the collector for cancellation; the buffered native stream may already have completed.
                        awaitCancellation()
                    }
                }
            }
            try {
                withTimeout(60_000) { partial.await() }
                println("COLLECTOR_CANCEL_PARTIAL_OBSERVED")
                assertTrue("Stream collector completed before cancellation", collector.isActive)
                println("COLLECTOR_CANCEL_REQUESTED")
                withTimeout(60_000) { collector.cancelAndJoin() }
                assertTrue("Stream collector did not cancel", collector.isCancelled)
                println("COLLECTOR_CANCEL_COLLECTOR_JOINED")
            } finally {
                // Joining must finish before unload, even when the test coroutine was cancelled.
                // This cannot forcibly interrupt a stuck native call; the external runner bounds that failure.
                withContext(NonCancellable) { collector.cancelAndJoin() }
            }

            val successor = withTimeout(60_000) {
                llm.generate("<|im_start|>user\nSay a short greeting.<|im_end|>\n<|im_start|>assistant\n")
            }
            assertTrue("Successor generation returned no text", successor.isNotBlank())
            println("COLLECTOR_CANCEL_SUCCESSOR_PROGRESS chars=${successor.length}")
        } finally {
            withContext(NonCancellable) { llm.unload() }
        }
    }
}
