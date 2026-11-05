package app.vezuna.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import app.vezuna.ui.VezunaTokens
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

private val ButtonShape = RoundedCornerShape(VezunaTokens.ControlRadius)
private val ButtonPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)

@Composable
private fun RowScope.ButtonContent(text: String, icon: DrawableResource?, loading: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
            icon != null -> Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
        }
        Text(if (loading) vitaText(L10nKey.PLEASE_WAIT) else text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/**
 * The one filled action of a decision area. While [loading] it stays readable, says so and ignores taps, so a
 * slow save cannot be submitted twice. On a category panel pass [container] = Pine and [content] = White.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: DrawableResource? = null,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
) {
    val busy = if (loading) vitaText(L10nKey.PLEASE_WAIT) else null
    Button(
        onClick = { if (!loading) onClick() },
        enabled = enabled,
        modifier = modifier.heightIn(min = VezunaTokens.RowHeight).semantics { busy?.let { stateDescription = it } },
        shape = ButtonShape,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            // Disabled keeps a readable label: muted text on the subtle surface (AA in both themes, see check_brand.py).
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) { ButtonContent(text, icon, loading) }
}

/** Secondary action: outlined, never competing with the filled one. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: DrawableResource? = null,
) {
    val category = onCategoryPanel()
    OutlinedButton(
        onClick = { if (!loading) onClick() },
        enabled = enabled,
        modifier = modifier.heightIn(min = VezunaTokens.RowHeight),
        shape = ButtonShape,
        contentPadding = ButtonPadding,
        border = BorderStroke(1.dp, if (enabled && category) LocalContentColor.current else if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (category) LocalContentColor.current else MaterialTheme.colorScheme.primary,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) { ButtonContent(text, icon, loading) }
}

/** Low-emphasis action, still at least 48dp tall. */
@Composable
fun TertiaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = if (onCategoryPanel()) LocalContentColor.current else MaterialTheme.colorScheme.primary) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = VezunaTokens.TouchTarget)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) color else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Destructive action. The label names the verb and the target ("Delete Lipid panel"). */
@Composable
fun DestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = { if (!loading) onClick() },
        enabled = enabled,
        modifier = modifier.heightIn(min = VezunaTokens.RowHeight),
        shape = ButtonShape,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) { ButtonContent(text, null, loading) }
}
