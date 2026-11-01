package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.llm.InstalledModel
import app.vezuna.llm.InstalledModelManager
import app.vezuna.llm.LlmGenerationSettings
import app.vezuna.llm.LocalHealthChat
import app.vezuna.llm.LocalLlm
import app.vezuna.llm.ModelCatalog
import app.vezuna.llm.ModelFileStore
import app.vezuna.ui.AppController
import app.vezuna.ui.SourceFileStore

internal fun labController(repository: InMemoryVezunaRepository, sourceStore: SourceFileStore? = null): AppController {
    val llm = LabTestLlm()
    return AppController(
        repository,
        InstalledModelManager(ModelCatalog.unconfigured(), LabTestModelStore(), llm),
        LocalHealthChat(repository, llm),
        sourceStore = sourceStore,
    )
}

/** Records saved and discarded keys so tests can prove stored files follow their documents. */
internal class RecordingSourceStore : SourceFileStore {
    val saved = mutableListOf<String>()
    val discarded = mutableListOf<String>()

    override suspend fun save(profileId: String, documentId: String, bytes: ByteArray): String =
        "source-$documentId".also { saved += it }

    override suspend fun discard(key: String) {
        discarded += key
    }
}

private class LabTestLlm : LocalLlm {
    override suspend fun load(modelPath: String, settings: LlmGenerationSettings) = Unit
    override suspend fun generate(prompt: String): String = ""
    override suspend fun unload() = Unit
}

private class LabTestModelStore : ModelFileStore {
    override suspend fun installed(): List<InstalledModel> = emptyList()
    override suspend fun install(model: InstalledModel, sourcePath: String) = Unit
    override suspend fun verifyIntegrity(model: InstalledModel): Boolean = true
    override suspend fun delete(model: InstalledModel) = Unit
    override suspend fun saveSelection(modelId: String?) = Unit
    override suspend fun savedSelection(): String? = null
}
