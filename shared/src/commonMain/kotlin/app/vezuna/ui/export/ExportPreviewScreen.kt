package app.vezuna.ui.export

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vezuna.domain.ProfileData
import app.vezuna.export.DoctorExportContent
import app.vezuna.export.ExportOptions
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.message
import app.vezuna.localization.resolve
import app.vezuna.localization.vitaPlural
import app.vezuna.localization.vitaText
import app.vezuna.platform.currentInstant
import app.vezuna.ui.AppScreen
import app.vezuna.ui.Category
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.CategoryPanel
import app.vezuna.ui.components.GroupedRows
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.components.StatusKind
import app.vezuna.ui.components.StatusTag
import app.vezuna.ui.components.TertiaryButton
import app.vezuna.ui.nav.RouteContext
import app.vezuna.ui.records.renderPdfPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_download
import vezuna.shared.generated.resources.ic_file_csv
import vezuna.shared.generated.resources.ic_file_pdf
import vezuna.shared.generated.resources.ic_refresh
import vezuna.shared.generated.resources.ic_share
import vezuna.shared.generated.resources.ill_processing
import vezuna.shared.generated.resources.ill_record_empty

/** A file ready to hand to the system: the preview shows these exact bytes and save/share receive them unchanged. */
private class ReadyFile(val fileName: String, val mimeType: String, val bytes: ByteArray)

/**
 * Images 118-123, 127, 138 and 121: builds the file once, previews it, then hands those bytes to the system save
 * or share sheet. Empty selections, creation failures and save failures each get their own recoverable state.
 */
@Composable
internal fun ExportPreviewScreen(ctx: RouteContext, kind: String) {
    val data = ctx.state.data ?: return
    val draft = ExportSession.existing(data.profile.profileId, kind) ?: ctx.draft(kind) ?: return
    val options = draft.options(today()) ?: ExportOptions()
    val csv = kind == CSV_KIND
    var sparseConfirmed by remember { mutableStateOf(false) }
    if (!sparseConfirmed && !ExportScope.hasContent(data, options, csv, TimeZone.currentSystemDefault())) {
        EmptyExport(ctx, data, options, csv) { sparseConfirmed = true }
        return
    }
    var attempt by remember { mutableStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        failed = false
        finished = false
        ctx.controller.clearExports()
        if (csv) ctx.controller.exportCsv(draft.language, options) else ctx.controller.exportPdf(draft.language, options)
        val state = ctx.controller.state.value
        failed = if (csv) state.csvExport == null else state.pdfExport == null
        finished = true
    }
    val ready = when {
        !finished -> null
        csv -> ctx.state.csvExport?.let { ReadyFile(DoctorExportContent.csvFileName(data.profile.displayName, currentInstant()), ExportPlatform.CSV, it.encodeToByteArray()) }
        else -> ctx.state.pdfExport?.let { ReadyFile(it.fileName, ExportPlatform.PDF, it.bytes) }
    }
    when {
        failed -> ExportFailed(ctx, data, options, csv, ctx.state.error?.resolve(), onRetry = { attempt++ })
        ready == null -> Preparing(data.profile.displayName)
        csv -> CsvReady(ctx, data, options, ready, ctx.state.csvExport.orEmpty())
        else -> PdfReady(ctx, data, options, ready)
    }
}

@Composable
private fun ScreenColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = VezunaTokens.GutterNarrow),
        verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
    ) {
        content()
        Spacer(Modifier.height(VezunaTokens.SpaceLg))
    }
}

@Composable
private fun Title(text: String) =
    Text(text, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })

/** Image 119: local work, no fake percentage; announced politely. */
@Composable
private fun Preparing(profileName: String) {
    ScreenColumn {
        Column(
            Modifier.fillMaxWidth().padding(top = VezunaTokens.SpaceLg).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd),
        ) {
            Image(painterResource(Res.drawable.ill_processing), contentDescription = null, modifier = Modifier.widthIn(max = 220.dp).fillMaxWidth())
            CircularProgressIndicator(Modifier.size(40.dp))
            Text(vitaText(L10nKey.EXPORT_PREPARING_TITLE), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Text(vitaText(L10nKey.EXPORT_PREPARING_BODY, profileName), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Text(vitaText(L10nKey.EXPORT_PREPARING_NOTE), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/** Image 122: nothing selected has content. Offers the review queue, a new selection, or a PDF that states its own empty scope. */
@Composable
private fun EmptyExport(ctx: RouteContext, data: ProfileData, options: ExportOptions, csv: Boolean, onCreateAnyway: () -> Unit) {
    val unreviewed = options.unreviewedCount(data)
    ScreenColumn {
        CategoryPanel(Category.RECORDS) {
            Image(painterResource(Res.drawable.ill_record_empty), contentDescription = null, modifier = Modifier.widthIn(max = 200.dp).fillMaxWidth().align(Alignment.CenterHorizontally))
            Text(vitaText(L10nKey.EXPORT_EMPTY_TITLE), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(if (unreviewed > 0) vitaPlural(L10nKey.EXPORT_EMPTY_UNREVIEWED, unreviewed) else vitaText(L10nKey.EXPORT_EMPTY_BODY), style = MaterialTheme.typography.bodyLarge)
            if (unreviewed > 0) {
                PrimaryButton(vitaText(L10nKey.EXPORT_REVIEW_IMPORTS), { ctx.navigate(AppScreen.ImportReview) }, Modifier.fillMaxWidth(), container = VezunaTokens.Pine, content = Color.White)
            }
            SecondaryButton(vitaText(L10nKey.EXPORT_CHANGE_SELECTION), { ctx.back() }, Modifier.fillMaxWidth())
        }
        if (!csv) TertiaryButton(vitaText(L10nKey.EXPORT_CREATE_ANYWAY), onCreateAnyway, Modifier.fillMaxWidth())
    }
}

/** Image 123: creation failed; nothing in the vault changed. */
@Composable
private fun ExportFailed(ctx: RouteContext, data: ProfileData, options: ExportOptions, csv: Boolean, reason: String?, onRetry: () -> Unit) {
    val name = DoctorExportContent.fileName(data.profile.displayName, if (csv) "lab-results" else options.family.slug, currentInstant(), if (csv) "csv" else "pdf")
    ScreenColumn {
        Title(vitaText(L10nKey.EXPORT_FAILED_TITLE))
        ErrorPanel(vitaText(L10nKey.EXPORT_FAILED_BODY), reason)
        GroupedRows {
            ListRow(name, supporting = vitaText(L10nKey.EXPORT_FILE_NAME), leadingIcon = if (csv) Res.drawable.ic_file_csv else Res.drawable.ic_file_pdf, category = Category.RECORDS)
            Counts(data, options)
        }
        PrimaryButton(vitaText(L10nKey.EXPORT_TRY_AGAIN), onRetry, Modifier.fillMaxWidth(), icon = Res.drawable.ic_refresh)
        SecondaryButton(vitaText(L10nKey.EXPORT_CHANGE_OPTIONS), { ctx.back() }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun Counts(data: ProfileData, options: ExportOptions) {
    ListRow(vitaText(L10nKey.EXPORT_TOTAL_CONFIRMED), trailing = { Text(ExportScope.confirmedCount(data, options.copy(includeLabsPdf = true)).toString(), style = MaterialTheme.typography.titleLarge) })
    ListRow(vitaText(L10nKey.EXPORT_TOTAL_EXCLUDED), trailing = { Text(options.unreviewedCount(data).toString(), style = MaterialTheme.typography.titleLarge) })
}

/** Assertive: a blocking failure next to the action that caused it, with what happened and what is safe. */
@Composable
private fun ErrorPanel(body: String, detail: String? = null) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(VezunaTokens.CardRadius)).background(MaterialTheme.colorScheme.surface)
            .border(2.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(VezunaTokens.CardRadius))
            .padding(VezunaTokens.SpaceMd)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
        verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceXs),
    ) {
        StatusTag(body, StatusKind.ERROR)
        detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

/**
 * Save and share for one ready file. Save success is announced; cancel says nothing; a failed save keeps the file
 * and shows image 127's recoverable state. Share hands off to the OS, which owns what happens next.
 */
@Composable
private fun Delivery(ctx: RouteContext, file: ReadyFile, saveLabel: L10nKey) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var shareFailed by remember { mutableStateOf(false) }
    val save = ExportPlatform.save
    val share = ExportPlatform.share
    if (save == null && share == null) {
        StatusTag(vitaText(L10nKey.EXPORT_DELIVERY_UNAVAILABLE), StatusKind.INFO)
        return
    }
    if (saveFailed) {
        Title(vitaText(L10nKey.EXPORT_SAVE_FAILED_TITLE))
        ErrorPanel(vitaText(L10nKey.EXPORT_SAVE_FAILED_BODY))
        GroupedRows {
            ListRow(file.fileName, supporting = vitaText(L10nKey.EXPORT_READY_TO_SAVE), leadingIcon = Res.drawable.ic_file_pdf.takeIf { file.mimeType == ExportPlatform.PDF } ?: Res.drawable.ic_file_csv, category = Category.RECORDS)
        }
    }
    save?.let { handler ->
        PrimaryButton(
            vitaText(if (saveFailed) L10nKey.EXPORT_SAVE_AGAIN else saveLabel),
            {
                saving = true
                shareFailed = false
                scope.launch {
                    try {
                        val outcome = handler(file.fileName, file.mimeType, file.bytes)
                        saveFailed = false
                        if (outcome == SaveOutcome.SAVED) ctx.controller.announce(message(L10nKey.EXPORT_SAVED, file.fileName))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        saveFailed = true
                    } finally {
                        saving = false
                    }
                }
            },
            Modifier.fillMaxWidth(),
            loading = saving,
            icon = if (saveFailed) Res.drawable.ic_refresh else Res.drawable.ic_download,
        )
    }
    share?.let { handler ->
        SecondaryButton(
            vitaText(L10nKey.EXPORT_SHARE),
            {
                shareFailed = false
                scope.launch {
                    try {
                        handler(file.fileName, file.mimeType, file.bytes)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        shareFailed = true
                    }
                }
            },
            Modifier.fillMaxWidth(),
            enabled = !saving,
            icon = Res.drawable.ic_share,
        )
    }
    if (shareFailed) ErrorPanel(vitaText(L10nKey.EXPORT_SHARE_FAILED))
}

/** Images 118, 120 and 138: actions first (reachable before the pages), then the rendered pages of the saved bytes. */
@Composable
private fun PdfReady(ctx: RouteContext, data: ProfileData, options: ExportOptions, file: ReadyFile) {
    var pageCount by remember(file) { mutableStateOf<Int?>(null) }
    ScreenColumn {
        Title(vitaText(L10nKey.EXPORT_READY_TITLE))
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(file.fileName, style = MaterialTheme.typography.titleLarge)
            pageCount?.let { Text(vitaPlural(L10nKey.EXPORT_PAGES, it), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Delivery(ctx, file, L10nKey.EXPORT_SAVE_PDF)
        TertiaryButton(vitaText(L10nKey.EXPORT_EDIT_OPTIONS), { ctx.back() }, Modifier.fillMaxWidth())
        StatusTag(vitaText(L10nKey.EXPORT_HEALTH_NOTE), StatusKind.INFO)
        ScopeSummary(data, options)
        PdfPages(file.bytes) { pageCount = it }
        Text(vitaText(L10nKey.EXPORT_PDF_ACCESS_NOTE), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Every page of [bytes], rendered by the platform PDF engine (PdfRenderer, PDFKit); the paper stays white in dark mode. */
@Composable
private fun PdfPages(bytes: ByteArray, onPageCount: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val first by produceState<Result<app.vezuna.ui.records.RenderedPdfPage?>?>(null, bytes, widthPx) {
            value = runCatching { renderPdfPage(bytes, 0, widthPx) }
        }
        val result = first
        if (result == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
            return@BoxWithConstraints
        }
        val page = result.getOrNull()
        if (page == null) {
            Text(vitaText(L10nKey.EXPORT_PREVIEW_UNAVAILABLE), style = MaterialTheme.typography.bodyMedium)
            return@BoxWithConstraints
        }
        LaunchedEffect(page.pageCount) { onPageCount(page.pageCount) }
        Column(verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd)) {
            PageImage(page.image, 1, page.pageCount)
            (1 until page.pageCount).forEach { index ->
                val rendered by produceState<app.vezuna.ui.records.RenderedPdfPage?>(null, bytes, widthPx, index) {
                    value = runCatching { renderPdfPage(bytes, index, widthPx) }.getOrNull()
                }
                rendered?.let { PageImage(it.image, index + 1, page.pageCount) } ?: CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun PageImage(image: androidx.compose.ui.graphics.ImageBitmap, number: Int, total: Int) {
    val description = vitaText(L10nKey.EXPORT_PREVIEW_PAGE, number, total)
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.FillWidth,
        modifier = Modifier.fillMaxWidth().background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
            .semantics { contentDescription = description },
    )
}

/** Image 121: counts, then the first rows read back from the CSV text that will be saved. */
@Composable
private fun CsvReady(ctx: RouteContext, data: ProfileData, options: ExportOptions, file: ReadyFile, csv: String) {
    val rows = remember(csv) { CsvPreview.rows(csv) }
    val shown = rows.take(CSV_PREVIEW_ROWS)
    ScreenColumn {
        Title(vitaText(L10nKey.EXPORT_CSV_TITLE))
        CategoryPanel(Category.RECORDS) {
            Text(vitaText(L10nKey.EXPORT_CSV_HEADING), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(file.fileName, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceLg)) {
                CountBlock(rows.size, vitaText(L10nKey.EXPORT_TOTAL_CONFIRMED), Modifier.weight(1f))
                CountBlock(options.unreviewedCount(data), vitaText(L10nKey.EXPORT_TOTAL_EXCLUDED), Modifier.weight(1f))
            }
        }
        Delivery(ctx, file, L10nKey.EXPORT_SAVE_CSV)
        TertiaryButton(vitaText(L10nKey.EXPORT_EDIT_OPTIONS), { ctx.back() }, Modifier.fillMaxWidth())
        Text(vitaText(L10nKey.EXPORT_CSV_PREVIEW), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        GroupedRows {
            shown.forEach { row ->
                val date = runCatching { formatUiDate(Instant.parse(row.collectedAt)) }.getOrDefault(row.collectedAt)
                ListRow(row.test, supporting = listOf(date, "${row.value} ${row.unit}".trim()).joinToString(" · "))
            }
        }
        if (rows.size > shown.size) Text(vitaText(L10nKey.EXPORT_CSV_MORE, shown.size, rows.size), style = MaterialTheme.typography.bodyMedium)
        StatusTag(vitaText(L10nKey.EXPORT_HEALTH_NOTE), StatusKind.INFO)
    }
}

@Composable
private fun CountBlock(count: Int, label: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(count.toString(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private const val CSV_PREVIEW_ROWS = 20
