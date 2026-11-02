package app.vezuna.ui.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.vezuna.llm.ModelPack
import app.vezuna.localization.L10nKey
import app.vezuna.localization.resolve
import app.vezuna.localization.vitaText
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.ConfirmDialog
import app.vezuna.ui.components.EmptyState
import app.vezuna.ui.components.FocusedTaskScaffold
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.ProgressState
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.components.StatusKind
import app.vezuna.ui.components.StatusTag
import app.vezuna.ui.nav.RouteContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_close
import vezuna.shared.generated.resources.ic_download
import vezuna.shared.generated.resources.ic_info
import vezuna.shared.generated.resources.ic_trash
import vezuna.shared.generated.resources.ill_model_download

/**
 * Local model management over the real [app.vezuna.llm.InstalledModelManager] state. The catalog is empty by
 * default, which is a truthful "not available yet" state. Store delivery (and its confirmation dialog) is system UI.
 */
@Composable
internal fun AssistantModels(ctx: RouteContext) {
    val modelState by ctx.controller.modelManager.state.collectAsState()
    var installingId by remember { mutableStateOf<String?>(null) }
    var installJob by remember { mutableStateOf<Job?>(null) }
    var deleting by remember { mutableStateOf<ModelPack?>(null) }
    FocusedTaskScaffold(title = vitaText(L10nKey.LOCAL_GGUF_MODELS), onBack = ctx::back) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = VezunaTokens.Gutter, vertical = VezunaTokens.SpaceMd),
            verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
        ) {
            Text(vitaText(L10nKey.MODEL_SECURITY_NOTICE), style = MaterialTheme.typography.bodyLarge)
            modelState.error?.let { ListRow(vitaText(L10nKey.MODEL_ACTION_FAILED), supporting = it.resolve(), leadingIcon = Res.drawable.ic_info) }
            modelState.catalogError?.let { ListRow(it.resolve(), leadingIcon = Res.drawable.ic_info) }
            if (modelState.busy || installingId != null) ProgressState(vitaText(L10nKey.MODEL_INSTALLING))
            if (modelState.catalog.packs.isEmpty() && modelState.catalogError == null) {
                EmptyState(Res.drawable.ill_model_download, vitaText(L10nKey.NO_STORE_MODEL_PACKS), vitaText(L10nKey.NO_STORE_MODEL_PACKS_EXPLANATION))
            }
            modelState.catalog.packs.forEach { pack ->
                val installed = modelState.installed.any { it.pack.modelId == pack.modelId }
                val loaded = modelState.loadedModelId == pack.modelId
                val installing = installingId == pack.modelId
                Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(VezunaTokens.SpaceMd), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
                        Text(pack.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        StatusTag(
                            vitaText(if (loaded) L10nKey.LOADED else if (installed) L10nKey.MODEL_INSTALLED else L10nKey.MODEL_AVAILABLE),
                            if (installed) StatusKind.CONFIRMED else StatusKind.INFO,
                        )
                        Text(
                            vitaText(L10nKey.MODEL_SIZE_SPEC, pack.sizeBytes / 1_000_000, pack.minimumRamBytes / 1_000_000, pack.maximumContextLength),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        when {
                            installing -> SecondaryButton(vitaText(L10nKey.CANCEL_MODEL_INSTALL), { installJob?.cancel() }, Modifier.fillMaxWidth(), icon = Res.drawable.ic_close)
                            !installed && ctx.modelPackLoader != null -> PrimaryButton(vitaText(L10nKey.INSTALL_VERIFIED_STORE_PACK), {
                                installingId = pack.modelId
                                installJob = ctx.scope.launch {
                                    try {
                                        if (!ctx.controller.modelManager.checkFit(pack)) return@launch
                                        val source = ctx.modelPackLoader.invoke(pack) ?: return@launch
                                        try {
                                            ctx.controller.installModel(pack, source.path)
                                        } finally {
                                            source.release()
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } finally {
                                        installingId = null
                                    }
                                }
                            }, Modifier.fillMaxWidth(), enabled = !modelState.busy && installingId == null, icon = Res.drawable.ic_download)
                            !installed -> Text(vitaText(L10nKey.AWAITING_NATIVE_STORE_LOADER), style = MaterialTheme.typography.bodyMedium)
                            else -> Row(horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
                                if (!loaded) {
                                    PrimaryButton(
                                        vitaText(if (modelState.selectedModelId == pack.modelId) L10nKey.LOAD_SELECTED_MODEL else L10nKey.SWITCH_MODEL),
                                        { ctx.scope.launch { ctx.controller.modelManager.select(pack.modelId) } },
                                        Modifier.weight(1f),
                                        enabled = !modelState.busy,
                                    )
                                }
                                SecondaryButton(vitaText(L10nKey.DELETE), { deleting = pack }, Modifier.weight(1f), enabled = !modelState.busy, icon = Res.drawable.ic_trash)
                            }
                        }
                    }
                }
            }
        }
        deleting?.let { pack ->
            ConfirmDialog(
                title = vitaText(L10nKey.DELETE_MODEL_TITLE, pack.displayName),
                body = vitaText(L10nKey.DELETE_MODEL_BODY),
                confirmLabel = vitaText(L10nKey.DELETE),
                onConfirm = { deleting = null; ctx.scope.launch { ctx.controller.modelManager.delete(pack.modelId) } },
                onDismiss = { deleting = null },
            )
        }
    }
}
