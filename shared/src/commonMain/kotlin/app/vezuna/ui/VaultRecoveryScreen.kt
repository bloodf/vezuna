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
import app.vezuna.domain.ProfileData
import app.vezuna.domain.keepingOnlyAvailableSourceFiles
import app.vezuna.security.BackupService
import app.vezuna.security.VaultFailure
import app.vezuna.security.VaultState

/** Restore from the recovery screen: [restore] resets the unreadable vault (quarantine only) and stores the data. */
class BackupRecovery(
    val service: BackupService,
    val files: BackupFileAccess,
    val restore: (ProfileData) -> Unit,
)

/** Single entry point for both platforms: loading, recovery, or the app itself once the vault is ready. */
@Composable
fun VaultGate(
    state: VaultState,
    onRetry: () -> Unit,
    onReset: () -> Unit,
    backup: BackupRecovery? = null,
    ready: @Composable () -> Unit,
) {
    // Held here because the recovery screen leaves composition while a retry shows the loading view.
    var retries by rememberSaveable { mutableStateOf(0) }
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
        is VaultState.Recovery -> VaultRecoveryScreen(
            failure = state.failure,
            onRetry = { retries += 1; onRetry() },
            onReset = onReset,
            failedRetries = retries,
            backup = backup,
            restoreFailed = state.restoreFailed,
        )
    }
}

@Composable
fun VaultRecoveryScreen(
    failure: VaultFailure,
    onRetry: () -> Unit,
    onReset: () -> Unit,
    failedRetries: Int = 0,
    backup: BackupRecovery? = null,
    restoreFailed: Boolean = false,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    var restoring by rememberSaveable { mutableStateOf(false) }
    VezunaTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, androidx.compose.ui.Alignment.CenterVertically),
            ) {
                Text(vitaText(L10nKey.VAULT_RECOVERY_TITLE), style = MaterialTheme.typography.headlineSmall)
                Text(vitaText(failure.explanation()), style = MaterialTheme.typography.bodyLarge)
                Text(vitaText(L10nKey.VAULT_RECOVERY_KEPT_NOTE), style = MaterialTheme.typography.bodyMedium)
                if (restoreFailed) {
                    Text(vitaText(L10nKey.BACKUP_RESTORE_FAILED_AFTER_RESET), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(vitaText(L10nKey.VAULT_RECOVERY_RETRY)) }
                // A transient failure only offers the (quarantine-only, confirmed) reset after repeated failed retries.
                if (failure != VaultFailure.UNAVAILABLE || failedRetries >= RETRIES_BEFORE_RESET) {
                    OutlinedButton(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(vitaText(L10nKey.VAULT_RECOVERY_RESET))
                    }
                    // Restoring starts from a fresh vault, so it is offered under the same conditions as the reset.
                    if (backup != null) {
                        OutlinedButton(onClick = { restoring = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(vitaText(L10nKey.BACKUP_RESTORE))
                        }
                    }
                }
            }
        }
        if (restoring && backup != null) {
            RestoreBackupFlow(
                service = backup.service,
                files = backup.files,
                profileExists = { false },
                confirmBody = L10nKey.BACKUP_RECOVERY_CONFIRM_BODY,
                onConfirmed = { opened, _ -> backup.restore(opened.data.keepingOnlyAvailableSourceFiles(null)) },
                onClose = { restoring = false },
            )
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
    VaultFailure.UNAVAILABLE -> L10nKey.VAULT_RECOVERY_BODY_UNAVAILABLE
}

/** One-time notice shown when the app opened from the previous generation. */
@Composable
fun PreviousGenerationNotice(visible: Boolean, onDismiss: () -> Unit) {
    if (!visible) return
    VezunaTheme {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(vitaText(L10nKey.VAULT_NOTICE_TITLE)) },
            text = { Text(vitaText(L10nKey.VAULT_NOTICE_BODY)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(vitaText(L10nKey.DISMISS)) } },
        )
    }
}

private const val RETRIES_BEFORE_RESET = 3
