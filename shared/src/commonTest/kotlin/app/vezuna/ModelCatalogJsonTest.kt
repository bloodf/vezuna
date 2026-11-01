package app.vezuna

import app.vezuna.llm.ModelCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelCatalogJsonTest {
    private fun pack(id: String, sha: String = "a".repeat(64)) =
        """{"modelId":"$id","displayName":"Test $id","fileName":"$id.gguf","androidAssetPackId":"a-$id","iosAssetPackId":"i-$id","sizeBytes":10,"minimumRamBytes":20,"maximumContextLength":30,"sha256":"$sha"}"""

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
}
