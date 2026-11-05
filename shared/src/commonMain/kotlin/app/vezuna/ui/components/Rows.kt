package app.vezuna.ui.components

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vezuna.ui.Category
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.vezuna
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_chevron_right

/** Section heading above a group; announced as a heading. */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(top = VezunaTokens.SpaceSm).semantics { heading() },
    )
}

/**
 * Rows on one opaque card. The 1dp gaps show the divider color behind the rows, so rows need no
 * separators of their own. Put [ListRow]s (or anything with an opaque background) inside.
 */
@Composable
fun GroupedRows(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
        title?.let { SectionHeading(it) }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(VezunaTokens.CardRadius)).background(MaterialTheme.vezuna.divider),
            verticalArrangement = Arrangement.spacedBy(1.dp),
            content = content,
        )
    }
}

/**
 * One tappable row, at least 52dp tall. [leadingIcon] sits on a tinted tile when [category] is set.
 * Without [onClick] the row is static text.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    supporting: String? = null,
    leadingIcon: DrawableResource? = null,
    category: Category? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    enabled: Boolean = true,
    /** Puts a wide [trailing] tag under the text from font scale 1.3 so the name keeps its width. */
    stackTrailing: Boolean = false,
) {
    val stacked = stackTrailing && trailing != null && LocalDensity.current.fontScale >= 1.3f
    // One focus stop per row: clickable merges its children; a static row merges explicitly.
    val clickable = if (onClick != null) {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    } else {
        Modifier.semantics(mergeDescendants = true) {}
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .then(clickable)
            .heightIn(min = VezunaTokens.RowHeight)
            .padding(horizontal = VezunaTokens.SpaceMd, vertical = VezunaTokens.SpaceSmd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd),
    ) {
        // At very large text the decorative icon gives its width back so long names do not break mid-word.
        when {
            leading != null -> leading()
            leadingIcon != null && !decorativeArtShown() -> Unit
            leadingIcon != null && category != null -> Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(VezunaTokens.ControlRadius)).background(category.fill),
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(leadingIcon), contentDescription = null, tint = category.onFill, modifier = Modifier.size(22.dp)) }
            leadingIcon != null -> Icon(painterResource(leadingIcon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (stacked) Box(Modifier.padding(top = VezunaTokens.SpaceXs)) { trailing?.invoke() }
        }
        if (!stacked) trailing?.invoke()
        if (showChevron) {
            Icon(painterResource(Res.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Substantial colored panel that identifies a kind of content (category color, never a health status).
 * Content uses the category ink; keep clinical values on opaque surfaces inside or below it.
 */
/** Corner and header art hides from this text scale on, so names and values reflow instead of breaking mid-word. */
internal const val ART_MAX_FONT_SCALE = 1.5f

/** False at very large text sizes: decorative art then gives its space back to the text (WCAG reflow). */
@Composable
fun decorativeArtShown(): Boolean = LocalDensity.current.fontScale < ART_MAX_FONT_SCALE

/** End padding that keeps text clear of a panel's corner art; none when [decorativeArtShown] is false. */
@Composable
fun Modifier.clearOfArt(end: Dp = 96.dp): Modifier = if (decorativeArtShown()) padding(end = end) else this

/**
 * Display-size titles (36sp) stop growing at [max] times their size so a single long word ("Tendencias") fits a
 * 320dp phone instead of breaking mid-word; they are already far above body size. Body text is never capped.
 */
@Composable
fun TextStyle.cappedScale(max: Float = 1.3f): TextStyle {
    val scale = LocalDensity.current.fontScale
    return if (scale > max) copy(fontSize = fontSize * (max / scale), lineHeight = lineHeight * (max / scale)) else this
}

/** Single-word labels (tabs, segments) shrink to fit one line instead of breaking mid-word at large text sizes. */
@Composable
fun singleWordAutoSize(maxFontSize: TextUnit): TextAutoSize =
    TextAutoSize.StepBased(minFontSize = minOf(10.sp, maxFontSize), maxFontSize = maxFontSize)

@Composable
fun CategoryPanel(
    category: Category,
    modifier: Modifier = Modifier,
    illustration: DrawableResource? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(VezunaTokens.CardRadius)
    val body: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth()) {
            illustration?.takeIf { decorativeArtShown() }?.let {
                Image(painterResource(it), contentDescription = null, modifier = Modifier.align(Alignment.TopEnd).size(width = 120.dp, height = 90.dp))
            }
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm), content = content)
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = category.fill, contentColor = category.onFill) { body() }
    } else {
        Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = category.fill, contentColor = category.onFill) { body() }
    }
}
