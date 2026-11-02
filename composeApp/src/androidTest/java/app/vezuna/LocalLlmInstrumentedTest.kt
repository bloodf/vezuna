package app.vezuna

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.vezuna.llm.LlamatikLocalLlm
import app.vezuna.llm.LlmGenerationSettings
import kotlinx.coroutines.runBlocking
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
}
