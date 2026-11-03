package app.vezuna.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.vezuna
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_back
import vezuna.shared.generated.resources.ic_check_circle
import vezuna.shared.generated.resources.ic_close
import vezuna.shared.generated.resources.ic_error

/**
 * Empty list with its specific reason and the next real task. Use one per reason (first use, no match,
 * nothing to review...); never a generic "nothing here".
 */
@Composable
fun EmptyState(
    illustration: DrawableResource,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = VezunaTokens.SpaceLg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd),
    ) {
        Image(painterResource(illustration), contentDescription = null, modifier = Modifier.widthIn(max = 220.dp).fillMaxWidth().heightIn(max = 165.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            PrimaryButton(actionLabel, onAction, Modifier.padding(top = VezunaTokens.SpaceSm))
        }
    }
}

/** Local work in progress, without fake percentages. Announced politely when it appears. */
@Composable
fun ProgressState(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(VezunaTokens.SpaceMd).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd),
    ) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Destructive or consequential decision. [body] names the target and what is removed; [confirmLabel] is an
 * explicit verb ("Delete report"), never "OK".
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
    confirmEnabled: Boolean = true,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, modifier = Modifier.semantics { heading() }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd)) {
                Text(body, style = MaterialTheme.typography.bodyLarge)
                extra?.invoke(this)
            }
        },
        confirmButton = {
            if (destructive) {
                DestructiveButton(confirmLabel, onConfirm, enabled = confirmEnabled)
            } else {
                PrimaryButton(confirmLabel, onConfirm, enabled = confirmEnabled)
            }
        },
        dismissButton = { TertiaryButton(vitaText(L10nKey.CANCEL), onDismiss) },
    )
}

/**
 * Put on the control that opens a dialog or sheet: when [open] turns false again, focus goes back to that
 * control instead of the top of the screen (a11y checklist item 11, A11Y_SPIKE section 3).
 */
@Composable
fun Modifier.returnFocusAfter(open: Boolean): Modifier {
    val requester = remember { FocusRequester() }
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        // The control can be gone (e.g. the item was deleted); then there is nothing to return to.
        if (wasOpen && !open) runCatching { requester.requestFocus() }
        wasOpen = open
    }
    return focusRequester(requester)
}

/** Bottom sheet with the Living Color radius and a heading. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VezunaSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = VezunaTokens.SheetRadius, topEnd = VezunaTokens.SheetRadius),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = VezunaTokens.Gutter).padding(bottom = VezunaTokens.SpaceLg).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

/**
 * Detail and form screens: a pine band with a back button and optional title, no bottom navigation. The band
 * reaches under the status bar like RootHeader. The back button is always visible (iOS has no system back
 * key); Android system back does the same through the shell. Screen-reader focus starts at the title, or at
 * the back button when there is no title.
 */
@Composable
fun FocusedTaskScaffold(
    title: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.vezuna.header, contentColor = MaterialTheme.vezuna.onHeader) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(horizontal = VezunaTokens.SpaceXs, vertical = VezunaTokens.SpaceXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(VezunaTokens.TouchTarget).then(if (title == null) Modifier.routeFocus(focusable = false) else Modifier),
                ) {
                    Icon(painterResource(Res.drawable.ic_back), contentDescription = vitaText(L10nKey.NAVIGATE_BACK))
                }
                title?.let {
                    Text(it, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).routeFocus().semantics { heading() })
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) { content() }
    }
}

/**
 * The one place global feedback appears (image 150). Notices are announced politely, errors assertively;
 * neither steals focus. A blocking error must also be shown next to its field, not only here.
 * The live-region node stays composed while empty: Compose on iOS announces a live region when its label
 * changes, not when a new node appears (see docs/redesign/A11Y_SPIKE.md).
 */
@Composable
fun FeedbackHost(notice: String?, error: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val text = error ?: notice
    val isError = error != null
    Box(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite
        },
    ) {
        if (text == null) return@Box
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = VezunaTokens.SpaceMd, vertical = VezunaTokens.SpaceSm),
            shape = RoundedCornerShape(VezunaTokens.CardRadius),
            color = if (isError) MaterialTheme.colorScheme.surface else VezunaTokens.Mint,
            contentColor = if (isError) MaterialTheme.colorScheme.error else VezunaTokens.OnCategory,
            border = if (isError) BorderStroke(2.dp, MaterialTheme.colorScheme.error) else null,
            shadowElevation = 2.dp,
        ) {
            Row(
                Modifier.padding(start = VezunaTokens.SpaceMd, end = VezunaTokens.SpaceXs, top = VezunaTokens.SpaceXs, bottom = VezunaTokens.SpaceXs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd),
            ) {
                Icon(painterResource(if (isError) Res.drawable.ic_error else Res.drawable.ic_check_circle), contentDescription = null, modifier = Modifier.size(24.dp))
                Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(vertical = VezunaTokens.SpaceSmd))
                IconButton(onClick = onDismiss, modifier = Modifier.size(VezunaTokens.TouchTarget)) {
                    Icon(painterResource(Res.drawable.ic_close), contentDescription = vitaText(L10nKey.DISMISS))
                }
            }
        }
    }
}
