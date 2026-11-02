package app.vezuna.ui.components

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
) {
    val clickable = if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier
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
        when {
            leading != null -> leading()
            leadingIcon != null && category != null -> Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(VezunaTokens.ControlRadius)).background(category.fill),
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(leadingIcon), contentDescription = null, tint = category.onFill, modifier = Modifier.size(22.dp)) }
            leadingIcon != null -> Icon(painterResource(leadingIcon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.invoke()
        if (showChevron) {
            Icon(painterResource(Res.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Substantial colored panel that identifies a kind of content (category color, never a health status).
 * Content uses the category ink; keep clinical values on opaque surfaces inside or below it.
 */
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
            illustration?.let {
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
