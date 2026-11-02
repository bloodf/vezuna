package app.vezuna.ui.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatLabValue
import app.vezuna.localization.vitaPlural
import app.vezuna.localization.vitaText
import app.vezuna.ui.AppScreen
import app.vezuna.ui.Category
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.CategoryPanel
import app.vezuna.ui.components.EmptyState
import app.vezuna.ui.components.FocusedTaskScaffold
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.nav.RouteContext
import kotlinx.coroutines.launch
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_history
import vezuna.shared.generated.resources.ill_file_error

/** Form body: centered and width-limited on tablets, scrolls so the primary action is always reachable. */
@Composable
private fun FormColumn(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 680.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = VezunaTokens.Gutter, vertical = VezunaTokens.SpaceMd),
            verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
            content = content,
        )
    }
}

@Composable
private fun FixFieldsNotice(visible: Boolean) {
    if (!visible) return
    Text(
        vitaText(L10nKey.ENTRY_FIX_FIELDS),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
    )
}

/** Manual entry (047 numeric, 048 text): a result typed from a paper report, saved as confirmed by its author. */
@Composable
internal fun ManualEntryScreen(ctx: RouteContext) {
    var draft by remember { mutableStateOf(ResultDraft()) }
    var showErrors by remember { mutableStateOf(false) }
    var focusTick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val errors = draft.validate(requireIdentity = true)
    val back = guardedBack(draft != ResultDraft()) { ctx.back() }
    FocusedTaskScaffold(title = vitaText(L10nKey.ENTRY_MANUAL_TITLE), onBack = back) {
        FormColumn {
            Text(vitaText(L10nKey.MANUAL_ENTRY_HINT), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ctx.state.data?.profile?.displayName?.let {
                Text(vitaText(L10nKey.ENTRY_FOR_PROFILE, it), style = MaterialTheme.typography.titleMedium)
            }
            ResultFieldSet(draft, { draft = it }, if (showErrors) errors else ResultErrors(), focusFirstError = focusTick)
            FixFieldsNotice(showErrors && !errors.isEmpty)
            PrimaryButton(
                vitaText(L10nKey.SAVE_MANUAL_RESULT),
                {
                    if (!errors.isEmpty) {
                        showErrors = true
                        focusTick++
                    } else {
                        busy = true
                        val d = draft
                        // On success the controller opens Records and announces the save; on failure the draft stays.
                        ctx.scope.launch {
                            ctx.controller.addManualResult(d.code, d.name, d.value, d.unit, d.lower, d.upper, d.rangeText, d.date, d.asText)
                            busy = false
                        }
                    }
                },
                Modifier.fillMaxWidth(),
                loading = busy,
            )
        }
    }
}

/** Correction of a confirmed result (025 numeric, 026 text, 027 validation). Saving writes a revision. */
@Composable
internal fun ResultEditScreen(ctx: RouteContext, observationId: String) {
    val observation = ctx.state.data?.observations?.firstOrNull { it.observationId == observationId && it.confirmation == ConfirmationStatus.CONFIRMED }
    if (observation == null) {
        FocusedTaskScaffold(title = vitaText(L10nKey.EDIT_RESULT), onBack = ctx::back) {
            EmptyState(Res.drawable.ill_file_error, vitaText(L10nKey.RESULT_UNAVAILABLE), vitaText(L10nKey.RESULT_UNAVAILABLE_EXPLANATION), Modifier.padding(VezunaTokens.Gutter))
        }
        return
    }
    val initial = remember(observationId) { observation.toDraft() }
    var draft by remember(observationId) { mutableStateOf(initial) }
    var showErrors by remember(observationId) { mutableStateOf(false) }
    var focusTick by remember(observationId) { mutableIntStateOf(0) }
    var busy by remember(observationId) { mutableStateOf(false) }
    val errors = draft.validate(requireIdentity = false, prefills = listOf(initial.value))
    val back = guardedBack(draft != initial) { ctx.back() }
    FocusedTaskScaffold(title = vitaText(L10nKey.EDIT_RESULT), onBack = back) {
        FormColumn {
            CategoryPanel(Category.RECORDS) {
                Text(observation.displayName, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(observation.testCode, style = MaterialTheme.typography.bodyMedium)
            }
            ResultFieldSet(
                draft, { draft = it }, if (showErrors) errors else ResultErrors(),
                editIdentity = false, editKind = false, focusFirstError = focusTick,
            )
            Text(vitaText(L10nKey.ENTRY_CORRECTION_NOTE), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FixFieldsNotice(showErrors && !errors.isEmpty)
            PrimaryButton(
                vitaText(L10nKey.ENTRY_SAVE_CORRECTION),
                {
                    if (!errors.isEmpty) {
                        showErrors = true
                        focusTick++
                    } else {
                        busy = true
                        val d = draft
                        ctx.scope.launch {
                            ctx.controller.editObservation(observationId, d.value, d.unit, d.lower, d.upper, d.rangeText, d.date)
                            busy = false
                            if (ctx.controller.state.value.error == null) ctx.back()
                        }
                    }
                },
                Modifier.fillMaxWidth(),
                loading = busy,
            )
            if (observation.revisions.isNotEmpty()) {
                Column(Modifier.fillMaxWidth()) {
                    ListRow(
                        vitaText(L10nKey.ENTRY_HISTORY_TITLE),
                        onClick = { ctx.navigate(AppScreen.ResultHistory(observationId)) },
                        supporting = vitaPlural(L10nKey.ENTRY_CORRECTIONS, observation.revisions.size),
                        leadingIcon = Res.drawable.ic_history,
                    )
                }
            }
        }
    }
}

/** Value text of one state of a result, at its recorded precision. */
internal fun stateValue(qualitative: String?, value: Double, decimals: Int?, unit: String): String =
    qualitative ?: "${formatLabValue(value, decimals)} $unit".trim()
