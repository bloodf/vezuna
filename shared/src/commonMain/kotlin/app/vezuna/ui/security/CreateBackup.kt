package app.vezuna.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vezuna.domain.ProfileData
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.localization.VitaMessageException
import app.vezuna.localization.resolve
import app.vezuna.localization.toUserText
import app.vezuna.localization.vitaText
import app.vezuna.security.BackupMember
import app.vezuna.security.BackupService
import app.vezuna.security.PassphraseStrength
import app.vezuna.security.PassphraseStrengthLevel
import app.vezuna.ui.BackupFileAccess
import app.vezuna.ui.Category
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.backupFileName
import app.vezuna.ui.components.FocusedTaskScaffold
import app.vezuna.ui.components.GroupedRows
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.ProgressState
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.components.StatusKind
import app.vezuna.ui.householdBackupFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_chat
import vezuna.shared.generated.resources.ic_file_text
import vezuna.shared.generated.resources.ic_key
import vezuna.shared.generated.resources.ill_processing
import vezuna.shared.generated.resources.ill_success

/** An encrypted file held in memory until the user picks where to save it. [household]: every profile. */
private class ReadyBackup(val fileName: String, val bytes: ByteArray, val household: Boolean, val coversEverything: Boolean, val withoutChat: Boolean, val leftOut: Int)

/**
 * Images 085-088: scope, chat choice and passphrase, then encryption, then the ready file and the native save.
 * Only a confirmed save counts: a cancelled save sheet keeps the file here and records nothing (R21).
 * [onSaved] receives whether the saved file holds every profile of the household.
 */
@Composable
internal fun CreateBackupFlow(
    service: BackupService,
    data: ProfileData,
    files: BackupFileAccess,
    household: (suspend (Set<Pair<String, String>>) -> List<BackupMember>)?,
    proposeLeaveOut: suspend () -> LeaveOutProposal,
    profileCount: Int,
    onSaved: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    var wholeHousehold by remember { mutableStateOf(false) }
    var leaveOutChat by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var encrypting by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<UserText?>(null) }
    var notSaved by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf<ReadyBackup?>(null) }
    var proposal by remember { mutableStateOf<LeaveOutProposal?>(null) }
    val scope = rememberCoroutineScope()
    val level = PassphraseStrength.evaluate(passphrase.toCharArray())
    val matches = passphrase == confirmation
    val canCreate = level != PassphraseStrengthLevel.TOO_SHORT && matches && !encrypting
    val close = { passphrase = ""; confirmation = ""; onClose() }

    fun encrypt(omit: Set<Pair<String, String>> = emptySet()) {
        encrypting = true
        error = null
        val chars = passphrase.toCharArray()
        val all = wholeHousehold && household != null
        scope.launch {
            try {
                val members = if (all) {
                    try {
                        household!!.invoke(omit)
                    } catch (tooLarge: VitaMessageException) {
                        if (omit.isNotEmpty() || tooLarge.userText.key != L10nKey.BACKUP_TOO_LARGE_NAMED) throw tooLarge
                        // Nothing is written and nothing is left out until the person agrees to the list.
                        proposal = proposeLeaveOut()
                        return@launch
                    }
                } else {
                    listOf(BackupMember(data))
                }
                val chosen = if (leaveOutChat) members.withoutChat() else members
                val bytes = if (all) service.createHousehold(chosen, chars) else service.create(chosen.first().data, chars)
                // A single-profile file still covers the household when there is one profile and nothing else to carry.
                val coversEverything = all || (profileCount == 1 && household == null)
                ready = ReadyBackup(if (all) householdBackupFileName() else backupFileName(data), bytes, all, coversEverything, leaveOutChat, members.sumOf { it.omitted.size })
                passphrase = ""
                confirmation = ""
                proposal = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                proposal = null
                error = failure.toUserText()
            } finally {
                encrypting = false
                chars.fill('\u0000')
            }
        }
    }

    val current = ready
    val pending = proposal
    FocusedTaskScaffold(title = vitaText(L10nKey.BACKUP_CREATE_ACTION), onBack = { if (!encrypting && !saving) { if (pending != null) proposal = null else close() } }) {
        when {
            encrypting -> SecurityPage {
                SecurityHero(
                    Res.drawable.ill_processing,
                    vitaText(if (wholeHousehold) L10nKey.BACKUP_ENCRYPTING_HOUSEHOLD else L10nKey.BACKUP_ENCRYPTING, data.profile.displayName),
                    vitaText(L10nKey.BACKUP_ENCRYPTING_BODY),
                    live = true,
                )
                ProgressState(vitaText(L10nKey.PLEASE_WAIT))
            }
            pending != null -> LeaveOutPage(pending, onAgree = { encrypt(pending.keys) }, onCancel = { proposal = null })
            current != null -> SecurityPage {
                SecurityHero(Res.drawable.ill_success, vitaText(L10nKey.BACKUP_READY_TITLE), current.fileName, live = true)
                GroupedRows {
                    ListRow(vitaText(L10nKey.BACKUP_READY_SIZE), supporting = fileSizeText(current.bytes.size), leadingIcon = Res.drawable.ic_file_text, category = Category.RECORDS)
                    ListRow(vitaText(L10nKey.BACKUP_READY_PASSPHRASE), supporting = vitaText(L10nKey.BACKUP_READY_PASSPHRASE_BODY), leadingIcon = Res.drawable.ic_key, category = Category.TRENDS)
                }
                SecurityNotice(scopeTitle(current.household, data.profile.displayName), StatusKind.INFO, detail = scopeBoundary(current.household))
                if (current.withoutChat) SecurityNotice(vitaText(L10nKey.BACKUP_READY_CHAT_LEFT_OUT), StatusKind.INFO)
                if (current.leftOut > 0) SecurityNotice(vitaText(L10nKey.BACKUP_READY_LEFT_OUT), StatusKind.WARNING)
                if (notSaved) SecurityNotice(vitaText(L10nKey.BACKUP_NOT_SAVED), StatusKind.WARNING)
                error?.let { SecurityNotice(it.resolve(), StatusKind.ERROR) }
                PrimaryButton(vitaText(L10nKey.BACKUP_SAVE_ACTION), loading = saving, modifier = Modifier.fillMaxWidth(), onClick = {
                    saving = true
                    notSaved = false
                    error = null
                    scope.launch {
                        try {
                            if (files.save(current.fileName, current.bytes)) {
                                ready = null
                                onSaved(current.coversEverything)
                            } else {
                                notSaved = true
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            error = failure.toUserText()
                        } finally {
                            saving = false
                        }
                    }
                })
                SecondaryButton(vitaText(L10nKey.BACKUP_DISCARD), close, Modifier.fillMaxWidth(), enabled = !saving)
            }
            else -> SecurityPage {
                if (household != null) {
                    Text(vitaText(L10nKey.BACKUP_SCOPE_TITLE), style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
                        ScopeOption(!wholeHousehold, scopeTitle(false, data.profile.displayName), scopeBoundary(false)) { wholeHousehold = false }
                        ScopeOption(wholeHousehold, scopeTitle(true), scopeBoundary(true)) { wholeHousehold = true }
                    }
                } else {
                    SecurityNotice(scopeTitle(false, data.profile.displayName), StatusKind.INFO, detail = scopeBoundary(false))
                }
                if (!wholeHousehold && data.vaultDocuments.any { it.source.vaultKey != null }) {
                    SecurityNotice(vitaText(L10nKey.BACKUP_DOCUMENTS_LEFT_OUT), StatusKind.WARNING)
                }
                GroupedRows {
                    ListRow(
                        vitaText(L10nKey.BACKUP_LEAVE_OUT_CHAT),
                        supporting = vitaText(L10nKey.BACKUP_LEAVE_OUT_CHAT_BODY),
                        leadingIcon = Res.drawable.ic_chat,
                        category = Category.TRENDS,
                        showChevron = false,
                        trailing = { Checkbox(checked = leaveOutChat, onCheckedChange = null) },
                        modifier = Modifier.toggleable(value = leaveOutChat, role = Role.Checkbox) { leaveOutChat = it },
                    )
                }
                PassphraseField(vitaText(L10nKey.BACKUP_PASSPHRASE_LABEL), passphrase, { passphrase = it; error = null }, enabled = true, newPassword = true)
                if (passphrase.isNotEmpty()) StrengthMeter(level)
                PassphraseField(
                    vitaText(L10nKey.BACKUP_PASSPHRASE_CONFIRM_LABEL), confirmation, { confirmation = it; error = null }, enabled = true, newPassword = true,
                    error = if (confirmation.isNotEmpty() && !matches) vitaText(L10nKey.BACKUP_PASSPHRASE_MISMATCH) else null,
                )
                SecurityNotice(vitaText(L10nKey.BACKUP_KEEP_PASSPHRASE), StatusKind.WARNING, detail = vitaText(L10nKey.BACKUP_PASSPHRASE_WARNING))
                error?.let { SecurityNotice(it.resolve(), StatusKind.ERROR) }
                PrimaryButton(vitaText(L10nKey.BACKUP_CREATE_ACTION), ::encrypt, Modifier.fillMaxWidth(), enabled = canCreate)
            }
        }
    }
}

/** What the chosen scope holds: household backups carry original files (up to the size limit), profile backups do not. */
@Composable
internal fun scopeBoundary(household: Boolean): String =
    vitaText(if (household) L10nKey.BACKUP_SCOPE_HOUSEHOLD_BODY else L10nKey.BACKUP_SCOPE_PROFILE_BODY)

@Composable
private fun scopeTitle(household: Boolean, name: String = ""): String =
    if (household) vitaText(L10nKey.BACKUP_SCOPE_HOUSEHOLD) else vitaText(L10nKey.BACKUP_SCOPE_PROFILE_NAMED, name)

@Composable
private fun ScopeOption(selected: Boolean, title: String, body: String, onSelect: () -> Unit) {
    GroupedRows {
        ListRow(
            title = title,
            supporting = body,
            showChevron = false,
            leading = { RadioButton(selected = selected, onClick = null) },
            modifier = Modifier.heightIn(min = 64.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        )
    }
}

/** The documents that do not fit, how they were chosen, and the explicit choice: back up without them or cancel. */
@Composable
internal fun LeaveOutPage(proposal: LeaveOutProposal, onAgree: () -> Unit, onCancel: () -> Unit) {
    val confirmLabel = vitaText(L10nKey.BACKUP_LEAVE_OUT_CONFIRM_A11Y)
    SecurityPage {
        SecurityNotice(vitaText(L10nKey.BACKUP_LEAVE_OUT_TITLE), StatusKind.WARNING, detail = vitaText(L10nKey.BACKUP_LEAVE_OUT_BODY))
        GroupedRows {
            proposal.documents.forEach {
                ListRow(
                    it.displayName,
                    supporting = vitaText(L10nKey.BACKUP_LEAVE_OUT_ROW, it.profileName, fileSizeText(it.bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())),
                    leadingIcon = Res.drawable.ic_file_text,
                    category = Category.RECORDS,
                    showChevron = false,
                )
            }
        }
        Text(vitaText(L10nKey.BACKUP_LEAVE_OUT_STAYS), style = MaterialTheme.typography.bodyMedium)
        PrimaryButton(vitaText(L10nKey.BACKUP_LEAVE_OUT_CONFIRM), onAgree, Modifier.fillMaxWidth().semantics { contentDescription = confirmLabel })
        SecondaryButton(vitaText(L10nKey.CANCEL), onCancel, Modifier.fillMaxWidth())
    }
}
