package app.vezuna.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import app.vezuna.domain.ChatMessage
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.localization.L10nKey
import app.vezuna.localization.resolve
import app.vezuna.localization.vitaText
import app.vezuna.ui.AppScreen
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.DestructiveButton
import app.vezuna.ui.components.EmptyState
import app.vezuna.ui.components.ConfirmDialog
import app.vezuna.ui.components.FocusedTaskScaffold
import app.vezuna.ui.components.GroupedRows
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.ProgressState
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.components.SectionHeading
import app.vezuna.platform.currentInstant
import app.vezuna.localization.formatUiDate
import app.vezuna.ui.nav.RouteContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_chip
import vezuna.shared.generated.resources.ic_close
import vezuna.shared.generated.resources.ic_send
import vezuna.shared.generated.resources.ill_assistant
import vezuna.shared.generated.resources.ill_model_download

/**
 * On-device assistant. Optional: records, backups and exports never depend on it. Without a model catalog the
 * truthful unavailable state is shown. Only confirmed records the user selected enter the model context.
 */
@Composable
internal fun AssistantChat(ctx: RouteContext) {
    val state = ctx.state
    val controller = ctx.controller
    val catalogState by controller.modelManager.state.collectAsState()
    FocusedTaskScaffold(title = vitaText(L10nKey.ON_DEVICE_AI), onBack = ctx::back) {
        val data = state.data
        if (data == null) {
            EmptyState(Res.drawable.ill_assistant, vitaText(L10nKey.NO_CHAT), vitaText(L10nKey.SELECT_PROFILE_FIRST), Modifier.padding(VezunaTokens.Gutter))
            return@FocusedTaskScaffold
        }
        var question by remember { mutableStateOf("") }
        var askJob by remember { mutableStateOf<Job?>(null) }
        var confirmClear by remember { mutableStateOf(false) }
        val confirmed = data.observations.filter { it.confirmation == ConfirmationStatus.CONFIRMED }
        val unavailable = catalogState.catalog.packs.isEmpty()
        val canAsk = question.isNotBlank() && ctx.loadedModelId != null && !state.loading
        val ask = { askJob = ctx.scope.launch { if (controller.ask(question)) question = "" } }

        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = VezunaTokens.Gutter, vertical = VezunaTokens.SpaceMd),
                verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
            ) {
                Text(vitaText(L10nKey.ON_DEVICE_AI_SUBTITLE), style = MaterialTheme.typography.bodyLarge)
                catalogState.catalogError?.let { ListRow(it.resolve(), leadingIcon = Res.drawable.ic_chip) }
                if (unavailable) {
                    if (catalogState.catalogError == null) {
                        EmptyState(Res.drawable.ill_model_download, vitaText(L10nKey.NO_STORE_MODEL_PACKS), vitaText(L10nKey.NO_STORE_MODEL_PACKS_EXPLANATION))
                    }
                } else {
                    GroupedRows {
                        ListRow(
                            title = when {
                                ctx.loadedModelId != null -> vitaText(L10nKey.MODEL_READY)
                                ctx.selectedModelId != null -> vitaText(L10nKey.MODEL_SELECTED_NOT_LOADED)
                                else -> vitaText(L10nKey.NO_MODEL_LOADED)
                            },
                            supporting = ctx.loadedModelId ?: ctx.selectedModelId ?: vitaText(L10nKey.CHOOSE_LOCAL_MODEL),
                            leadingIcon = Res.drawable.ic_chip,
                            onClick = { ctx.navigate(AppScreen.Models) },
                        )
                    }
                    SectionHeading(vitaText(L10nKey.RECORDS_IN_CONTEXT))
                    Text(vitaText(L10nKey.RECORDS_SELECTED, state.selectedObservationIds.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (confirmed.isEmpty()) {
                        Text(vitaText(L10nKey.NO_CONFIRMED_RECORDS_CONTEXT), style = MaterialTheme.typography.bodyLarge)
                    } else {
                        GroupedRows {
                            confirmed.forEach { observation ->
                                val selected = observation.observationId in state.selectedObservationIds
                                Row(
                                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                                        .toggleable(selected, role = Role.Checkbox, onValueChange = { controller.toggleObservation(observation.observationId) })
                                        .heightIn(min = VezunaTokens.RowHeight).padding(horizontal = VezunaTokens.SpaceMd, vertical = VezunaTokens.SpaceSm),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd),
                                ) {
                                    Checkbox(selected, onCheckedChange = null)
                                    Column(Modifier.weight(1f)) {
                                        Text(observation.displayName, style = MaterialTheme.typography.titleMedium)
                                        Text(formatUiDate(observation.collectedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
                if (data.chat.isEmpty() && !unavailable) {
                    EmptyState(Res.drawable.ill_assistant, vitaText(L10nKey.START_PRIVATE_CONVERSATION), vitaText(L10nKey.START_PRIVATE_CONVERSATION_EXPLANATION))
                }
                data.chat.forEachIndexed { index, message -> ChatBubble(message, live = index == data.chat.lastIndex && message.role == ChatMessage.Role.ASSISTANT) }
                state.streamingAnswer?.let { partial ->
                    if (partial.isEmpty()) ProgressState(vitaText(L10nKey.ON_DEVICE_AI))
                    else ChatBubble(ChatMessage(data.profile.profileId, "streaming", ChatMessage.Role.ASSISTANT, partial, currentInstant()), live = true)
                    SecondaryButton(vitaText(L10nKey.STOP_GENERATING), { askJob?.cancel() }, icon = Res.drawable.ic_close)
                }
                if (data.chat.isNotEmpty()) {
                    DestructiveButton(vitaText(L10nKey.CLEAR_CHAT_HISTORY), { confirmClear = true }, enabled = state.streamingAnswer == null && !state.loading)
                }
            }
            if (!unavailable) {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = VezunaTokens.SpaceXs) {
                    Column(Modifier.fillMaxWidth().padding(VezunaTokens.SpaceMd), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
                        if (ctx.loadedModelId == null) {
                            Text(vitaText(L10nKey.CHAT_NEEDS_MODEL), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedTextField(
                            value = question,
                            onValueChange = { question = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(vitaText(L10nKey.ASK_RECORD_EDUCATION)) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { if (canAsk) ask() }),
                        )
                        PrimaryButton(vitaText(L10nKey.ASK_ON_DEVICE_MODEL), { ask() }, Modifier.fillMaxWidth(), enabled = canAsk, icon = Res.drawable.ic_send)
                    }
                }
            }
        }
        if (confirmClear) {
            ConfirmDialog(
                title = vitaText(L10nKey.CLEAR_CHAT_CONFIRM_TITLE),
                body = vitaText(L10nKey.CLEAR_CHAT_CONFIRM_BODY),
                confirmLabel = vitaText(L10nKey.CLEAR_CHAT_HISTORY),
                onConfirm = { confirmClear = false; ctx.scope.launch { controller.clearChatHistory() } },
                onDismiss = { confirmClear = false },
            )
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, live: Boolean = false) {
    val user = message.role == ChatMessage.Role.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.88f).then(if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
            shape = MaterialTheme.shapes.large,
            color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (user) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        ) {
            Column(Modifier.padding(VezunaTokens.SpaceMd), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceXs)) {
                Text(vitaText(if (user) L10nKey.YOU else L10nKey.VEZUNA), style = MaterialTheme.typography.labelLarge)
                Text(message.content, style = MaterialTheme.typography.bodyLarge)
                if (!user) Text(vitaText(L10nKey.GENERATED_TEXT_DISCLAIMER), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
