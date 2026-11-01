package app.vezuna

import app.vezuna.llm.DeviceCapacity
import app.vezuna.llm.ModelFitPolicy
import app.vezuna.llm.ModelPack
import app.vezuna.llm.UnknownDeviceCapacity
import app.vezuna.localization.L10nKey
import app.vezuna.localization.VitaMessageException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelFitPolicyTest {
    private val pack = ModelPack("m", "M", "m.gguf", "a", "i", sizeBytes = 1_000_000_000, minimumRamBytes = 4_000_000_000, maximumContextLength = 4096, sha256 = "a".repeat(64))

    private fun capacity(ram: Long?, free: Long?) = object : DeviceCapacity {
        override fun totalRamBytes() = ram
        override fun freeStorageBytes() = free
    }

    @Test
    fun requiresTwentyPercentStorageMargin() {
        assertEquals(1_200_000_000, ModelFitPolicy.requiredFreeBytes(pack))
    }

    @Test
    fun passesWhenEnoughOrUnknown() {
        ModelFitPolicy.requireFits(pack, capacity(4_000_000_000, 1_200_000_000))
        ModelFitPolicy.requireFits(pack, UnknownDeviceCapacity)
    }

    @Test
    fun blocksLowRam() {
        val error = assertFailsWith<VitaMessageException> { ModelFitPolicy.requireFits(pack, capacity(3_999_999_999, 9_000_000_000)) }
        assertEquals(L10nKey.MODEL_INSUFFICIENT_RAM, error.userText.key)
    }

    @Test
    fun blocksLowStorage() {
        val error = assertFailsWith<VitaMessageException> { ModelFitPolicy.requireFits(pack, capacity(8_000_000_000, 1_199_999_999)) }
        assertEquals(L10nKey.MODEL_INSUFFICIENT_STORAGE, error.userText.key)
    }
}
