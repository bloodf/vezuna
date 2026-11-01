package app.vezuna.llm

import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.localization.failMessage
import app.vezuna.localization.message
import app.vezuna.localization.toUserText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ModelPack(
    val modelId: String,
    val displayName: String,
    val fileName: String,
    val androidAssetPackId: String,
    val iosAssetPackId: String,
    val sizeBytes: Long,
    val minimumRamBytes: Long,
    val maximumContextLength: Int,
    val sha256: String,
) {
    init {
        require(modelId.isNotBlank() && displayName.isNotBlank())
        require(fileName.endsWith(".gguf") && !fileName.contains('/') && !fileName.contains('\\'))
        require(androidAssetPackId.isNotBlank() && iosAssetPackId.isNotBlank())
        require(sizeBytes > 0 && minimumRamBytes > 0 && maximumContextLength > 0)
        if (!SHA256.matches(sha256)) failMessage(L10nKey.MODEL_SHA_REQUIRED)
    }

    companion object { private val SHA256 = Regex("[0-9a-f]{64}") }
}

@Serializable
data class InstalledModel(
    val pack: ModelPack,
    val localPath: String,
    val installedAtEpochSeconds: Long,
)

class ModelCatalog(packs: List<ModelPack>) {
    val packs: List<ModelPack> = packs.sortedBy { it.displayName }

    init {
        require(packs.map { it.modelId }.distinct().size == packs.size)
        require(packs.map { it.fileName }.distinct().size == packs.size)
    }

    fun byId(modelId: String): ModelPack? = packsById[modelId]
    private val packsById = this.packs.associateBy { it.modelId }

    companion object {
        // Store shells provide signed pack metadata after configuring their own asset packs.
        fun unconfigured(): ModelCatalog = ModelCatalog(emptyList())

        private val json = Json { ignoreUnknownKeys = false }

        /** Parses `composeResources/files/model-packs.json`, the one catalog source for Android and iOS. Throws on invalid content. */
        fun fromJson(text: String): ModelCatalog = ModelCatalog(json.decodeFromString<List<ModelPack>>(text))
    }
}

/** A store-delivered pack file; [release] must be called once installation finished or failed. */
class ModelPackSource(val path: String, val release: () -> Unit = {})

interface ModelFileStore {
    suspend fun installed(): List<InstalledModel>
    /**
     * Streams the file at [sourcePath] into private storage, verifying size and lowercase SHA-256 while copying.
     * Implementations must never buffer the whole file, must remove partial output on failure and must
     * leave any previously installed copy untouched unless verification succeeds.
     */
    suspend fun install(model: InstalledModel, sourcePath: String)
    suspend fun delete(model: InstalledModel)
    suspend fun saveSelection(modelId: String?)
    suspend fun savedSelection(): String?

    /** Must stream the file digest; implementations never load the whole model into memory. */
    suspend fun verifyIntegrity(model: InstalledModel): Boolean
}

data class ModelManagerState(
    val installed: List<InstalledModel> = emptyList(),
    val selectedModelId: String? = null,
    val loadedModelId: String? = null,
    val busy: Boolean = false,
    val error: UserText? = null,
    val catalog: ModelCatalog = ModelCatalog.unconfigured(),
    /** Set when the bundled catalog could not be read; distinct from the intentional empty catalog. */
    val catalogError: UserText? = null,
)

class InstalledModelManager(
    initialCatalog: ModelCatalog,
    private val files: ModelFileStore,
    private val llm: LocalLlm,
) {
    private val mutex = Mutex()
    var catalog: ModelCatalog = initialCatalog
        private set
    private val mutableState = MutableStateFlow(ModelManagerState(catalog = initialCatalog))
    val state: StateFlow<ModelManagerState> = mutableState.asStateFlow()

    /** Loads the bundled catalog; a failure leaves it empty and sets [ModelManagerState.catalogError]. Call [refresh] afterwards. */
    suspend fun configureCatalog(load: suspend () -> ModelCatalog) {
        var error: UserText? = null
        val loaded = try {
            load()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            error = message(L10nKey.MODEL_CATALOG_UNREADABLE)
            ModelCatalog.unconfigured()
        }
        mutex.withLock {
            catalog = loaded
            mutableState.value = mutableState.value.copy(catalog = loaded, catalogError = error)
        }
    }

    suspend fun refresh() = mutex.withLock {
        val previous = mutableState.value
        mutableState.value = previous.copy(busy = true, error = null)
        try {
            val installed = files.installed().filter { candidate -> catalog.byId(candidate.pack.modelId) == candidate.pack }
            val savedSelection = files.savedSelection()
            val selected = savedSelection.takeIf { id -> installed.any { it.pack.modelId == id } }
            if (selected != savedSelection) files.saveSelection(null)
            var loaded = previous.loadedModelId.takeIf { it == selected && installed.any { model -> model.pack.modelId == it } }
            var loadError: UserText? = null
            if (loaded == null && previous.loadedModelId != null) llm.unload()
            if (selected != null && loaded == null) {
                val model = installed.first { it.pack.modelId == selected }
                try {
                    if (!files.verifyIntegrity(model)) failMessage(L10nKey.MODEL_INTEGRITY_FAILED)
                    llm.load(model.localPath, LlmGenerationSettings(contextLength = minOf(4096, model.pack.maximumContextLength)))
                    loaded = selected
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    runCatching { llm.unload() }
                    loadError = message(L10nKey.SELECTED_MODEL_NOT_LOADED, selected)
                }
            }
            mutableState.value = previous.copy(installed = installed, selectedModelId = selected, loadedModelId = loaded, busy = false, error = loadError)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            runCatching { llm.unload() }
            mutableState.value = previous.copy(
                busy = false,
                loadedModelId = null,
                error = error.toUserText(L10nKey.MODEL_RESTORATION_FAILED),
            )
        }
    }

    suspend fun install(pack: ModelPack, sourcePath: String, installedAtEpochSeconds: Long) = mutex.withLock {
        if (catalog.byId(pack.modelId) != pack) failMessage(L10nKey.MODEL_NOT_IN_CATALOG)
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        try {
            val model = InstalledModel(pack, pack.fileName, installedAtEpochSeconds)
            // Verified atomic replace first: a failed install keeps the loaded model and the old file.
            files.install(model, sourcePath)
            if (mutableState.value.loadedModelId == pack.modelId) {
                llm.unload()
                mutableState.value = mutableState.value.copy(loadedModelId = null)
            }
            if (mutableState.value.selectedModelId == pack.modelId) files.saveSelection(pack.modelId)
            mutableState.value = mutableState.value.copy(installed = files.installed(), busy = false)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            mutableState.value = mutableState.value.copy(
                busy = false,
                error = error.toUserText(L10nKey.MODEL_INSTALLATION_FAILED),
            )
            throw error
        }
    }

    suspend fun select(modelId: String, settings: LlmGenerationSettings = LlmGenerationSettings()) = mutex.withLock {
        val model = files.installed().firstOrNull { it.pack.modelId == modelId && catalog.byId(modelId) == it.pack }
            ?: failMessage(L10nKey.INSTALL_MODEL_FIRST)
        if (settings.contextLength > model.pack.maximumContextLength) failMessage(L10nKey.CONTEXT_EXCEEDS_MODEL_LIMIT)
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        try {
            if (!files.verifyIntegrity(model)) failMessage(L10nKey.MODEL_INTEGRITY_FAILED)
            if (mutableState.value.loadedModelId != null) {
                llm.unload()
                mutableState.value = mutableState.value.copy(loadedModelId = null)
            }
            llm.load(model.localPath, settings)
            files.saveSelection(modelId)
            mutableState.value = mutableState.value.copy(busy = false, selectedModelId = modelId, loadedModelId = modelId)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            runCatching { llm.unload() }
            mutableState.value = mutableState.value.copy(
                busy = false,
                loadedModelId = null,
                error = error.toUserText(L10nKey.MODEL_LOADING_FAILED),
            )
            throw error
        }
    }

    suspend fun unload() = mutex.withLock {
        if (mutableState.value.loadedModelId != null) llm.unload()
        mutableState.value = mutableState.value.copy(loadedModelId = null)
    }

    suspend fun delete(modelId: String) = mutex.withLock {
        val model = files.installed().firstOrNull { it.pack.modelId == modelId } ?: return@withLock
        if (mutableState.value.loadedModelId == modelId) {
            llm.unload()
            mutableState.value = mutableState.value.copy(loadedModelId = null)
        }
        if (mutableState.value.selectedModelId == modelId) files.saveSelection(null)
        files.delete(model)
        mutableState.value = mutableState.value.copy(
            installed = files.installed(),
            selectedModelId = mutableState.value.selectedModelId.takeUnless { it == modelId },
            loadedModelId = mutableState.value.loadedModelId.takeUnless { it == modelId },
        )
    }
}
