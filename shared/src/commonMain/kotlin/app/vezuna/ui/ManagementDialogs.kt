package app.vezuna.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText

@Composable
fun TextInputDialog(title: String, label: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton({ onConfirm(value) }, enabled = value.isNotBlank()) { Text(vitaText(L10nKey.SAVE)) } },
        dismissButton = { TextButton(onDismiss) { Text(vitaText(L10nKey.CANCEL)) } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onDismiss) { Text(vitaText(L10nKey.CANCEL)) } },
    )
}
