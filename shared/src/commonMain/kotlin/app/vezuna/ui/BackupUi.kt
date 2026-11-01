package app.vezuna.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.vezuna.domain.ProfileData
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.localization.resolve
import app.vezuna.localization.toUserText
import app.vezuna.localization.vitaText
import app.vezuna.platform.currentInstant
import app.vezuna.security.BackupKind
import app.vezuna.security.BackupService
import app.vezuna.security.OpenedBackup
import app.vezuna.security.PassphraseStrength
import app.vezuna.security.PassphraseStrengthLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Native file access, supplied by each platform shell (SAF on Android, document picker and share sheet on iOS). */
class BackupFileAccess(
    /** Bytes of the chosen file, or null when the user cancelled. */
    val pick: suspend () -> ByteArray?,
    /** True when the file was saved or shared, false when the user cancelled. */
    val save: suspend (fileName: String, bytes: ByteArray) -> Boolean,
)

/** Backup and restore entry points shown in Settings. */
@Composable
fun BackupSettingsSection(state: AppUiState, controller: AppController, files: BackupFileAccess?) {
    val service = controller.backup
    if (service == null || files == null) return
    var creating by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    SectionHeading(vitaText(L10nKey.BACKUP_SECTION_TITLE), vitaText(L10nKey.STORAGE_BACKUP_DESCRIPTION))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        VitaButton(vitaText(L10nKey.BACKUP_CREATE), Icons.Rounded.Lock, { creating = true }, Modifier.weight(1f), enabled = state.data != null)
        VitaButton(vitaText(L10nKey.BACKUP_RESTORE), Icons.Rounded.FileDownload, { restoring = true }, Modifier.weight(1f), outlined = true)
    }
    val data = state.data
    if (creating && data != null) {
        CreateBackupDialog(service, data, files, onClose = { creating = false }, onSaved = { controller.announce(UserText.Key(L10nKey.BACKUP_SAVED)) })
    }
    if (restoring) {
        RestoreBackupFlow(
            service = service,
            files = files,
            profileExists = { id -> state.profiles.any { it.profileId == id } },
            confirmBody = null,
            onConfirmed = { opened, mode -> controller.restoreBackup(opened.data, mode) },
            onClose = { restoring = false },
        )
    }
}

@Composable
private fun PassphraseField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CreateBackupDialog(service: BackupService, data: ProfileData, files: BackupFileAccess, onClose: () -> Unit, onSaved: () -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<UserText?>(null) }
    val scope = rememberCoroutineScope()
    val level = PassphraseStrength.evaluate(passphrase.toCharArray())
    val matches = passphrase == confirmation
    val ready = level != PassphraseStrengthLevel.TOO_SHORT && matches && !busy
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(vitaText(L10nKey.BACKUP_CREATE)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InlineNotice(vitaText(L10nKey.BACKUP_PASSPHRASE_WARNING), Icons.Rounded.Lock)
                PassphraseField(vitaText(L10nKey.BACKUP_PASSPHRASE_LABEL), passphrase, { passphrase = it; error = null }, !busy)
                if (passphrase.isNotEmpty()) {
                    Text(
                        vitaText(
                            when (level) {
                                PassphraseStrengthLevel.TOO_SHORT -> L10nKey.BACKUP_STRENGTH_TOO_SHORT
                                PassphraseStrengthLevel.FAIR -> L10nKey.BACKUP_STRENGTH_FAIR
                                PassphraseStrengthLevel.STRONG -> L10nKey.BACKUP_STRENGTH_STRONG
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (level == PassphraseStrengthLevel.TOO_SHORT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PassphraseField(vitaText(L10nKey.BACKUP_PASSPHRASE_CONFIRM_LABEL), confirmation, { confirmation = it; error = null }, !busy)
                if (confirmation.isNotEmpty() && !matches) {
                    Text(vitaText(L10nKey.BACKUP_PASSPHRASE_MISMATCH), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (busy) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 3.dp)
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = {
                busy = true
                val chars = passphrase.toCharArray()
                scope.launch {
                    try {
                        val bytes = service.create(data, chars)
                        val saved = files.save(backupFileName(data), bytes)
                        busy = false
                        if (saved) {
                            onSaved()
                            onClose()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        busy = false
                        error = failure.toUserText()
                    } finally {
                        chars.fill('\u0000')
                    }
                }
            }) { Text(vitaText(L10nKey.BACKUP_CREATE_ACTION)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text(vitaText(L10nKey.CANCEL)) } },
    )
}

private sealed interface RestoreStep {
    data object Picking : RestoreStep
    data object Failed : RestoreStep
    data class Passphrase(val file: ByteArray) : RestoreStep
    data class Decide(val opened: OpenedBackup) : RestoreStep
}

/**
 * File, passphrase and decision steps shared by Settings and the vault recovery screen. [profileExists] decides
 * whether the replace-or-new choice is offered; [confirmBody] replaces the default confirmation text.
 */
@Composable
fun RestoreBackupFlow(
    service: BackupService,
    files: BackupFileAccess,
    profileExists: (String) -> Boolean,
    confirmBody: L10nKey?,
    onConfirmed: suspend (OpenedBackup, RestoreMode) -> Unit,
    onClose: () -> Unit,
) {
    var step by remember { mutableStateOf<RestoreStep>(RestoreStep.Picking) }
    var passphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<UserText?>(null) }
    val scope = rememberCoroutineScope()

    fun open(file: ByteArray, secret: CharArray?) {
        busy = true
        scope.launch {
            try {
                step = RestoreStep.Decide(service.open(file, secret))
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                error = failure.toUserText()
                if (step !is RestoreStep.Passphrase) step = RestoreStep.Failed
            } finally {
                busy = false
                secret?.fill('\u0000')
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            val file = files.pick()
            if (file == null) {
                onClose()
            } else when (service.kind(file)) {
                BackupKind.PORTABLE -> step = RestoreStep.Passphrase(file)
                BackupKind.SAME_DEVICE -> open(file, null)
                BackupKind.UNKNOWN -> { error = UserText.Key(L10nKey.BACKUP_NOT_A_BACKUP); step = RestoreStep.Failed }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            error = failure.toUserText()
            step = RestoreStep.Failed
        }
    }

    when (val current = step) {
        RestoreStep.Picking -> if (busy) ErrorOrBusyDialog(null, true, onClose)
        RestoreStep.Failed -> ErrorOrBusyDialog(error, false, onClose)
        is RestoreStep.Passphrase -> {
            AlertDialog(
                onDismissRequest = { if (!busy) onClose() },
                title = { Text(vitaText(L10nKey.BACKUP_ENTER_PASSPHRASE_TITLE)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PassphraseField(vitaText(L10nKey.BACKUP_PASSPHRASE_LABEL), passphrase, { passphrase = it; error = null }, !busy)
                        error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        if (busy) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 3.dp)
                    }
                },
                confirmButton = {
                    TextButton(enabled = passphrase.isNotEmpty() && !busy, onClick = { open(current.file, passphrase.toCharArray()) }) {
                        Text(vitaText(L10nKey.BACKUP_UNLOCK))
                    }
                },
                dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text(vitaText(L10nKey.CANCEL)) } },
            )
        }
        is RestoreStep.Decide -> {
            val opened = current.opened
            val name = opened.data.profile.displayName
            val exists = profileExists(opened.data.profile.profileId)
            val finish: (RestoreMode) -> Unit = { mode ->
                busy = true
                scope.launch {
                    try {
                        onConfirmed(opened, mode)
                    } finally {
                        busy = false
                        onClose()
                    }
                }
            }
            AlertDialog(
                onDismissRequest = { if (!busy) onClose() },
                title = { Text(vitaText(if (exists) L10nKey.BACKUP_PROFILE_EXISTS_TITLE else L10nKey.BACKUP_CONFIRM_TITLE)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(vitaText(L10nKey.BACKUP_CONTENTS, name), style = MaterialTheme.typography.titleSmall)
                        if (opened.kind == BackupKind.SAME_DEVICE) Text(vitaText(L10nKey.BACKUP_SAME_DEVICE_LABEL), style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (exists) vitaText(L10nKey.BACKUP_PROFILE_EXISTS_BODY, name) else vitaText(confirmBody ?: L10nKey.BACKUP_CONFIRM_BODY, name),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (busy) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 3.dp)
                    }
                },
                confirmButton = {
                    if (exists) {
                        Column {
                            TextButton(enabled = !busy, onClick = { finish(RestoreMode.RESTORE_AS_NEW) }) { Text(vitaText(L10nKey.BACKUP_RESTORE_AS_NEW)) }
                            TextButton(enabled = !busy, onClick = { finish(RestoreMode.REPLACE_EXISTING) }) { Text(vitaText(L10nKey.BACKUP_REPLACE)) }
                        }
                    } else {
                        TextButton(enabled = !busy, onClick = { finish(RestoreMode.REPLACE_EXISTING) }) { Text(vitaText(L10nKey.BACKUP_RESTORE_ACTION)) }
                    }
                },
                dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text(vitaText(L10nKey.CANCEL)) } },
            )
        }
    }
}

@Composable
private fun ErrorOrBusyDialog(error: UserText?, busy: Boolean, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(vitaText(L10nKey.BACKUP_RESTORE)) },
        text = {
            if (busy) CircularProgressIndicator(strokeWidth = 3.dp)
            else Text(error?.resolve().orEmpty(), color = MaterialTheme.colorScheme.error)
        },
        confirmButton = { TextButton(onClick = onClose) { Text(vitaText(L10nKey.DISMISS)) } },
    )
}

internal fun backupFileName(data: ProfileData): String {
    val safe = data.profile.displayName.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("").take(40).ifBlank { "profile" }
    val date = currentInstant().toLocalDateTime(TimeZone.currentSystemDefault()).date
    return "vezuna-$safe-$date.${BackupService.FILE_EXTENSION}"
}
