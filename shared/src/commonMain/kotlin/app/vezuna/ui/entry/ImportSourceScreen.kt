package app.vezuna.ui.entry

import app.vezuna.ui.components.clearOfNavigationBar
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import app.vezuna.ui.AppScreen
import app.vezuna.ui.Category
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.FocusedTaskScaffold
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.components.TertiaryButton
import app.vezuna.ui.nav.RouteContext
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_camera
import vezuna.shared.generated.resources.ic_chevron_right
import vezuna.shared.generated.resources.ic_edit
import vezuna.shared.generated.resources.ic_file_pdf
import vezuna.shared.generated.resources.ic_file_text
import vezuna.shared.generated.resources.ic_photo
import vezuna.shared.generated.resources.ill_file_error
import vezuna.shared.generated.resources.ill_ocr_document
import vezuna.shared.generated.resources.ill_processing

/**
 * Add a record (030), then local extraction (035) and its failures (036 unreadable, 037 no results, 038 too large).
 * Camera, photos and files open the real system UI through the platform picker; cancelling returns here silently.
 */
@Composable
internal fun ImportSourceScreen(ctx: RouteContext) {
    val session = ctx.controller.importSession
    val phase by session.phase.collectAsState()
    var running by remember { mutableStateOf<Job?>(null) }
    val start: (SourceKind) -> Unit = { kind ->
        ctx.sourcePicker?.let { picker -> running = ctx.scope.launch { ctx.controller.importFrom(kind, picker) } }
    }
    // Cancelling stops extraction and deletes its temporary copies; nothing is saved because records are only
    // written after extraction returns a source.
    val cancel = { running?.cancel(); running = null }
    val manual = { session.idle(); ctx.navigate(AppScreen.ManualEntry) }
    FocusedTaskScaffold(title = vitaText(L10nKey.ENTRY_ADD_RECORD), onBack = ctx::back) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).clearOfNavigationBar().padding(horizontal = VezunaTokens.Gutter, vertical = VezunaTokens.SpaceMd),
            verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
        ) {
            when (val current = phase) {
                ImportPhase.Idle -> SourceChoices(ctx.sourcePicker != null, true, start, manual)
                // While the OS picker is open (no file yet) the choices stay underneath it, inactive.
                is ImportPhase.Reading -> current.fileName?.let { Reading(it, current.page, current.pageCount, cancel.takeIf { current.cancellable }) } ?: SourceChoices(ctx.sourcePicker != null, false, start, manual)
                is ImportPhase.Failed -> Problem(
                    illustration = Res.drawable.ill_file_error,
                    title = vitaText(current.reason.titleKey()),
                    body = current.reason.body(),
                    fileName = current.fileName,
                    onRetry = { start(current.kind) }.takeIf { current.reason == ImportFailureReason.UNREADABLE },
                    onOther = session::idle,
                    onManual = manual,
                )
                is ImportPhase.NoResults -> Problem(
                    illustration = Res.drawable.ill_ocr_document,
                    title = vitaText(L10nKey.ENTRY_NO_RESULTS_TITLE),
                    body = vitaText(L10nKey.ENTRY_NO_RESULTS_BODY),
                    fileName = current.fileName,
                    onRetry = { start(current.kind) },
                    onOther = session::idle,
                    onManual = manual,
                )
            }
        }
    }
}

private fun ImportFailureReason.titleKey(): L10nKey = when (this) {
    ImportFailureReason.TOO_LARGE -> L10nKey.ENTRY_TOO_LARGE_TITLE
    ImportFailureReason.UNREADABLE -> L10nKey.ENTRY_UNREADABLE_TITLE
    ImportFailureReason.CAMERA_UNAVAILABLE -> L10nKey.ENTRY_CAMERA_UNAVAILABLE_TITLE
    ImportFailureReason.CAMERA_DENIED -> L10nKey.ENTRY_CAMERA_DENIED_TITLE
    ImportFailureReason.TOO_MANY_PAGES -> L10nKey.ENTRY_TOO_MANY_PAGES_TITLE
}

@Composable
private fun ImportFailureReason.body(): String =
    if (this == ImportFailureReason.TOO_MANY_PAGES) vitaText(bodyKey(), MAX_PDF_PAGES) else vitaText(bodyKey())

private fun ImportFailureReason.bodyKey(): L10nKey = when (this) {
    ImportFailureReason.TOO_LARGE -> L10nKey.ENTRY_TOO_LARGE_BODY
    ImportFailureReason.UNREADABLE -> L10nKey.ENTRY_UNREADABLE_BODY
    ImportFailureReason.CAMERA_UNAVAILABLE -> L10nKey.ENTRY_CAMERA_UNAVAILABLE_BODY
    ImportFailureReason.CAMERA_DENIED -> L10nKey.ENTRY_CAMERA_DENIED_BODY
    ImportFailureReason.TOO_MANY_PAGES -> L10nKey.ENTRY_TOO_MANY_PAGES_BODY
}

@Composable
private fun SourceChoices(pickerAvailable: Boolean, idle: Boolean, onSource: (SourceKind) -> Unit, onManual: () -> Unit) {
    Text(vitaText(L10nKey.ENTRY_ADD_RECORD_INTRO), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SourceOption(Category.RECORDS, Res.drawable.ic_camera, vitaText(L10nKey.ENTRY_SOURCE_CAMERA), vitaText(L10nKey.ENTRY_SOURCE_CAMERA_HINT), pickerAvailable && idle) { onSource(SourceKind.CAMERA) }
    SourceOption(Category.CARE, Res.drawable.ic_photo, vitaText(L10nKey.ENTRY_SOURCE_PHOTOS), vitaText(L10nKey.ENTRY_SOURCE_PHOTOS_HINT), pickerAvailable && idle) { onSource(SourceKind.PHOTOS) }
    SourceOption(Category.TRENDS, Res.drawable.ic_file_pdf, vitaText(L10nKey.ENTRY_SOURCE_FILE), vitaText(L10nKey.ENTRY_SOURCE_FILE_HINT), pickerAvailable && idle) { onSource(SourceKind.FILE) }
    SourceOption(Category.ACTIVITY, Res.drawable.ic_edit, vitaText(L10nKey.ENTRY_SOURCE_MANUAL), vitaText(L10nKey.ENTRY_SOURCE_MANUAL_HINT), idle, onManual)
    if (!pickerAvailable) {
        Text(vitaText(L10nKey.ENTRY_PICKER_UNAVAILABLE), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(vitaText(L10nKey.ENTRY_ON_DEVICE_NOTE), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One large colored choice; the color names the kind of source, never a status. */
@Composable
private fun SourceOption(category: Category, icon: DrawableResource, title: String, hint: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(VezunaTokens.CardRadius),
        color = if (enabled) category.fill else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) category.onFill else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            Modifier.heightIn(min = 76.dp).padding(VezunaTokens.SpaceMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
        ) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(VezunaTokens.ControlRadius)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(hint, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(painterResource(Res.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun Reading(fileName: String, page: Int, pageCount: Int, onCancel: (() -> Unit)?) {
    Surface(shape = RoundedCornerShape(VezunaTokens.CardRadius), color = Category.RECORDS.fill, contentColor = Category.RECORDS.onFill) {
        Column(
            Modifier.fillMaxWidth().padding(VezunaTokens.SpaceLg).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd),
        ) {
            Image(painterResource(Res.drawable.ill_processing), contentDescription = null, modifier = Modifier.widthIn(max = 220.dp).fillMaxWidth().heightIn(max = 165.dp))
            Text(vitaText(L10nKey.ENTRY_READING_TITLE), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Text(fileName, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(vitaText(L10nKey.ENTRY_READING_BODY), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            if (pageCount > 0) {
                Text(vitaText(L10nKey.ENTRY_READING_PROGRESS, page, pageCount), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                LinearProgressIndicator(progress = { (page - 1).coerceAtLeast(0).toFloat() / pageCount }, modifier = Modifier.fillMaxWidth().padding(top = VezunaTokens.SpaceSm))
            } else {
                // Indeterminate until the page count is known.
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = VezunaTokens.SpaceSm))
            }
            onCancel?.let { TertiaryButton(vitaText(L10nKey.ENTRY_CANCEL_READING), it, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun Problem(
    illustration: DrawableResource,
    title: String,
    body: String,
    fileName: String?,
    onRetry: (() -> Unit)?,
    onOther: () -> Unit,
    onManual: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(VezunaTokens.CardRadius), color = Category.CARE.fill, contentColor = Category.CARE.onFill) {
        Column(
            Modifier.fillMaxWidth().padding(VezunaTokens.SpaceLg).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd),
        ) {
            Image(painterResource(illustration), contentDescription = null, modifier = Modifier.widthIn(max = 200.dp).fillMaxWidth().heightIn(max = 150.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        }
    }
    fileName?.let {
        Column(Modifier.clip(RoundedCornerShape(VezunaTokens.CardRadius))) { ListRow(it, leadingIcon = Res.drawable.ic_file_text) }
    }
    if (onRetry != null) PrimaryButton(vitaText(L10nKey.ENTRY_TRY_AGAIN), onRetry, Modifier.fillMaxWidth())
    SecondaryButton(vitaText(L10nKey.ENTRY_CHOOSE_ANOTHER), onOther, Modifier.fillMaxWidth())
    TertiaryButton(vitaText(L10nKey.ENTRY_SOURCE_MANUAL), onManual, Modifier.fillMaxWidth())
}
