package app.vezuna

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vezuna.license.PlayLvlLicenseGate
import app.vezuna.llm.InstalledModelManager
import app.vezuna.llm.LocalHealthChat
import app.vezuna.llm.ModelCatalog
import app.vezuna.llm.AndroidDeviceCapacity
import app.vezuna.llm.PlayAssetPackLoader
import app.vezuna.llm.llamatikLocalLlm
import app.vezuna.llm.loadBundledModelCatalog
import app.vezuna.security.BackupService
import app.vezuna.security.VaultState
import app.vezuna.ui.AppController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns Android state that must survive Activity recreation while the task remains alive. */
class AndroidSessionViewModel(
    private val app: VezunaApplication,
) : ViewModel() {
    private val llm = llamatikLocalLlm()
    private val licenseGate = PlayLvlLicenseGate(app)
    private val modelManager = InstalledModelManager(ModelCatalog.unconfigured(), app.modelFiles, llm, AndroidDeviceCapacity(app))

    val modelLoader = PlayAssetPackLoader(app, licenseGate)
    val controller = AppController(
        app.repository,
        modelManager,
        LocalHealthChat(app.repository, llm),
        backup = BackupService(app.vault),
    )

    init {
        // Only after the vault is readable: initialize() may create a default profile.
        // Re-runs on every return to Ready so a vault reset or restore reloads the retained controller.
        viewModelScope.launch {
            app.vaultState.filter { it is VaultState.Ready }.collect {
                // Same bundled catalog file as iOS, read off the main thread.
                withContext(Dispatchers.IO) { modelManager.configureCatalog(::loadBundledModelCatalog) }
                controller.initialize()
            }
        }
    }

    override fun onCleared() {
        licenseGate.close()
        app.applicationScope.launch { runCatching { modelManager.unload() } }
        super.onCleared()
    }
}
