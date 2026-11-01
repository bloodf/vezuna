package app.vezuna.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import app.vezuna.security.VaultFailure
import app.vezuna.security.VaultState

/** Single entry point for both platforms: loading, recovery, or the app itself once the vault is ready. */
@Composable
fun VaultGate(
    state: VaultState,
    onRetry: () -> Unit,
    onReset: () -> Unit,
    ready: @Composable () -> Unit,
) {
    when (state) {
        VaultState.Ready -> ready()
        VaultState.Loading -> VezunaTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, androidx.compose.ui.Alignment.CenterVertically),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text(vitaText(L10nKey.LOADING_DATA))
                }
            }
        }
        is VaultState.Recovery -> VaultRecoveryScreen(state.failure, onRetry, onReset)
    }
}

@Composable
fun VaultRecoveryScreen(failure: VaultFailure, onRetry: () -> Unit, onReset: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    VezunaTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, androidx.compose.ui.Alignment.CenterVertically),
            ) {
                Text(vitaText(L10nKey.VAULT_RECOVERY_TITLE), style = MaterialTheme.typography.headlineSmall)
                Text(vitaText(failure.explanation()), style = MaterialTheme.typography.bodyLarge)
                Text(vitaText(L10nKey.VAULT_RECOVERY_KEPT_NOTE), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(vitaText(L10nKey.VAULT_RECOVERY_RETRY)) }
                OutlinedButton(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(vitaText(L10nKey.VAULT_RECOVERY_RESET))
                }
            }
        }
        if (confirming) {
            AlertDialog(
                onDismissRequest = { confirming = false },
                title = { Text(vitaText(L10nKey.VAULT_RECOVERY_RESET_CONFIRM_TITLE)) },
                text = { Text(vitaText(L10nKey.VAULT_RECOVERY_RESET_CONFIRM_BODY)) },
                confirmButton = {
                    TextButton(onClick = { confirming = false; onReset() }) { Text(vitaText(L10nKey.VAULT_RECOVERY_RESET_CONFIRM)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirming = false }) { Text(vitaText(L10nKey.VAULT_RECOVERY_CANCEL)) }
                },
            )
        }
    }
}

internal fun VaultFailure.explanation(): L10nKey = when (this) {
    VaultFailure.CORRUPT -> L10nKey.VAULT_RECOVERY_BODY_CORRUPT
    VaultFailure.KEY_INVALIDATED -> L10nKey.VAULT_RECOVERY_BODY_KEY_INVALIDATED
    VaultFailure.KEY_UNAVAILABLE -> L10nKey.VAULT_RECOVERY_BODY_KEY_UNAVAILABLE
}
