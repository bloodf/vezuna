package app.vezuna

import app.vezuna.llm.ModelCatalog
import app.vezuna.llm.ModelPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ModelCatalogJsonTest {
    private fun pack(id: String, sha: String = "a".repeat(64)) =
        """{"modelId":"$id","displayName":"Test $id","fileName":"$id.gguf","androidAssetPackId":"a-$id","iosAssetPackId":"i-$id","sizeBytes":10,"minimumRamBytes":20,"maximumContextLength":30,"sha256":"$sha"}"""

    /** One catalog entry; a null [android] or [ios] leaves that platform out of the entry. */
    private fun entry(
        modelId: String,
        fileName: String = "$modelId.gguf",
        android: String? = null,
        ios: String? = null,
        sha: String = "a".repeat(64),
    ): String {
        val fields = mutableListOf(
            "\"modelId\":\"$modelId\"",
            "\"displayName\":\"Test $modelId\"",
            "\"fileName\":\"$fileName\"",
        )
        android?.let { fields += "\"androidAssetPackId\":\"$it\"" }
        ios?.let { fields += "\"iosAssetPackId\":\"$it\"" }
        fields += "\"sizeBytes\":10"
        fields += "\"minimumRamBytes\":20"
        fields += "\"maximumContextLength\":30"
        fields += "\"sha256\":\"$sha\""
        return fields.joinToString(",", prefix = "{", postfix = "}")
    }

    @Test
    fun emptyArrayIsAnUnconfiguredCatalog() {
        assertEquals(emptyList(), ModelCatalog.fromJson("[]").packs)
    }

    @Test
    fun validPacksParseAndSortByName() {
        assertEquals(listOf("b", "a").sorted(), ModelCatalog.fromJson("[${pack("b")},${pack("a")}]").packs.map { it.modelId })
    }

    @Test
    fun invalidContentIsRejectedNotIgnored() {
        assertFailsWith<Exception> { ModelCatalog.fromJson("{") }
        assertFailsWith<Exception> { ModelCatalog.fromJson("[${pack("x", sha = "short")}]") }
        assertFailsWith<Exception> { ModelCatalog.fromJson("[${pack("x")},${pack("x")}]") }
    }

    @Test
    fun androidOnlyOfferIsListedOnAndroidOnly() {
        val catalog = ModelCatalog.fromJson("[${entry("m", android = "a-m")}]")
        assertEquals(listOf("m"), catalog.forPlatform(ModelPlatform.ANDROID).packs.map { it.modelId })
        assertEquals(emptyList(), catalog.forPlatform(ModelPlatform.IOS).packs.map { it.modelId })
    }

    @Test
    fun iosOnlyOfferIsListedOnIosOnly() {
        val catalog = ModelCatalog.fromJson("[${entry("m", ios = "i-m")}]")
        assertEquals(emptyList(), catalog.forPlatform(ModelPlatform.ANDROID).packs.map { it.modelId })
        assertEquals(listOf("m"), catalog.forPlatform(ModelPlatform.IOS).packs.map { it.modelId })
    }

    @Test
    fun dualPlatformOfferIsListedOnBothPlatforms() {
        val catalog = ModelCatalog.fromJson("[${pack("m")}]")
        assertEquals(listOf("m"), catalog.forPlatform(ModelPlatform.ANDROID).packs.map { it.modelId })
        assertEquals(listOf("m"), catalog.forPlatform(ModelPlatform.IOS).packs.map { it.modelId })
    }

    @Test
    fun offerWithoutAnyStoreIdIsRejected() {
        assertFailsWith<Exception> { ModelCatalog.fromJson("[${entry("m")}]") }
        assertFailsWith<Exception> {
            ModelCatalog.fromJson(
                """[{"modelId":"m","displayName":"Test m","fileName":"m.gguf","androidAssetPackId":null,"iosAssetPackId":null,"sizeBytes":10,"minimumRamBytes":20,"maximumContextLength":30,"sha256":"${"a".repeat(64)}"}]""",
            )
        }
    }

    @Test
    fun blankStoreIdIsRejected() {
        assertFailsWith<Exception> { ModelCatalog.fromJson("[${entry("m", android = "")}]") }
        assertFailsWith<Exception> { ModelCatalog.fromJson("[${entry("m", ios = "")}]") }
        assertFailsWith<Exception> { ModelCatalog.fromJson("[${entry("m", android = "", ios = "")}]") }
        assertFailsWith<Exception> { ModelCatalog.fromJson("[${entry("m", android = " ", ios = "i-m")}]") }
    }

    @Test
    fun malformedOfferForTheOtherPlatformIsStillRejected() {
        assertFailsWith<Exception> {
            ModelCatalog.fromJson("[${entry("m", ios = "i-m", sha = "short")}]").forPlatform(ModelPlatform.ANDROID)
        }
    }

    @Test
    fun duplicateOfferAcrossPlatformsIsRejectedBeforeFiltering() {
        // The Android catalog would hold a single entry after filtering, so the duplicate must fail earlier than that.
        assertFailsWith<Exception> {
            ModelCatalog.fromJson(
                "[${entry("m", fileName = "a.gguf", android = "a-m")},${entry("m", fileName = "b.gguf", ios = "i-m")}]",
            ).forPlatform(ModelPlatform.ANDROID)
        }
    }

    @Test
    fun duplicateFileNameAcrossPlatformsIsRejectedBeforeFiltering() {
        assertFailsWith<Exception> {
            ModelCatalog.fromJson(
                "[${entry("m", fileName = "same.gguf", android = "a-m")},${entry("n", fileName = "same.gguf", ios = "i-n")}]",
            ).forPlatform(ModelPlatform.ANDROID)
        }
    }

    @Test
    fun deliveryIdIsReportedOnlyForItsOwnPlatform() {
        val catalog = ModelCatalog.fromJson("[${entry("m", android = "a-m")},${entry("n", ios = "i-n")},${pack("d")}]")
        val packs = catalog.packs.associateBy { it.modelId }
        assertEquals("a-m", packs.getValue("m").deliveryIdFor(ModelPlatform.ANDROID))
        assertNull(packs.getValue("m").deliveryIdFor(ModelPlatform.IOS))
        assertEquals("i-n", packs.getValue("n").deliveryIdFor(ModelPlatform.IOS))
        assertNull(packs.getValue("n").deliveryIdFor(ModelPlatform.ANDROID))
        assertEquals("a-d", packs.getValue("d").deliveryIdFor(ModelPlatform.ANDROID))
        assertEquals("i-d", packs.getValue("d").deliveryIdFor(ModelPlatform.IOS))
    }
}
