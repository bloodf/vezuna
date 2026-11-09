package app.vezuna

import app.vezuna.llm.*
import app.vezuna.localization.L10nKey
import app.vezuna.localization.VitaMessageException
import app.vezuna.localization.failMessage
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ModelLoadAdmissionTest {
    private val pack = ModelPack("m", "M", "m.gguf", "a", "i", sizeBytes = 100, minimumRamBytes = 100, maximumContextLength = 4096, sha256 = "a".repeat(64))
    private val settings = LlmGenerationSettings()
    private val key = ModelLoadPolicyKey(pack.sha256, pack.sizeBytes, settings, "build")
    private val snapshot = LoadMemorySnapshot(200, 100, false, 10)

    @Test fun exactIdentityAndPositiveBudgetAreRequiredBeforeReadingMemory() {
        var calls = 0
        fun check(budgets: Map<ModelLoadPolicyKey, Long>, build: String = "build") {
            val policy = BudgetedModelLoadAdmission(build, budgets) { calls++; snapshot }
            val failure = assertFailsWith<VitaMessageException> { policy.requireFits(pack, settings) }
            assertEquals(L10nKey.MODEL_LOAD_POLICY_UNAVAILABLE, failure.userText.key)
        }
        check(emptyMap())
        check(mapOf(key to 0))
        check(mapOf(key to -1))
        check(mapOf(key.copy(sha256 = "other") to 100))
        check(mapOf(key.copy(sizeBytes = 101) to 100))
        check(mapOf(key.copy(settings = settings.copy(maxTokens = settings.maxTokens + 1)) to 100))
        check(mapOf(key to 100), "other")
        assertEquals(0, calls)
    }

    @Test fun validHitUsesCopiedBudgetAndChecksSnapshot() {
        val budgets = mutableMapOf(key to 100L)
        val policy = BudgetedModelLoadAdmission("build", budgets) { snapshot }
        budgets.clear()
        policy.requireFits(pack, settings)
        val invalid = BudgetedModelLoadAdmission("build", mapOf(key to 100L)) { snapshot.copy(totalBytes = 0) }
        assertEquals(L10nKey.MODEL_CAPACITY_UNAVAILABLE, assertFailsWith<VitaMessageException> { invalid.requireFits(pack, settings) }.userText.key)
    }

    @Test fun providerFailuresAndCancellationKeepTheirSemantics() {
        val canceled = CancellationException("cancel")
        val policy = BudgetedModelLoadAdmission("build", mapOf(key to 100L)) { throw canceled }
        assertSame(canceled, assertFailsWith<CancellationException> { policy.requireFits(pack, settings) })
        val localized = assertFailsWith<VitaMessageException> { failMessage(L10nKey.MODEL_MEMORY_PRESSURE) }
        val preserved = BudgetedModelLoadAdmission("build", mapOf(key to 100L)) { throw localized }
        assertSame(localized, assertFailsWith<VitaMessageException> { preserved.requireFits(pack, settings) })
        val broken = BudgetedModelLoadAdmission("build", mapOf(key to 100L)) { error("failure") }
        assertEquals(L10nKey.MODEL_CAPACITY_UNAVAILABLE, assertFailsWith<VitaMessageException> { broken.requireFits(pack, settings) }.userText.key)
    }
}
