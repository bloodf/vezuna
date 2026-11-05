package app.vezuna.llm

import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.localization.failMessage
import app.vezuna.localization.message
import app.vezuna.localization.toUserText
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The store that delivers a model asset. Android ships Play asset packs; iOS ships store asset packs. */
enum class ModelPlatform { ANDROID, IOS }

@Serializable
data class ModelPack(
    val modelId: String,
    val displayName: String,
    val fileName: String,
    // A pack may be offered on one platform only; the missing side stays null instead of naming a pack that does not exist.
    val androidAssetPackId: String? = null,
    val iosAssetPackId: String? = null,
    val sizeBytes: Long,
    val minimumRamBytes: Long,
    val maximumContextLength: Int,
    val sha256: String,
) {
    init {
        require(modelId.isNotBlank() && displayName.isNotBlank())
        require(fileName.endsWith(".gguf") && !fileName.contains('/') && !fileName.contains('\\'))
        // A named pack must be real, and at least one platform must deliver this model.
        require(androidAssetPackId == null || androidAssetPackId.isNotBlank())
        require(iosAssetPackId == null || iosAssetPackId.isNotBlank())
        require(androidAssetPackId != null || iosAssetPackId != null)
        require(sizeBytes > 0 && minimumRamBytes > 0 && maximumContextLength > 0)
        if (!SHA256.matches(sha256)) failMessage(L10nKey.MODEL_SHA_REQUIRED)
    }

    /** The store pack id that delivers this model on [platform], or null when the model is not offered there. */
    fun deliveryIdFor(platform: ModelPlatform): String? = when (platform) {
        ModelPlatform.ANDROID -> androidAssetPackId
        ModelPlatform.IOS -> iosAssetPackId
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

    /** The offers [platform] can actually deliver; packs meant for the other platform are dropped. */
    fun forPlatform(platform: ModelPlatform): ModelCatalog =
        ModelCatalog(packs.filter { it.deliveryIdFor(platform) != null })

    companion object {
        // Store shells provide signed pack metadata after configuring their own asset packs.
        fun unconfigured(): ModelCatalog = ModelCatalog(emptyList())

        private val json = Json { ignoreUnknownKeys = false }

        /**
         * Parses `composeResources/files/model-packs.json`, the one catalog source for Android and iOS. Throws on invalid content.
         * Every entry is validated here, before any platform filtering, so a corrupt or duplicated pack is rejected even when
         * it belongs to the other platform.
         */
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

sealed interface SavedModelSelection {
    data object Unknown : SavedModelSelection
    data class Known(val modelId: String?) : SavedModelSelection
}

data class ModelManagerState(
    val installed: List<InstalledModel> = emptyList(),
    val selectedModelId: String? = null,
    val selection: SavedModelSelection = SavedModelSelection.Unknown,
    val metadataNeedsRefresh: Boolean = false,
    val loadedModelId: String? = null,
    val busy: Boolean = false,
    val error: UserText? = null,
    val catalog: ModelCatalog = ModelCatalog.unconfigured(),
    val catalogError: UserText? = null,
)

class InstalledModelManager(
    initialCatalog: ModelCatalog,
    private val files: ModelFileStore,
    private val llm: LocalLlm,
    private val capacity: DeviceCapacity = UnknownDeviceCapacity,
) {
    private val mutex = Mutex()
    private val managed = llm as? ManagedLlmLifecycle
    private var loadedLease: EngineLease? = null
    var catalog: ModelCatalog = initialCatalog
        private set
    private val mutableState = MutableStateFlow(ModelManagerState(catalog = initialCatalog))
    val state: StateFlow<ModelManagerState> = mutableState.asStateFlow()
    internal val ownerStatus: StateFlow<EngineOwnerStatus>? get() = managed?.ownerStatus

    internal fun readyModelId(status: EngineOwnerStatus? = ownerStatus?.value): String? =
        if (managed == null || status is EngineOwnerStatus.Current &&
            status.readiness == EngineReadiness.KnownLoaded && status.lease === loadedLease)
            mutableState.value.loadedModelId else null

    private fun checkSession() {
        if (ownerStatus?.value == EngineOwnerStatus.Superseded) sessionSuperseded()
    }
    private fun selection(value: SavedModelSelection) {
        mutableState.value = mutableState.value.copy(selection = value, selectedModelId = (value as? SavedModelSelection.Known)?.modelId)
    }
    private suspend fun <T> external(key: L10nKey, action: suspend () -> T): T {
        checkSession()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        return try { action() }
        catch (canceled: kotlinx.coroutines.CancellationException) { throw canceled }
        catch (expected: ModelOperationFailure) { throw expected }
        catch (failure: Exception) { throw ModelOperationFailure(failure.toUserText(key), failure) }
    }
    private suspend fun saveSelection(id: String?) {
        checkSession()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        selection(SavedModelSelection.Unknown)
        external(L10nKey.MODEL_ACTION_FAILED) { files.saveSelection(id) }
        selection(SavedModelSelection.Known(id))
    }
    private suspend fun readInstalled(): List<InstalledModel> {
        val installed = external(L10nKey.MODEL_RESTORATION_FAILED) { files.installed() }
            .filter { catalog.byId(it.pack.modelId) == it.pack }
        mutableState.value = mutableState.value.copy(installed = installed, metadataNeedsRefresh = false)
        return installed
    }
    private suspend fun readSelection(): String? {
        selection(SavedModelSelection.Unknown)
        val observed = external(L10nKey.MODEL_RESTORATION_FAILED) { files.savedSelection() }
        selection(SavedModelSelection.Known(observed))
        return observed
    }
    private suspend fun lifecycle(command: LifecycleCommand, modelId: String? = null) {
        if (command != LifecycleCommand.Shutdown) checkSession()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val capability = managed
        if (capability != null) {
            val request = LifecycleRequest(command, (capability.ownerStatus.value as? EngineOwnerStatus.Current)?.lease)
            try { capability.applyLifecycle(request) }
            finally {
                // Historical receipts never write the capability's live owner status.
                when (request.receipt.disposition) {
                    LifecycleDisposition.LoadAcknowledged -> {
                        loadedLease = request.receipt.lease
                        mutableState.value = mutableState.value.copy(loadedModelId = modelId)
                    }
                    LifecycleDisposition.ShutdownAcknowledged, LifecycleDisposition.Uncertain -> mutableState.value = mutableState.value.copy(loadedModelId = null)
                    else -> Unit
                }
            }
        } else {
            when (command) {
                LifecycleCommand.Shutdown -> if (mutableState.value.loadedModelId != null) {
                    external(L10nKey.MODEL_LOADING_FAILED) { llm.unload() }
                    mutableState.value = mutableState.value.copy(loadedModelId = null)
                }
                is LifecycleCommand.Replace -> {
                    if (mutableState.value.loadedModelId != null) {
                        external(L10nKey.MODEL_LOADING_FAILED) { llm.unload() }
                        mutableState.value = mutableState.value.copy(loadedModelId = null)
                    }
                    external(L10nKey.MODEL_LOADING_FAILED) { llm.load(command.path, command.settings) }
                    mutableState.value = mutableState.value.copy(loadedModelId = modelId)
                }
            }
        }
    }
    private suspend fun legacyCleanup() {
        if (managed == null) {
            try { llm.unload() }
            catch (canceled: kotlinx.coroutines.CancellationException) { throw canceled }
            catch (_: Exception) { /* Existing best-effort legacy cleanup. */ }
            mutableState.value = mutableState.value.copy(loadedModelId = null)
        }
    }
    private suspend fun operation(absorb: Boolean = false, rejectSuperseded: Boolean = true, action: suspend () -> Unit) = mutex.withLock {
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        try {
            if (rejectSuperseded) checkSession()
            action()
        } catch (canceled: kotlinx.coroutines.CancellationException) {
            throw canceled
        } catch (failure: ModelOperationFailure) {
            mutableState.value = mutableState.value.copy(error = failure.userText)
            if (!absorb) throw failure
        } catch (failure: app.vezuna.localization.VitaMessageException) {
            mutableState.value = mutableState.value.copy(error = failure.userText)
            if (!absorb) throw ModelOperationFailure(failure.userText, failure)
        } finally {
            mutableState.value = mutableState.value.copy(busy = false)
        }
    }

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

    suspend fun refresh() = operation(absorb = true) {
        try {
            val installed = readInstalled()
            val observed = readSelection()
            val selected = observed.takeIf { id -> installed.any { it.pack.modelId == id } }
            if (selected != observed) saveSelection(null)
            if (selected == null) {
                lifecycle(LifecycleCommand.Shutdown)
            } else if (readyModelId() != selected) {
                val model = installed.first { it.pack.modelId == selected }
                try {
                    if (!external(L10nKey.MODEL_INTEGRITY_FAILED) { files.verifyIntegrity(model) }) failMessage(L10nKey.MODEL_INTEGRITY_FAILED)
                    lifecycle(LifecycleCommand.Replace(model.localPath, LlmGenerationSettings(contextLength = minOf(4096, model.pack.maximumContextLength))), selected)
                } catch (canceled: kotlinx.coroutines.CancellationException) { throw canceled }
                catch (failure: ModelOperationFailure) {
                    throw ModelOperationFailure(message(L10nKey.SELECTED_MODEL_NOT_LOADED, selected), failure)
                } catch (failure: app.vezuna.localization.VitaMessageException) {
                    throw ModelOperationFailure(message(L10nKey.SELECTED_MODEL_NOT_LOADED, selected), failure)
                }
            }
        } catch (canceled: kotlinx.coroutines.CancellationException) { throw canceled }
        catch (failure: ModelOperationFailure) { legacyCleanup(); throw failure }
        catch (failure: app.vezuna.localization.VitaMessageException) { legacyCleanup(); throw failure }
    }

    /** Runs before the pack is downloaded; on failure the reason is put in [ModelManagerState.error] and false is returned. */
    fun checkFit(pack: ModelPack): Boolean = try {
        ModelFitPolicy.requireFits(pack, capacity)
        true
    } catch (error: app.vezuna.localization.VitaMessageException) {
        mutableState.value = mutableState.value.copy(error = error.userText)
        false
    }

    suspend fun install(pack: ModelPack, sourcePath: String, installedAtEpochSeconds: Long) = operation() {
        if (catalog.byId(pack.modelId) != pack) failMessage(L10nKey.MODEL_NOT_IN_CATALOG)
        ModelFitPolicy.requireFits(pack, capacity, delivered = true)
        val model = InstalledModel(pack, pack.fileName, installedAtEpochSeconds)
        val replacingLoaded = mutableState.value.loadedModelId == pack.modelId
        if (managed != null && (replacingLoaded || (ownerStatus?.value as? EngineOwnerStatus.Current)?.readiness == EngineReadiness.Uncertain)) lifecycle(LifecycleCommand.Shutdown)
        checkSession()
        mutableState.value = mutableState.value.copy(metadataNeedsRefresh = true)
        external(L10nKey.MODEL_INSTALLATION_FAILED) { files.install(model, sourcePath) }
        if (managed == null && replacingLoaded) lifecycle(LifecycleCommand.Shutdown)
        if ((mutableState.value.selection as? SavedModelSelection.Known)?.modelId == pack.modelId) saveSelection(pack.modelId)
        readInstalled()
    }

    suspend fun select(modelId: String, settings: LlmGenerationSettings = LlmGenerationSettings()) = operation() {
        val model = readInstalled().firstOrNull { it.pack.modelId == modelId } ?: failMessage(L10nKey.INSTALL_MODEL_FIRST)
        if (settings.contextLength > model.pack.maximumContextLength) failMessage(L10nKey.CONTEXT_EXCEEDS_MODEL_LIMIT)
        try {
            if (!external(L10nKey.MODEL_INTEGRITY_FAILED) { files.verifyIntegrity(model) }) failMessage(L10nKey.MODEL_INTEGRITY_FAILED)
            lifecycle(LifecycleCommand.Replace(model.localPath, settings), modelId)
            saveSelection(modelId)
        } catch (canceled: kotlinx.coroutines.CancellationException) { throw canceled }
        catch (failure: ModelOperationFailure) { legacyCleanup(); throw failure }
        catch (failure: app.vezuna.localization.VitaMessageException) { legacyCleanup(); throw failure }
    }

    suspend fun unload() = operation(rejectSuperseded = false) { lifecycle(LifecycleCommand.Shutdown) }

    suspend fun delete(modelId: String) = operation() {
        val model = readInstalled().firstOrNull { it.pack.modelId == modelId } ?: return@operation
        if (mutableState.value.selection == SavedModelSelection.Unknown) readSelection()
        if (mutableState.value.loadedModelId == modelId || (ownerStatus?.value as? EngineOwnerStatus.Current)?.readiness == EngineReadiness.Uncertain) lifecycle(LifecycleCommand.Shutdown)
        if (mutableState.value.selectedModelId == modelId) saveSelection(null)
        checkSession()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val before = mutableState.value.selection
        selection(SavedModelSelection.Unknown)
        mutableState.value = mutableState.value.copy(metadataNeedsRefresh = true)
        external(L10nKey.MODEL_ACTION_FAILED) { files.delete(model) }
        if (before is SavedModelSelection.Known) selection(SavedModelSelection.Known(before.modelId.takeUnless { it == modelId }))
        readInstalled()
    }
}
