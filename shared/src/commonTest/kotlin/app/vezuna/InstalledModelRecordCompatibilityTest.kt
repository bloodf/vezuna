package app.vezuna

import app.vezuna.llm.InstalledModel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Installed-model records written while both delivery ids were mandatory must still decode and re-encode unchanged. */
class InstalledModelRecordCompatibilityTest {
    private val json = Json { encodeDefaults = true }
    private val serializer = ListSerializer(InstalledModel.serializer())
    private val sha = "a".repeat(64)

    /** Byte for byte what the model stores wrote while every pack named both platforms. */
    private val legacy =
        """[{"pack":{"modelId":"legacy","displayName":"Legacy","fileName":"legacy.gguf","androidAssetPackId":"legacy-android","iosAssetPackId":"legacy-ios","sizeBytes":10,"minimumRamBytes":20,"maximumContextLength":30,"sha256":"$sha"},"localPath":"/models/legacy.gguf","installedAtEpochSeconds":1700000000}]"""

    @Test
    fun legacyDualPlatformRecordStillDecodesAndKeepsItsMetadata() {
        val models = json.decodeFromString(serializer, legacy)
        assertEquals(1, models.size)
        val pack = models.single().pack
        assertEquals("legacy", pack.modelId)
        assertEquals("Legacy", pack.displayName)
        assertEquals("legacy.gguf", pack.fileName)
        assertEquals("legacy-android", pack.androidAssetPackId)
        assertEquals("legacy-ios", pack.iosAssetPackId)
        assertEquals(10L, pack.sizeBytes)
        assertEquals("/models/legacy.gguf", models.single().localPath)
        assertEquals(1700000000L, models.single().installedAtEpochSeconds)
    }

    @Test
    fun legacyRecordReencodesIdentically() {
        val decoded = json.decodeFromString(serializer, legacy)
        assertEquals(legacy, json.encodeToString(serializer, decoded))
    }

    @Test
    fun androidOnlyRecordRoundTripsWithAnAbsentOtherPlatform() {
        val record =
            """[{"pack":{"modelId":"android-only","displayName":"Android only","fileName":"android-only.gguf","androidAssetPackId":"android-only-pack","iosAssetPackId":null,"sizeBytes":10,"minimumRamBytes":20,"maximumContextLength":30,"sha256":"$sha"},"localPath":"/models/android-only.gguf","installedAtEpochSeconds":1700000001}]"""
        val decoded = json.decodeFromString(serializer, record)
        assertEquals("android-only-pack", decoded.single().pack.androidAssetPackId)
        assertNull(decoded.single().pack.iosAssetPackId)
        assertEquals(record, json.encodeToString(serializer, decoded))
    }
}
