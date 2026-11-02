package app.vezuna.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import app.vezuna.security.BackupMember
import app.vezuna.security.BackupService
import app.vezuna.security.VaultFailure
import app.vezuna.security.VaultState
import app.vezuna.ui.components.DestructiveButton
import app.vezuna.ui.components.VezunaSheet
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.components.StatusKind
import app.vezuna.ui.components.TertiaryButton
import app.vezuna.ui.security.RestoreBackupFlow
import app.vezuna.ui.security.SecurityNotice
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ill_file_error
import vezuna.shared.generated.resources.vezuna_lockup_dark

/** Restore from the recovery screen: [restore] resets the unreadable vault (quarantine only) and stores the data. */
class BackupRecovery(
    val service: BackupService,
    val files: BackupFileAccess,
    val restore: (List<BackupMember>) -> Unit,
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
        VaultState.Loading -> StartupScreen()
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

/** Image 008: the brand and a progress line only; nothing from the vault exists yet. */
@Composable
private fun StartupScreen() = VezunaTheme {
    Surface(Modifier.fillMaxSize(), color = VezunaTokens.Pine, contentColor = VezunaTokens.Light.onHeader) {
        Column(
            Modifier.fillMaxSize().padding(VezunaTokens.Gutter),
            verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceLg, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(painterResource(Res.drawable.vezuna_lockup_dark), contentDescription = vitaText(L10nKey.VEZUNA), modifier = Modifier.fillMaxWidth(0.6f))
            Row(
                Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd),
            ) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp, color = VezunaTokens.Mint)
                Text(vitaText(L10nKey.LOADING_DATA), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/**
 * Images 011/012. The reason copy is specific to [failure]. Reset (and restore, which starts from a fresh vault)
 * appear only for failures a retry cannot fix, or after [RETRIES_BEFORE_RESET] failed retries of a transient one,
 * never for a newer schema; reset only moves the unreadable files aside and always asks first.
 */
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
            if (restoring && backup != null) {
                RestoreBackupFlow(
                    service = backup.service,
                    files = backup.files,
                    profileExists = { false },
                    recovery = true,
                    onConfirmed = { opened, _ -> backup.restore(opened.members) },
                    onClose = { restoring = false },
                )
                return@Surface
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Surface(
                    color = VezunaTokens.Pine,
                    contentColor = VezunaTokens.Light.onHeader,
                    shape = RoundedCornerShape(bottomStart = VezunaTokens.SheetRadius, bottomEnd = VezunaTokens.SheetRadius),
                ) {
                    Column(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(start = VezunaTokens.Gutter, end = VezunaTokens.Gutter, top = VezunaTokens.SpaceMd, bottom = VezunaTokens.SpaceLg),
                        verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
                    ) {
                        Image(painterResource(Res.drawable.vezuna_lockup_dark), contentDescription = vitaText(L10nKey.VEZUNA), modifier = Modifier.width(140.dp))
                        Text(vitaText(L10nKey.VAULT_RECOVERY_TITLE), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    }
                }
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = VezunaTokens.Gutter, vertical = VezunaTokens.SpaceLg),
                    verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(painterResource(Res.drawable.ill_file_error), contentDescription = null, modifier = Modifier.widthIn(max = 200.dp).fillMaxWidth().heightIn(max = 150.dp))
                    Text(vitaText(failure.explanation()), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
                    SecurityNotice(vitaText(L10nKey.VAULT_RECOVERY_KEPT_NOTE), StatusKind.INFO)
                    if (restoreFailed) SecurityNotice(vitaText(L10nKey.BACKUP_RESTORE_FAILED_AFTER_RESET), StatusKind.ERROR)
                    PrimaryButton(vitaText(L10nKey.VAULT_RECOVERY_RETRY), onRetry, Modifier.fillMaxWidth())
                    // A transient failure (busy storage or an unreadable key) only offers reset after repeated failed retries.
                    val transient = failure == VaultFailure.UNAVAILABLE || failure == VaultFailure.KEY_UNAVAILABLE
                    if (failure != VaultFailure.SCHEMA_NEWER && (!transient || failedRetries >= RETRIES_BEFORE_RESET)) {
                        // Restoring starts from a fresh vault, so it is offered under the same conditions as the reset.
                        if (backup != null) SecondaryButton(vitaText(L10nKey.BACKUP_RESTORE), { restoring = true }, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(VezunaTokens.SpaceSm))
                        TertiaryButton(vitaText(L10nKey.VAULT_RECOVERY_RESET), { confirming = true }, Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (confirming) {
            // Image 012: a sheet with an explicit verb; the body says the unreadable files are kept aside.
            VezunaSheet(vitaText(L10nKey.VAULT_RECOVERY_RESET_CONFIRM_TITLE), onDismiss = { confirming = false }) {
                Text(vitaText(L10nKey.VAULT_RECOVERY_RESET_CONFIRM_BODY), style = MaterialTheme.typography.bodyLarge)
                DestructiveButton(vitaText(L10nKey.VAULT_RECOVERY_RESET_CONFIRM), { confirming = false; onReset() }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(VezunaTokens.SpaceSm))
                SecondaryButton(vitaText(L10nKey.VAULT_RECOVERY_CANCEL), { confirming = false }, Modifier.fillMaxWidth())
            }
        }
    }
}

internal fun VaultFailure.explanation(): L10nKey = when (this) {
    VaultFailure.CORRUPT -> L10nKey.VAULT_RECOVERY_BODY_CORRUPT
    VaultFailure.KEY_INVALIDATED -> L10nKey.VAULT_RECOVERY_BODY_KEY_INVALIDATED
    VaultFailure.KEY_UNAVAILABLE -> L10nKey.VAULT_RECOVERY_BODY_KEY_UNAVAILABLE
    VaultFailure.UNAVAILABLE -> L10nKey.VAULT_RECOVERY_BODY_UNAVAILABLE
    VaultFailure.SCHEMA_NEWER -> L10nKey.VAULT_RECOVERY_BODY_SCHEMA_NEWER
}

/** Image 013: one-time notice shown when the app opened from the previous generation. */
@Composable
fun PreviousGenerationNotice(visible: Boolean, onDismiss: () -> Unit) {
    if (!visible) return
    VezunaTheme {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(vitaText(L10nKey.VAULT_NOTICE_TITLE), modifier = Modifier.semantics { heading() }) },
            text = { Text(vitaText(L10nKey.VAULT_NOTICE_BODY)) },
            confirmButton = { TertiaryButton(vitaText(L10nKey.DISMISS), onDismiss) },
        )
    }
}

private const val RETRIES_BEFORE_RESET = 3
