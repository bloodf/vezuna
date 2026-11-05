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
    fun beforeDeliveryBothTheStoreCopyAndTheInstalledCopyMustFit() {
        assertEquals(2_200_000_000, ModelFitPolicy.requiredFreeBytes(pack, delivered = false))
        assertEquals(1_200_000_000, ModelFitPolicy.requiredFreeBytes(pack, delivered = true))
        val error = assertFailsWith<VitaMessageException> { ModelFitPolicy.requireFits(pack, capacity(8_000_000_000, 1_500_000_000)) }
        assertEquals(L10nKey.MODEL_INSUFFICIENT_STORAGE, error.userText.key)
        ModelFitPolicy.requireFits(pack, capacity(8_000_000_000, 1_500_000_000), delivered = true)
    }

    @Test
    fun passesWhenEnoughAndRejectsUnknown() {
        ModelFitPolicy.requireFits(pack, capacity(4_000_000_000, 1_200_000_000), delivered = true)
        val error = assertFailsWith<VitaMessageException> { ModelFitPolicy.requireFits(pack, UnknownDeviceCapacity) }
        assertEquals(L10nKey.MODEL_CAPACITY_UNAVAILABLE, error.userText.key)
    }

    @Test
    fun blocksLowRam() {
        val error = assertFailsWith<VitaMessageException> { ModelFitPolicy.requireFits(pack, capacity(3_999_999_999, 9_000_000_000)) }
        assertEquals(L10nKey.MODEL_INSUFFICIENT_RAM, error.userText.key)
    }

    @Test
    fun blocksLowStorage() {
        val error = assertFailsWith<VitaMessageException> { ModelFitPolicy.requireFits(pack, capacity(8_000_000_000, 1_199_999_999), delivered = true) }
        assertEquals(L10nKey.MODEL_INSUFFICIENT_STORAGE, error.userText.key)
    }
    @Test fun rejectsOverflowAndChecksExactPhases() {
        val pinned = pack.copy(sizeBytes = 1_117_320_736)
        assertEquals(1_340_784_884, ModelFitPolicy.requiredFreeBytes(pinned, true))
        assertEquals(2_458_105_620, ModelFitPolicy.requiredFreeBytes(pinned, false))
        for (delivered in listOf(false, true)) {
            val required = ModelFitPolicy.requiredFreeBytes(pinned, delivered)
            ModelFitPolicy.requireFits(pinned, capacity(8_000_000_000, required), delivered)
            assertFailsWith<VitaMessageException> { ModelFitPolicy.requireFits(pinned, capacity(8_000_000_000, required - 1), delivered) }
        }
        assertFailsWith<VitaMessageException> { ModelFitPolicy.requiredFreeBytes(pack.copy(sizeBytes = Long.MAX_VALUE)) }
    }
    @Test fun loadMemoryRequiresValidSnapshotAndExactSyntheticBudget() {
        val snapshot = app.vezuna.llm.LoadMemorySnapshot(8_000_000_000, 100, false, 50)
        app.vezuna.llm.requireLoadMemory(pack, snapshot, 100)
        for (sample in listOf(snapshot.copy(availableBytes = 99), snapshot.copy(lowMemory = true))) {
            val error = assertFailsWith<VitaMessageException> { app.vezuna.llm.requireLoadMemory(pack, sample, 100) }
            assertEquals(L10nKey.MODEL_MEMORY_PRESSURE, error.userText.key)
        }
        val invalid = assertFailsWith<VitaMessageException> { app.vezuna.llm.requireLoadMemory(pack, snapshot.copy(totalBytes = 0), 100) }
        assertEquals(L10nKey.MODEL_CAPACITY_UNAVAILABLE, invalid.userText.key)
        val unsupported = assertFailsWith<VitaMessageException> { app.vezuna.llm.requireLoadMemory(pack, snapshot, 0) }
        assertEquals(L10nKey.MODEL_LOAD_POLICY_UNAVAILABLE, unsupported.userText.key)
    }
    @Test fun providerExceptionsAreLocalizedAndCancellationIsPreserved() {
        val broken = object : DeviceCapacity {
            override fun totalRamBytes(): Long? = error("failure")
            override fun freeStorageBytes(): Long? = 1
        }
        val error = assertFailsWith<VitaMessageException> { ModelFitPolicy.requireFits(pack, broken) }
        assertEquals(L10nKey.MODEL_CAPACITY_UNAVAILABLE, error.userText.key)
        val canceled = object : DeviceCapacity {
            override fun totalRamBytes(): Long? = throw kotlinx.coroutines.CancellationException("cancel")
            override fun freeStorageBytes(): Long? = 1
        }
        assertFailsWith<kotlinx.coroutines.CancellationException> { ModelFitPolicy.requireFits(pack, canceled) }
    }
    @Test fun eachMissingInstallReadingIsUnavailable() {
        for ((ram, free) in listOf(null to 10L, 8_000_000_000L to null, null to null)) {
            val error = assertFailsWith<VitaMessageException> { ModelFitPolicy.requireFits(pack, capacity(ram, free)) }
            assertEquals(L10nKey.MODEL_CAPACITY_UNAVAILABLE, error.userText.key)
        }
    }
}
