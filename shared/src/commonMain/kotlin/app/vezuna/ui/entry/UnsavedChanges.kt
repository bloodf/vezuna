package app.vezuna.ui.entry

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.backhandler.BackHandler
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import app.vezuna.ui.components.ConfirmDialog

/**
 * Back for a form (049): asks before discarding only when [dirty] (real unsaved edits); otherwise goes straight
 * back. Covers the visible back button and Android system back. Returns the handler for the back button.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun guardedBack(dirty: Boolean, back: () -> Unit): () -> Unit {
    var asking by remember { mutableStateOf(false) }
    BackHandler(enabled = dirty) { asking = true }
    if (asking) {
        ConfirmDialog(
            title = vitaText(L10nKey.ENTRY_DISCARD_TITLE),
            body = vitaText(L10nKey.ENTRY_DISCARD_BODY),
            confirmLabel = vitaText(L10nKey.ENTRY_DISCARD_CONFIRM),
            onConfirm = { asking = false; back() },
            onDismiss = { asking = false },
        )
    }
    return { if (dirty) asking = true else back() }
}
