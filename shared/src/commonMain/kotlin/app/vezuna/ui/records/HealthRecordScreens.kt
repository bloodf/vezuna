package app.vezuna.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import app.vezuna.domain.AllergyRecord
import app.vezuna.domain.AllergySeverity
import app.vezuna.domain.ConditionRecord
import app.vezuna.domain.ConditionStatus
import app.vezuna.domain.HealthRecord
import app.vezuna.domain.HealthRecordKind
import app.vezuna.domain.PartialDate
import app.vezuna.domain.ProfileData
import app.vezuna.domain.VaccinationRecord
import app.vezuna.domain.healthRecords
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.formatUiMonth
import app.vezuna.localization.vitaPlural
import app.vezuna.localization.vitaText
import app.vezuna.platform.currentInstant
import app.vezuna.ui.AppScreen
import app.vezuna.ui.Category
import app.vezuna.ui.CollectionDateField
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.ConfirmDialog
import app.vezuna.ui.components.DestructiveButton
import app.vezuna.ui.components.EmptyState
import app.vezuna.ui.components.FocusedTaskScaffold
import app.vezuna.ui.components.GroupedRows
import app.vezuna.ui.components.LabeledField
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.TertiaryButton
import app.vezuna.ui.entry.FixFieldsNotice
import app.vezuna.ui.entry.FormColumn
import app.vezuna.ui.entry.guardedBack
import app.vezuna.ui.export.atNoonUtc
import app.vezuna.ui.nav.RouteContext
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.DrawableResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_block
import vezuna.shared.generated.resources.ic_drop
import vezuna.shared.generated.resources.ic_heart
import vezuna.shared.generated.resources.ic_plus
import vezuna.shared.generated.resources.ill_file_error
import vezuna.shared.generated.resources.ill_record_empty

private class KindStyle(val title: L10nKey, val add: L10nKey, val edit: L10nKey, val emptyBody: L10nKey, val nameField: L10nKey, val icon: DrawableResource)

private fun HealthRecordKind.style() = when (this) {
    HealthRecordKind.ALLERGY -> KindStyle(L10nKey.HR_ALLERGIES, L10nKey.HR_ADD_ALLERGY, L10nKey.HR_EDIT_ALLERGY, L10nKey.HR_EMPTY_ALLERGIES, L10nKey.HR_FIELD_SUBSTANCE, Res.drawable.ic_block)
    HealthRecordKind.CONDITION -> KindStyle(L10nKey.HR_CONDITIONS, L10nKey.HR_ADD_CONDITION, L10nKey.HR_EDIT_CONDITION, L10nKey.HR_EMPTY_CONDITIONS, L10nKey.HR_FIELD_CONDITION, Res.drawable.ic_heart)
    HealthRecordKind.VACCINATION -> KindStyle(L10nKey.HR_VACCINATIONS, L10nKey.HR_ADD_VACCINATION, L10nKey.HR_EDIT_VACCINATION, L10nKey.HR_EMPTY_VACCINATIONS, L10nKey.HR_FIELD_VACCINE, Res.drawable.ic_drop)
}

/** The Records root's way in: one row per kind with its count. */
@Composable
internal fun HealthRecordRows(ctx: RouteContext, data: ProfileData) {
    GroupedRows(title = vitaText(L10nKey.HR_SECTION)) {
        HealthRecordKind.entries.forEach { kind ->
            val style = kind.style()
            ListRow(
                vitaText(style.title),
                onClick = { ctx.navigate(AppScreen.HealthRecords(kind)) },
                supporting = vitaPlural(L10nKey.HR_COUNT, data.healthRecords(kind).size),
                leadingIcon = style.icon,
                category = Category.RECORDS,
            )
        }
    }
}

@Composable
internal fun HealthRecordListScreen(ctx: RouteContext, kind: HealthRecordKind) {
    val style = kind.style()
    FocusedTaskScaffold(title = vitaText(style.title), onBack = ctx::back) {
        val data = ctx.state.data ?: return@FocusedTaskScaffold
        val records = data.healthRecords(kind)
        val add = { ctx.navigate(AppScreen.HealthRecordForm(kind)) }
        FormColumn {
            Text(vitaText(L10nKey.HR_INTRO), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (records.isEmpty()) {
                EmptyState(Res.drawable.ill_record_empty, vitaText(L10nKey.HR_EMPTY_TITLE), vitaText(style.emptyBody), actionLabel = vitaText(style.add), onAction = add)
            } else {
                GroupedRows {
                    records.forEach { record ->
                        ListRow(
                            record.title,
                            onClick = { ctx.navigate(AppScreen.HealthRecordForm(kind, record.id)) },
                            supporting = summary(record),
                            leadingIcon = style.icon,
                            category = Category.RECORDS,
                        )
                    }
                }
                PrimaryButton(vitaText(style.add), add, Modifier.fillMaxWidth(), icon = Res.drawable.ic_plus)
            }
        }
    }
}

@Composable
private fun summary(record: HealthRecord): String? = when (record) {
    is AllergyRecord -> listOfNotNull(record.reaction, record.severity?.let { vitaText(severityKey(it)) }, record.notes)
    is ConditionRecord -> listOfNotNull(
        record.onset?.let { vitaText(L10nKey.HR_ONSET_LABEL, onsetText(it)) } ?: vitaText(L10nKey.HR_ONSET_UNKNOWN),
        record.status?.let { vitaText(statusKey(it)) },
        record.notes,
    )
    is VaccinationRecord -> listOfNotNull(
        vitaText(L10nKey.HR_DATE_LABEL, record.date?.let { formatUiDate(it) } ?: vitaText(L10nKey.HR_DATE_NOT_RECORDED)),
        record.doseNumber?.let { vitaText(L10nKey.HR_DOSE_LABEL, it) },
        record.lot?.let { vitaText(L10nKey.HR_LOT_LABEL, it) },
    )
}.joinToString(" · ").ifEmpty { null }

/** At the precision the owner gave, never more. */
@Composable
private fun onsetText(onset: PartialDate): String = when {
    onset.month == null -> onset.year.toString()
    onset.day == null -> formatUiMonth(LocalDate(onset.year, onset.month, 1).atNoonUtc())
    else -> formatUiDate(LocalDate(onset.year, onset.month, onset.day).atNoonUtc())
}

private fun severityKey(value: AllergySeverity) = when (value) {
    AllergySeverity.MILD -> L10nKey.HR_SEVERITY_MILD
    AllergySeverity.MODERATE -> L10nKey.HR_SEVERITY_MODERATE
    AllergySeverity.SEVERE -> L10nKey.HR_SEVERITY_SEVERE
}

private fun statusKey(value: ConditionStatus) = when (value) {
    ConditionStatus.ONGOING -> L10nKey.HR_STATUS_ONGOING
    ConditionStatus.RESOLVED -> L10nKey.HR_STATUS_RESOLVED
}

/** Add ([recordId] null) or edit, with delete on an existing record. */
@Composable
internal fun HealthRecordFormScreen(ctx: RouteContext, kind: HealthRecordKind, recordId: String?) {
    val style = kind.style()
    val title = vitaText(if (recordId == null) style.add else style.edit)
    val data = ctx.state.data ?: return
    val existing = recordId?.let { id -> data.healthRecords.firstOrNull { it.id == id && it.kind == kind } }
    if (recordId != null && existing == null) {
        FocusedTaskScaffold(title = title, onBack = ctx::back) {
            EmptyState(Res.drawable.ill_file_error, vitaText(L10nKey.HR_NOT_FOUND), vitaText(L10nKey.HR_INTRO), Modifier.padding(VezunaTokens.Gutter))
        }
        return
    }
    val key = Triple(data.profile.profileId, kind, recordId)
    val initial = remember(key) { existing?.toDraft() ?: HealthRecordDraft(kind) }
    var draft by remember(key) { mutableStateOf(HealthRecordFormSession.restore(key) ?: initial) }
    var showErrors by remember(key) { mutableStateOf(false) }
    var busy by remember(key) { mutableStateOf(false) }
    var confirmDelete by remember(key) { mutableStateOf(false) }
    SideEffect { HealthRecordFormSession.keep(key, draft.takeIf { it != initial }) }
    val today = remember { currentInstant().toLocalDateTime(TimeZone.currentSystemDefault()).date }
    val leave = { HealthRecordFormSession.keep(key, null); ctx.back() }
    val back = guardedBack(draft != initial, leave)
    FocusedTaskScaffold(title = title, onBack = back) {
        FormColumn {
            Text(vitaText(L10nKey.ENTRY_FOR_PROFILE, data.profile.displayName), style = MaterialTheme.typography.titleMedium)
            LabeledField(vitaText(style.nameField), draft.name, { draft = draft.copy(name = it) }, error = vitaText(L10nKey.HR_NAME_REQUIRED).takeIf { showErrors && draft.nameMissing }, singleLine = false)
            when (kind) {
                HealthRecordKind.ALLERGY -> AllergyFields(draft) { draft = it }
                HealthRecordKind.CONDITION -> ConditionFields(draft, onsetError = showErrors && draft.onsetInvalid(today)) { draft = it }
                HealthRecordKind.VACCINATION -> VaccinationFields(draft) { draft = it }
            }
            FixFieldsNotice(showErrors && !draft.isValid(today))
            PrimaryButton(
                vitaText(L10nKey.SAVE),
                {
                    if (!draft.isValid(today)) {
                        showErrors = true
                    } else {
                        busy = true
                        val saving = draft
                        ctx.scope.launch {
                            ctx.controller.saveHealthRecord(recordId, saving)
                            busy = false
                            if (ctx.controller.state.value.error == null) leave()
                        }
                    }
                },
                Modifier.fillMaxWidth(),
                loading = busy,
            )
            existing?.let { record -> DestructiveButton(vitaText(L10nKey.HR_DELETE, record.title), { confirmDelete = true }, Modifier.fillMaxWidth()) }
        }
    }
    if (confirmDelete && existing != null) {
        ConfirmDialog(
            title = vitaText(L10nKey.HR_DELETE, existing.title),
            body = vitaText(L10nKey.HR_DELETE_BODY),
            confirmLabel = vitaText(L10nKey.DELETE),
            onConfirm = {
                confirmDelete = false
                ctx.scope.launch {
                    ctx.controller.deleteHealthRecord(existing.id)
                    if (ctx.controller.state.value.error == null) leave()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun AllergyFields(draft: HealthRecordDraft, onChange: (HealthRecordDraft) -> Unit) {
    LabeledField(vitaText(L10nKey.HR_FIELD_REACTION), draft.reaction, { onChange(draft.copy(reaction = it)) }, singleLine = false)
    LabelPicker(vitaText(L10nKey.HR_FIELD_SEVERITY), AllergySeverity.entries, draft.severity, ::severityKey) { onChange(draft.copy(severity = it)) }
    LabeledField(vitaText(L10nKey.HR_FIELD_NOTES), draft.notes, { onChange(draft.copy(notes = it)) }, singleLine = false)
}

@Composable
private fun ConditionFields(draft: HealthRecordDraft, onsetError: Boolean, onChange: (HealthRecordDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
        Text(vitaText(L10nKey.HR_ONSET), style = MaterialTheme.typography.titleMedium)
        Text(vitaText(L10nKey.HR_ONSET_HINT), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LabeledField(vitaText(L10nKey.HR_ONSET_YEAR), draft.onsetYear, { onChange(draft.copy(onsetYear = it)) }, keyboardType = KeyboardType.Number, error = vitaText(L10nKey.HR_ONSET_INVALID).takeIf { onsetError })
        LabeledField(vitaText(L10nKey.HR_ONSET_MONTH), draft.onsetMonth, { onChange(draft.copy(onsetMonth = it)) }, keyboardType = KeyboardType.Number)
        LabeledField(vitaText(L10nKey.HR_ONSET_DAY), draft.onsetDay, { onChange(draft.copy(onsetDay = it)) }, keyboardType = KeyboardType.Number)
    }
    LabelPicker(vitaText(L10nKey.HR_FIELD_STATUS), ConditionStatus.entries, draft.status, ::statusKey) { onChange(draft.copy(status = it)) }
    LabeledField(vitaText(L10nKey.HR_FIELD_NOTES), draft.notes, { onChange(draft.copy(notes = it)) }, singleLine = false)
}

@Composable
private fun VaccinationFields(draft: HealthRecordDraft, onChange: (HealthRecordDraft) -> Unit) {
    Column {
        CollectionDateField(draft.date, { date, _ -> onChange(draft.copy(date = date)) }, label = vitaText(L10nKey.HR_FIELD_DATE), help = vitaText(L10nKey.HR_DATE_HELP))
        if (draft.date != null) TertiaryButton(vitaText(L10nKey.CLEAR_DATE), { onChange(draft.copy(date = null)) })
    }
    LabeledField(vitaText(L10nKey.HR_FIELD_DOSE), draft.dose, { onChange(draft.copy(dose = it)) })
    LabeledField(vitaText(L10nKey.HR_FIELD_LOT), draft.lot, { onChange(draft.copy(lot = it)) })
}

/** The owner's own label from a short list; "Not stated" (null) is the start and is never assumed to mean anything. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T : Any> LabelPicker(title: String, options: List<T>, selected: T?, labelKey: (T) -> L10nKey, onSelect: (T?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
            (listOf<T?>(null) + options).forEach { option ->
                val isSelected = option == selected
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(option) },
                    label = { Text(vitaText(option?.let(labelKey) ?: L10nKey.HR_NOT_STATED)) },
                    modifier = Modifier.heightIn(min = VezunaTokens.TouchTarget).semantics { role = Role.RadioButton; this.selected = isSelected },
                )
            }
        }
    }
}
