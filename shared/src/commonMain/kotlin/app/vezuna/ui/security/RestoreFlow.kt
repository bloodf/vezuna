package app.vezuna.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.localization.resolve
import app.vezuna.localization.toUserText
import app.vezuna.localization.vitaText
import app.vezuna.security.BackupKind
import app.vezuna.security.BackupService
import app.vezuna.security.OpenedBackup
import app.vezuna.ui.BackupFileAccess
import app.vezuna.ui.Category
import app.vezuna.ui.RestoreMode
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.ConfirmDialog
import app.vezuna.ui.components.EmptyState
import app.vezuna.ui.components.FocusedTaskScaffold
import app.vezuna.ui.components.GroupedRows
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.ProfileAvatar
import app.vezuna.ui.components.ProgressState
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.components.StatusKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_calendar
import vezuna.shared.generated.resources.ic_capsule
import vezuna.shared.generated.resources.ic_chat
import vezuna.shared.generated.resources.ic_file_text
import vezuna.shared.generated.resources.ic_lab
import vezuna.shared.generated.resources.ic_photo
import vezuna.shared.generated.resources.ic_pulse
import vezuna.shared.generated.resources.ic_records
import vezuna.shared.generated.resources.ill_file_error
import vezuna.shared.generated.resources.ill_restore

private sealed interface RestoreStep {
    data object Picking : RestoreStep
    data class Failed(val error: UserText) : RestoreStep
    class Passphrase(val file: ByteArray) : RestoreStep
    class Decide(val opened: OpenedBackup) : RestoreStep
    data object Restoring : RestoreStep
}

/**
 * Pick, unlock, preview and confirm a backup, shared by Backups and the vault recovery screen ([recovery]).
 * Nothing on the device changes until [onConfirmed] runs; the caller performs the restore and leaves this flow
 * (the restore switches profile or rebuilds the vault). An existing profile is replaced only after a second,
 * explicit confirmation; restoring as a new profile is the preselected choice.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RestoreBackupFlow(
    service: BackupService,
    files: BackupFileAccess,
    profileExists: (String) -> Boolean,
    recovery: Boolean,
    onConfirmed: (OpenedBackup, RestoreMode) -> Unit,
    onClose: () -> Unit,
) {
    var step by remember { mutableStateOf<RestoreStep>(RestoreStep.Picking) }
    var picks by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val busy = step == RestoreStep.Restoring
    BackHandler(enabled = !busy, onBack = onClose)

    LaunchedEffect(picks) {
        step = RestoreStep.Picking
        step = try {
            val file = files.pick()
            when {
                file == null -> { onClose(); return@LaunchedEffect }
                file.isEmpty() || file.size > BackupService.MAX_BACKUP_READ_BYTES -> RestoreStep.Failed(UserText.Key(L10nKey.BACKUP_FILE_UNREADABLE))
                else -> when (service.kind(file)) {
                    BackupKind.PORTABLE -> RestoreStep.Passphrase(file)
                    BackupKind.SAME_DEVICE -> RestoreStep.Decide(service.open(file, null))
                    BackupKind.UNKNOWN -> RestoreStep.Failed(UserText.Key(L10nKey.BACKUP_NOT_A_BACKUP))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            RestoreStep.Failed(failure.toUserText())
        }
    }

    FocusedTaskScaffold(title = vitaText(L10nKey.BACKUP_RESTORE), onBack = { if (!busy) onClose() }) {
        when (val current = step) {
            RestoreStep.Picking -> SecurityPage { ProgressState(vitaText(L10nKey.BACKUP_CHOOSE_FILE_WAITING)) }
            RestoreStep.Restoring -> SecurityPage {
                SecurityHero(Res.drawable.ill_restore, vitaText(L10nKey.BACKUP_RESTORING), vitaText(L10nKey.BACKUP_RESTORING_BODY), live = true)
                ProgressState(vitaText(L10nKey.PLEASE_WAIT))
            }
            is RestoreStep.Failed -> SecurityPage {
                EmptyState(
                    Res.drawable.ill_file_error,
                    title = vitaText(L10nKey.BACKUP_OPEN_FAILED_TITLE),
                    body = current.error.resolve(),
                    actionLabel = vitaText(L10nKey.BACKUP_CHOOSE_ANOTHER),
                    onAction = { picks++ },
                )
                SecondaryButton(vitaText(L10nKey.CANCEL), onClose, Modifier.fillMaxWidth())
            }
            is RestoreStep.Passphrase -> PassphrasePage(current.file, service, onOpened = { step = RestoreStep.Decide(it) }, onFailed = { step = RestoreStep.Failed(it) }, onPickAgain = { picks++ })
            is RestoreStep.Decide -> PreviewPage(current.opened, profileExists, recovery) { mode ->
                step = RestoreStep.Restoring
                scope.launch { onConfirmed(current.opened, mode) }
            }
        }
    }
}

@Composable
private fun PassphrasePage(file: ByteArray, service: BackupService, onOpened: (OpenedBackup) -> Unit, onFailed: (UserText) -> Unit, onPickAgain: () -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<UserText?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SecurityPage {
        Text(vitaText(L10nKey.BACKUP_RESTORE_SUBTITLE), style = MaterialTheme.typography.bodyLarge)
        ListRow(
            title = vitaText(L10nKey.BACKUP_FILE_SELECTED),
            supporting = fileSizeText(file.size),
            leadingIcon = Res.drawable.ic_file_text,
            category = Category.RECORDS,
        )
        PassphraseField(
            vitaText(L10nKey.BACKUP_PASSPHRASE_LABEL), passphrase, { passphrase = it; error = null }, enabled = !busy, newPassword = false,
            error = error?.resolve(),
        )
        PrimaryButton(vitaText(L10nKey.BACKUP_UNLOCK), enabled = passphrase.isNotEmpty(), loading = busy, modifier = Modifier.fillMaxWidth(), onClick = {
            busy = true
            val secret = passphrase.toCharArray()
            scope.launch {
                try {
                    val opened = service.open(file, secret)
                    passphrase = ""
                    onOpened(opened)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    val text = failure.toUserText()
                    // A wrong passphrase stays here with the input kept; damaged or newer files get their own page.
                    if (text is UserText.Key && text.key == L10nKey.BACKUP_WRONG_PASSPHRASE) error = text else onFailed(text)
                } finally {
                    busy = false
                    secret.fill('\u0000')
                }
            }
        })
        SecondaryButton(vitaText(L10nKey.BACKUP_CHOOSE_ANOTHER), onPickAgain, Modifier.fillMaxWidth(), enabled = !busy)
    }
}

@Composable
private fun PreviewPage(opened: OpenedBackup, profileExists: (String) -> Boolean, recovery: Boolean, onRestore: (RestoreMode) -> Unit) {
    val names = opened.members.joinToString(", ") { it.data.profile.displayName }
    val existing = opened.members.filter { profileExists(it.data.profile.profileId) }.joinToString(", ") { it.data.profile.displayName }
    val contents = remember(opened) { BackupContents.of(opened.members) }
    var mode by remember { mutableStateOf(RestoreMode.RESTORE_AS_NEW) }
    var confirmingReplace by remember { mutableStateOf(false) }
    SecurityPage {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd)) {
            ProfileAvatar(opened.data.profile.displayName, size = 56)
            Column(Modifier.weight(1f)) {
                Text(
                    if (opened.members.size > 1) vitaText(L10nKey.BACKUP_HOUSEHOLD_CONTENTS, opened.members.size, names) else names,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    vitaText(if (opened.kind == BackupKind.SAME_DEVICE) L10nKey.BACKUP_SAME_DEVICE_LABEL else L10nKey.BACKUP_KIND_PORTABLE),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ContentsRows(contents)
        if (contents.documents > 0 && contents.originalFiles < contents.documents) {
            SecurityNotice(vitaText(L10nKey.BACKUP_ORIGINALS_MISSING_TITLE), StatusKind.INFO, detail = vitaText(L10nKey.BACKUP_ORIGINALS_MISSING_BODY))
        }
        if (recovery) SecurityNotice(vitaText(L10nKey.BACKUP_RECOVERY_CONFIRM_BODY), StatusKind.INFO)
        if (existing.isNotEmpty() && !recovery) {
            Text(vitaText(L10nKey.BACKUP_ALREADY_EXISTS, existing), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = VezunaTokens.SpaceSm))
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
                ModeOption(RestoreMode.RESTORE_AS_NEW, mode, vitaText(L10nKey.BACKUP_RESTORE_AS_NEW), vitaText(L10nKey.BACKUP_RESTORE_AS_NEW_BODY)) { mode = it }
                ModeOption(RestoreMode.REPLACE_EXISTING, mode, vitaText(L10nKey.BACKUP_REPLACE), vitaText(L10nKey.BACKUP_REPLACE_BODY, existing)) { mode = it }
            }
            PrimaryButton(vitaText(L10nKey.CONTINUE_ACTION), modifier = Modifier.fillMaxWidth(), onClick = {
                if (mode == RestoreMode.REPLACE_EXISTING) confirmingReplace = true else onRestore(RestoreMode.RESTORE_AS_NEW)
            })
        } else {
            if (!recovery) SecurityNotice(vitaText(L10nKey.BACKUP_CONFIRM_BODY, names), StatusKind.INFO)
            PrimaryButton(vitaText(L10nKey.BACKUP_RESTORE_ACTION), modifier = Modifier.fillMaxWidth(), onClick = { onRestore(RestoreMode.REPLACE_EXISTING) })
        }
    }
    if (confirmingReplace) {
        ConfirmDialog(
            title = vitaText(L10nKey.BACKUP_PROFILE_EXISTS_TITLE),
            body = vitaText(L10nKey.BACKUP_PROFILE_EXISTS_BODY, existing),
            confirmLabel = vitaText(L10nKey.BACKUP_REPLACE),
            onConfirm = { confirmingReplace = false; onRestore(RestoreMode.REPLACE_EXISTING) },
            onDismiss = { confirmingReplace = false },
        )
    }
}

@Composable
private fun ModeOption(value: RestoreMode, selected: RestoreMode, title: String, body: String, onSelect: (RestoreMode) -> Unit) {
    GroupedRows {
        ListRow(
            title = title,
            supporting = body,
            showChevron = false,
            leading = { RadioButton(selected = value == selected, onClick = null) },
            modifier = Modifier.heightIn(min = 64.dp).selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) }),
        )
    }
}

@Composable
internal fun ContentsRows(contents: BackupContents) {
    GroupedRows(title = vitaText(L10nKey.BACKUP_INCLUDED)) {
        ListRow(vitaText(L10nKey.BACKUP_ITEM_REPORTS), supporting = vitaText(L10nKey.BACKUP_ITEM_REPORTS_COUNT, contents.reports, contents.resultsToReview), leadingIcon = Res.drawable.ic_records, category = Category.RECORDS)
        ListRow(vitaText(L10nKey.BACKUP_ITEM_RESULTS), supporting = contents.confirmedResults.toString(), leadingIcon = Res.drawable.ic_lab, category = Category.TRENDS)
        ListRow(vitaText(L10nKey.BACKUP_ITEM_MEDICATIONS), supporting = vitaText(L10nKey.BACKUP_ITEM_MEDICATIONS_COUNT, contents.medications, contents.doseEvents), leadingIcon = Res.drawable.ic_capsule, category = Category.CARE)
        if (contents.healthRecords > 0) ListRow(vitaText(L10nKey.BACKUP_ITEM_HEALTH_RECORDS), supporting = contents.healthRecords.toString(), leadingIcon = Res.drawable.ic_file_text, category = Category.RECORDS)
        if (contents.appointments > 0) ListRow(vitaText(L10nKey.APPT_TITLE), supporting = contents.appointments.toString(), leadingIcon = Res.drawable.ic_calendar, category = Category.RECORDS)
        if (contents.hasDeviceData) ListRow(vitaText(L10nKey.BACKUP_ITEM_DEVICE), leadingIcon = Res.drawable.ic_pulse, category = Category.ACTIVITY)
        ListRow(vitaText(L10nKey.BACKUP_ITEM_CHAT), supporting = contents.chatMessages.toString(), leadingIcon = Res.drawable.ic_chat, category = Category.TRENDS)
        ListRow(
            vitaText(L10nKey.BACKUP_ITEM_ORIGINALS),
            supporting = if (contents.originalFiles > 0) vitaText(L10nKey.BACKUP_ITEM_ORIGINALS_COUNT, contents.originalFiles, contents.documents) else vitaText(L10nKey.BACKUP_ITEM_NOT_INCLUDED),
            leadingIcon = Res.drawable.ic_photo,
            category = Category.ACTIVITY,
        )
    }
}
