package app.vezuna.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.vita
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_check_circle
import vezuna.shared.generated.resources.ic_close
import vezuna.shared.generated.resources.ic_error
import vezuna.shared.generated.resources.ic_info
import vezuna.shared.generated.resources.ic_warning

/** Local tabs or a small exclusive choice. Each option keeps its full label. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentedControl(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                modifier = Modifier.heightIn(min = VezunaTokens.TouchTarget),
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

/** History windows for charts and lists. [All] is the default for sparse histories. */
enum class HistoryRange(val months: Int?, val shortKey: L10nKey, val labelKey: L10nKey) {
    THREE_MONTHS(3, L10nKey.RANGE_3M, L10nKey.RANGE_3M_LABEL),
    SIX_MONTHS(6, L10nKey.RANGE_6M, L10nKey.RANGE_6M_LABEL),
    ONE_YEAR(12, L10nKey.RANGE_1Y, L10nKey.RANGE_1Y_LABEL),
    ALL(null, L10nKey.RANGE_ALL, L10nKey.RANGE_ALL_LABEL),
}

/** 3M / 6M / 1Y / All as one selectable group; screen readers hear the full period name. */
@Composable
fun RangeChips(selected: HistoryRange, onSelect: (HistoryRange) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
        HistoryRange.entries.forEach { range ->
            val isSelected = range == selected
            val label = vitaText(range.labelKey)
            Surface(
                shape = RoundedCornerShape(VezunaTokens.PillRadius),
                color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                contentColor = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .clip(RoundedCornerShape(VezunaTokens.PillRadius))
                    .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(range) }
                    .semantics { contentDescription = label },
            ) {
                Text(
                    vitaText(range.shortKey),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.heightIn(min = VezunaTokens.TouchTarget).padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }
        }
    }
}

enum class StatusKind { NEEDS_REVIEW, CONFIRMED, WARNING, ERROR, INFO }

/**
 * Status as icon plus text, never color alone: Needs review, Confirmed, Above report range... The text carries
 * the meaning; color only supports it and never implies a diagnosis.
 */
@Composable
fun StatusTag(text: String, kind: StatusKind, modifier: Modifier = Modifier) {
    val (color, icon) = statusStyle(kind)
    Row(
        modifier
            .clip(RoundedCornerShape(VezunaTokens.PillRadius))
            .background(color.copy(alpha = 0.12f))
            .heightIn(min = 36.dp)
            .padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp)
            .clearAndSetSemantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
private fun statusStyle(kind: StatusKind): Pair<Color, DrawableResource> = when (kind) {
    StatusKind.NEEDS_REVIEW, StatusKind.WARNING -> MaterialTheme.vita.warning to Res.drawable.ic_warning
    StatusKind.CONFIRMED -> MaterialTheme.vita.success to Res.drawable.ic_check_circle
    StatusKind.ERROR -> MaterialTheme.colorScheme.error to Res.drawable.ic_error
    StatusKind.INFO -> MaterialTheme.colorScheme.primary to Res.drawable.ic_info
}

/**
 * Text field with a persistent label, an optional hint and a local error. The error never clears the
 * entered value; the keyboard follows [keyboardType]. A clear button appears once there is text.
 */
@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    enabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            enabled = enabled,
            isError = error != null,
            singleLine = singleLine,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(VezunaTokens.ControlRadius),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
            ),
            trailingIcon = when {
                error != null -> {
                    { Icon(painterResource(Res.drawable.ic_error), contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                }
                value.isNotEmpty() && enabled -> {
                    {
                        IconButton(onClick = { onValueChange("") }) {
                            Icon(painterResource(Res.drawable.ic_close), contentDescription = vitaText(L10nKey.CLEAR_FIELD))
                        }
                    }
                }
                else -> null
            },
            supportingText = (error ?: hint)?.let { text -> { Text(text) } },
            modifier = Modifier.fillMaxWidth().semantics { if (error != null) error(error) },
        )
    }
}
