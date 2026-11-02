package app.vezuna.ui.records

import app.vezuna.ui.components.clearOfNavigationBar
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import app.vezuna.ui.AppScreen
import app.vezuna.ui.SourceFileRead
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.EmptyState
import app.vezuna.ui.components.FocusedTaskScaffold
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.ProgressState
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.nav.RouteContext
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_minus
import vezuna.shared.generated.resources.ic_plus
import vezuna.shared.generated.resources.ill_file_error

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 4f
private const val ZOOM_STEP = 0.5f

/**
 * The report's original file, decrypted from the vault on this device only (image 020). Without a stored file
 * the screen says so and never implies an attachment exists (image 021).
 */
@Composable
internal fun SourceViewerScreen(ctx: RouteContext, documentId: String) =
    FocusedTaskScaffold(title = vitaText(L10nKey.SOURCE_TITLE), onBack = ctx::back) {
        val document = ctx.state.data?.documents?.firstOrNull { it.documentId == documentId }
        var read by remember(documentId) { mutableStateOf<SourceFileRead?>(null) }
        LaunchedEffect(documentId) { read = ctx.controller.readSourceFile(documentId) }
        when (val result = read) {
            null -> ProgressState(vitaText(L10nKey.SOURCE_OPENING))
            is SourceFileRead.Available -> SourcePages(result, document?.displayName.orEmpty(), onFailed = { read = SourceFileRead.Unreadable })
            SourceFileRead.Missing -> SourceUnavailable(ctx, vitaText(L10nKey.SOURCE_MISSING_BODY), offerReimport = document != null)
            SourceFileRead.Unreadable -> SourceUnavailable(ctx, vitaText(L10nKey.SOURCE_UNREADABLE_BODY), offerReimport = false)
        }
    }

@Composable
private fun SourceUnavailable(ctx: RouteContext, body: String, offerReimport: Boolean) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).clearOfNavigationBar().padding(horizontal = VezunaTokens.Gutter),
        verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd),
    ) {
        EmptyState(Res.drawable.ill_file_error, vitaText(L10nKey.SOURCE_MISSING_TITLE), body)
        if (offerReimport) {
            Text(vitaText(L10nKey.SOURCE_MISSING_REIMPORT_HINT), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PrimaryButton(vitaText(L10nKey.SOURCE_RETURN), ctx::back, Modifier.fillMaxWidth())
        if (offerReimport) SecondaryButton(vitaText(L10nKey.SOURCE_REIMPORT), { ctx.navigate(AppScreen.ImportSource) }, Modifier.fillMaxWidth())
    }
}

private fun isPdf(mediaType: String) = mediaType.equals("application/pdf", ignoreCase = true)

/** One page at a time (PDF pages rendered on demand, photos decoded), with zoom buttons, pinch and a page picker. */
@Composable
private fun SourcePages(file: SourceFileRead.Available, fileName: String, onFailed: () -> Unit) {
    var pageIndex by remember { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(if (isPdf(file.mediaType)) 1 else file.pages.size) }
    var zoom by remember { mutableStateOf(MIN_ZOOM) }
    // Keeps the last render of the page on screen while a sharper one is produced after zooming.
    var shown by remember { mutableStateOf<Pair<Int, ImageBitmap>?>(null) }
    var unsupported by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val baseWidth = maxWidth - VezunaTokens.Gutter * 2
        // PDF pages re-render per zoom bucket (1x..4x of the 2x base), capped by MAX_PAGE_PX, so small values stay sharp.
        val bucket = if (isPdf(file.mediaType)) ceil(zoom).toInt().coerceIn(1, MAX_ZOOM.toInt()) else 1
        val renderPx = with(LocalDensity.current) { (baseWidth.toPx() * 2 * bucket).toInt().coerceAtMost(MAX_PAGE_PX) }
        // Keyed on the clamped size: buckets that clamp to the same size do not render again.
        LaunchedEffect(pageIndex, renderPx) {
            val page = try {
                if (isPdf(file.mediaType)) {
                    renderPdfPage(file.pages.first(), pageIndex, renderPx)?.also { pageCount = it.pageCount }?.image
                } else {
                    withContext(Dispatchers.Default) { file.pages[pageIndex].decodeToImageBitmap() }
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // A failed sharper re-render keeps the page already on screen.
                if (shown?.first != pageIndex) onFailed()
                return@LaunchedEffect
            }
            if (page == null) unsupported = true else shown = pageIndex to page
        }
        if (unsupported) {
            EmptyState(Res.drawable.ill_file_error, vitaText(L10nKey.SOURCE_UNSUPPORTED_TITLE), vitaText(L10nKey.SOURCE_UNSUPPORTED_BODY), Modifier.padding(VezunaTokens.Gutter))
            return@BoxWithConstraints
        }
        Column(Modifier.fillMaxSize().clearOfNavigationBar()) {
            Text(
                vitaText(L10nKey.SOURCE_PAGE_OF, pageIndex + 1, pageCount),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = VezunaTokens.Gutter).semantics { liveRegion = LiveRegionMode.Polite },
            )
            val transform = rememberTransformableState { change, _, _ -> zoom = (zoom * change).coerceIn(MIN_ZOOM, MAX_ZOOM) }
            val pageDescription = vitaText(L10nKey.SOURCE_PAGE_IMAGE, pageIndex + 1, pageCount, fileName)
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(VezunaTokens.SpaceMd).transformable(transform, canPan = { false })
                    .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val current = shown?.takeIf { it.first == pageIndex }?.second
                if (current == null) {
                    ProgressState(vitaText(L10nKey.SOURCE_OPENING), Modifier.width(baseWidth))
                } else {
                    Image(
                        current, contentDescription = pageDescription, contentScale = ContentScale.FillWidth,
                        modifier = Modifier.width(baseWidth * zoom).background(Color.White),
                    )
                }
            }
            ZoomControls(zoom) { zoom = it }
            if (pageCount > 1) PagePicker(pageIndex, pageCount) { pageIndex = it }
        }
    }
}

@Composable
private fun ZoomControls(zoom: Float, onZoom: (Float) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = VezunaTokens.Gutter),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton({ onZoom((zoom - ZOOM_STEP).coerceAtLeast(MIN_ZOOM)) }, enabled = zoom > MIN_ZOOM, modifier = Modifier.size(VezunaTokens.TouchTarget)) {
            Icon(painterResource(Res.drawable.ic_minus), contentDescription = vitaText(L10nKey.SOURCE_ZOOM_OUT))
        }
        Text("${(zoom * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = VezunaTokens.SpaceMd))
        IconButton({ onZoom((zoom + ZOOM_STEP).coerceAtMost(MAX_ZOOM)) }, enabled = zoom < MAX_ZOOM, modifier = Modifier.size(VezunaTokens.TouchTarget)) {
            Icon(painterResource(Res.drawable.ic_plus), contentDescription = vitaText(L10nKey.SOURCE_ZOOM_IN))
        }
    }
}

@Composable
private fun PagePicker(selected: Int, count: Int, onSelect: (Int) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = VezunaTokens.Gutter, vertical = VezunaTokens.SpaceSm),
        horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm, Alignment.CenterHorizontally),
    ) {
        repeat(count) { index ->
            val label = vitaText(L10nKey.SOURCE_PAGE_LABEL, index + 1)
            val modifier = Modifier.semantics { contentDescription = label; this.selected = index == selected }
            val shape = RoundedCornerShape(VezunaTokens.ControlRadius)
            if (index == selected) {
                Button({ onSelect(index) }, modifier, shape = shape) { Text("${index + 1}") }
            } else {
                OutlinedButton({ onSelect(index) }, modifier, shape = shape, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) { Text("${index + 1}") }
            }
        }
    }
}
