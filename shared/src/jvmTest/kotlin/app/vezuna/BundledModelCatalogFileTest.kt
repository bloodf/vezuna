package app.vezuna

import app.vezuna.llm.ModelCatalog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The one catalog file both apps package must always parse; real entries need real store-hosted asset values. */
class BundledModelCatalogFileTest {
    @Test
    fun shippedCatalogFileParses() {
        val file = File("src/commonMain/composeResources/files/model-packs.json")
        assertTrue(file.isFile, "catalog source missing: ${file.absolutePath}")
        ModelCatalog.fromJson(file.readText())
    }
}
