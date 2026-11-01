package app.vezuna

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.vezuna.license.PlayLvlLicenseGate
import app.vezuna.llm.InstalledModelManager
import app.vezuna.llm.LocalHealthChat
import app.vezuna.llm.PlayAssetPackLoader
import app.vezuna.llm.llamatikLocalLlm
import app.vezuna.security.VaultState
import app.vezuna.ui.AppController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Owns Android state that must survive Activity recreation while the task remains alive. */
class AndroidSessionViewModel(
    private val app: VezunaApplication,
) : ViewModel() {
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val llm = llamatikLocalLlm()
    private val licenseGate = PlayLvlLicenseGate(app)

    val controller = AppController(
        app.repository,
        InstalledModelManager(app.modelCatalog, app.modelFiles, llm),
        LocalHealthChat(app.repository, llm),
    )
    val modelLoader = PlayAssetPackLoader(app, licenseGate)

    private var initializationStarted = false

    fun initialize() {
        if (initializationStarted) return
        initializationStarted = true
        sessionScope.launch {
            app.vaultState.first { it is VaultState.Ready }
            controller.initialize()
        }
    }

    override fun onCleared() {
        sessionScope.cancel()
        runCatching { runBlocking(Dispatchers.IO) { controller.modelManager.unload() } }
        licenseGate.close()
        super.onCleared()
    }

    class Factory(private val app: VezunaApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(AndroidSessionViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            return AndroidSessionViewModel(app) as T
        }
    }
}
