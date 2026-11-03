package app.vezuna.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vezuna.domain.DocumentTags
import app.vezuna.domain.VaultDocument
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.vitaPlural
import app.vezuna.localization.vitaText
import app.vezuna.ui.AppScreen
import app.vezuna.ui.Category
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.CategoryPanel
import app.vezuna.ui.components.ConfirmDialog
import app.vezuna.ui.components.EmptyState
import app.vezuna.ui.components.FocusedTaskScaffold
import app.vezuna.ui.components.GroupedRows
import app.vezuna.ui.components.LabeledField
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.components.SectionHeading
import app.vezuna.ui.components.VezunaSheet
import app.vezuna.ui.components.clearOfArt
import app.vezuna.ui.components.clearOfNavigationBar
import app.vezuna.ui.components.returnFocusAfter
import app.vezuna.ui.entry.SourceKind
import app.vezuna.ui.nav.RouteContext
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_camera
import vezuna.shared.generated.resources.ic_close
import vezuna.shared.generated.resources.ic_edit
import vezuna.shared.generated.resources.ic_file_pdf
import vezuna.shared.generated.resources.ic_folder
import vezuna.shared.generated.resources.ic_photo
import vezuna.shared.generated.resources.ic_plus
import vezuna.shared.generated.resources.ic_trash
import vezuna.shared.generated.resources.ill_file_error
import vezuna.shared.generated.resources.ill_record_empty
import vezuna.shared.generated.resources.ill_records

private fun VaultDocument.isPdf() = source.mediaType.equals("application/pdf", ignoreCase = true)
private fun VaultDocument.hasFile() = source.vaultKey != null

/** The way into the document vault from the Records root; shown even when there are no lab records yet. */
@Composable
internal fun DocumentsEntry(count: Int, onClick: () -> Unit) = GroupedRows {
    ListRow(
        vitaText(L10nKey.DOCUMENTS_TITLE),
        supporting = if (count == 0) vitaText(L10nKey.DOCUMENTS_ENTRY_HINT) else vitaPlural(L10nKey.DOCUMENTS_COUNT, count),
        leadingIcon = Res.drawable.ic_folder,
        category = Category.RECORDS,
        onClick = onClick,
    )
}

/** Prescriptions, imaging reports and similar files: list, tag filter, add. Files are stored, never read. */
@Composable
internal fun DocumentsScreen(ctx: RouteContext) = FocusedTaskScaffold(title = vitaText(L10nKey.DOCUMENTS_TITLE), onBack = ctx::back) {
    val documents = ctx.state.data?.vaultDocuments ?: return@FocusedTaskScaffold
    val selected by ctx.controller.documentTagFilter.collectAsState()
    val tags = DocumentTags.inUse(documents)
    // A filter whose last document was deleted or retagged is no filter.
    val tag = tags.firstOrNull { it.equals(selected, ignoreCase = true) }
    var adding by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).clearOfNavigationBar().padding(horizontal = VezunaTokens.Gutter, vertical = VezunaTokens.SpaceMd),
        verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
    ) {
        if (documents.isEmpty()) {
            EmptyState(
                Res.drawable.ill_record_empty, vitaText(L10nKey.DOCUMENTS_EMPTY_TITLE), vitaText(L10nKey.DOCUMENTS_EMPTY_BODY),
                actionLabel = vitaText(L10nKey.DOCUMENTS_ADD), onAction = { adding = true },
            )
        } else {
            if (tags.isNotEmpty()) TagFilter(tags, tag, ctx.controller::filterDocumentsByTag)
            GroupedRows {
                DocumentTags.filter(documents, tag).forEach { document ->
                    DocumentRow(document) { ctx.navigate(AppScreen.DocumentDetail(document.source.documentId)) }
                }
            }
            PrimaryButton(vitaText(L10nKey.DOCUMENTS_ADD), { adding = true }, Modifier.fillMaxWidth(), icon = Res.drawable.ic_plus)
        }
        Text(vitaText(L10nKey.DOCUMENTS_PRIVACY_NOTE), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (adding) {
        AddDocumentSheet(ctx.sourcePicker != null, onDismiss = { adding = false }) { kind ->
            adding = false
            ctx.sourcePicker?.let { picker -> ctx.scope.launch { ctx.controller.addDocumentFrom(kind, picker) } }
        }
    }
}

@Composable
private fun TagFilter(tags: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
        Text(vitaText(L10nKey.DOCUMENTS_FILTER_BY_TAG), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
            TagChip(vitaText(L10nKey.DOCUMENTS_FILTER_ALL), selected == null) { onSelect(null) }
            tags.forEach { tag -> TagChip(tag, tag == selected) { onSelect(tag) } }
        }
    }
}

@Composable
private fun TagChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, modifier = Modifier.padding(vertical = VezunaTokens.SpaceSmd)) },
        modifier = Modifier.heightIn(min = VezunaTokens.TouchTarget),
    )
}

@Composable
private fun DocumentRow(document: VaultDocument, onClick: () -> Unit) {
    val details = buildList {
        add(formatUiDate(document.source.importedAt))
        if (document.tags.isNotEmpty()) add(document.tags.joinToString(", "))
        if (!document.hasFile()) add(vitaText(L10nKey.DOCUMENTS_NO_FILE))
    }
    ListRow(
        document.source.displayName,
        supporting = details.joinToString(" · "),
        leadingIcon = if (document.isPdf()) Res.drawable.ic_file_pdf else Res.drawable.ic_photo,
        category = Category.RECORDS,
        onClick = onClick,
    )
}

@Composable
private fun AddDocumentSheet(pickerAvailable: Boolean, onDismiss: () -> Unit, onSource: (SourceKind) -> Unit) {
    VezunaSheet(vitaText(L10nKey.DOCUMENTS_ADD), onDismiss) {
        GroupedRows {
            ListRow(
                vitaText(L10nKey.ENTRY_SOURCE_CAMERA), supporting = vitaText(L10nKey.DOCUMENTS_SOURCE_CAMERA_HINT), leadingIcon = Res.drawable.ic_camera,
                category = Category.RECORDS, enabled = pickerAvailable, onClick = { onSource(SourceKind.CAMERA) },
            )
            ListRow(
                vitaText(L10nKey.ENTRY_SOURCE_PHOTOS), supporting = vitaText(L10nKey.DOCUMENTS_SOURCE_PHOTOS_HINT), leadingIcon = Res.drawable.ic_photo,
                category = Category.CARE, enabled = pickerAvailable, onClick = { onSource(SourceKind.PHOTOS) },
            )
            ListRow(
                vitaText(L10nKey.ENTRY_SOURCE_FILE), supporting = vitaText(L10nKey.DOCUMENTS_SOURCE_FILE_HINT), leadingIcon = Res.drawable.ic_file_pdf,
                category = Category.TRENDS, enabled = pickerAvailable, onClick = { onSource(SourceKind.FILE) },
            )
        }
        if (!pickerAvailable) Text(vitaText(L10nKey.DOCUMENTS_PICKER_UNAVAILABLE), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One document: its name and tags, its file, edit, and delete with confirmation. */
@Composable
internal fun DocumentDetailScreen(ctx: RouteContext, documentId: String) = FocusedTaskScaffold(title = null, onBack = ctx::back) {
    val document = ctx.state.data?.vaultDocuments?.firstOrNull { it.source.documentId == documentId } ?: return@FocusedTaskScaffold EmptyState(
        Res.drawable.ill_file_error, vitaText(L10nKey.DOCUMENTS_NOT_FOUND), vitaText(L10nKey.DOCUMENTS_NOT_FOUND_BODY),
        Modifier.padding(VezunaTokens.Gutter), actionLabel = vitaText(L10nKey.DOCUMENTS_RETURN), onAction = ctx::back,
    )
    var editing by remember(documentId) { mutableStateOf(false) }
    var confirmDelete by remember(documentId) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).clearOfNavigationBar().padding(horizontal = VezunaTokens.Gutter, vertical = VezunaTokens.SpaceMd),
        verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
    ) {
        CategoryPanel(Category.RECORDS, illustration = Res.drawable.ill_records) {
            Text(document.source.displayName, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.clearOfArt().semantics { heading() })
            Text(vitaText(L10nKey.DOCUMENTS_ADDED_ON, formatUiDate(document.source.importedAt)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.clearOfArt())
            Text(ctx.state.data?.profile?.displayName.orEmpty(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.clearOfArt())
            Text(
                if (document.tags.isEmpty()) vitaText(L10nKey.DOCUMENTS_NO_TAGS) else vitaText(L10nKey.DOCUMENTS_TAGS_LIST, document.tags.joinToString(", ")),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (document.hasFile()) {
            PrimaryButton(vitaText(L10nKey.DOCUMENTS_VIEW_FILE), { ctx.navigate(AppScreen.SourceViewer(documentId)) }, Modifier.fillMaxWidth(), icon = if (document.isPdf()) Res.drawable.ic_file_pdf else Res.drawable.ic_photo)
        } else {
            CategoryPanel(Category.CARE) {
                Text(vitaText(L10nKey.DOCUMENTS_NO_FILE), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text(vitaText(L10nKey.DOCUMENTS_FILE_MISSING_BODY), style = MaterialTheme.typography.bodyMedium)
            }
        }
        GroupedRows {
            ListRow(vitaText(L10nKey.DOCUMENTS_EDIT), leadingIcon = Res.drawable.ic_edit, onClick = { editing = true }, modifier = Modifier.returnFocusAfter(editing))
            ListRow(vitaText(L10nKey.DOCUMENTS_DELETE), leadingIcon = Res.drawable.ic_trash, onClick = { confirmDelete = true }, modifier = Modifier.returnFocusAfter(confirmDelete))
        }
    }
    if (editing) {
        EditDocumentSheet(document, onDismiss = { editing = false }) { name, tags ->
            editing = false
            ctx.scope.launch { ctx.controller.updateDocument(documentId, name, tags) }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = vitaText(L10nKey.DOCUMENTS_DELETE_TITLE, document.source.displayName),
            body = vitaText(L10nKey.DOCUMENTS_DELETE_BODY),
            confirmLabel = vitaText(L10nKey.DOCUMENTS_DELETE),
            onConfirm = {
                confirmDelete = false
                ctx.scope.launch {
                    ctx.controller.deleteDocument(documentId)
                    if (ctx.controller.state.value.data?.vaultDocuments?.none { it.source.documentId == documentId } == true) ctx.back()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun EditDocumentSheet(document: VaultDocument, onDismiss: () -> Unit, onSave: (String, List<String>) -> Unit) {
    var name by remember { mutableStateOf(document.source.displayName) }
    var tags by remember { mutableStateOf(document.tags) }
    var newTag by remember { mutableStateOf("") }
    val addTag = { tag: String -> tags = DocumentTags.clean(tags + tag); newTag = "" }
    val nameMissing = name.isBlank()
    val suggestions = listOf(
        vitaText(L10nKey.DOCUMENTS_TAG_PRESCRIPTION), vitaText(L10nKey.DOCUMENTS_TAG_IMAGING), vitaText(L10nKey.DOCUMENTS_TAG_REFERRAL),
        vitaText(L10nKey.DOCUMENTS_TAG_VACCINATION), vitaText(L10nKey.DOCUMENTS_TAG_INSURANCE),
    ).filter { suggestion -> tags.none { it.equals(suggestion, ignoreCase = true) } }
    VezunaSheet(vitaText(L10nKey.DOCUMENTS_EDIT), onDismiss) {
        LabeledField(vitaText(L10nKey.DOCUMENTS_NAME_LABEL), name, { name = it.take(DocumentTags.MAX_NAME_LENGTH) }, error = if (nameMissing) vitaText(L10nKey.DOCUMENTS_NAME_REQUIRED) else null)
        SectionHeading(vitaText(L10nKey.DOCUMENTS_TAGS_HEADING))
        if (tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
                tags.forEach { tag ->
                    val remove = vitaText(L10nKey.DOCUMENTS_REMOVE_TAG, tag)
                    InputChip(
                        selected = false,
                        onClick = { tags = tags - tag },
                        label = { Text(tag, modifier = Modifier.padding(vertical = VezunaTokens.SpaceSmd)) },
                        trailingIcon = { Icon(painterResource(Res.drawable.ic_close), contentDescription = remove) },
                        modifier = Modifier.heightIn(min = VezunaTokens.TouchTarget),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
            LabeledField(vitaText(L10nKey.DOCUMENTS_NEW_TAG_LABEL), newTag, { newTag = it.take(DocumentTags.MAX_LENGTH) }, Modifier.weight(1f))
            SecondaryButton(vitaText(L10nKey.DOCUMENTS_ADD_TAG), { addTag(newTag) }, enabled = newTag.isNotBlank() && tags.size < DocumentTags.MAX_PER_DOCUMENT)
        }
        if (suggestions.isNotEmpty()) {
            Text(vitaText(L10nKey.DOCUMENTS_TAG_SUGGESTIONS), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }.padding(top = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
                suggestions.forEach { suggestion -> TagChip(suggestion, false) { addTag(suggestion) } }
            }
        }
        // A tag typed but not added yet is what the person meant to save.
        PrimaryButton(vitaText(L10nKey.DOCUMENTS_SAVE), { onSave(name, tags + newTag) }, Modifier.fillMaxWidth(), enabled = !nameMissing)
        SecondaryButton(vitaText(L10nKey.CANCEL), onDismiss, Modifier.fillMaxWidth())
    }
}
