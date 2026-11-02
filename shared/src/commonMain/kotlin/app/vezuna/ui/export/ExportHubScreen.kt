package app.vezuna.ui.export

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vezuna.export.ExportFamily
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import app.vezuna.ui.AppScreen
import app.vezuna.ui.Category
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.GroupedRows
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.ProfileAvatar
import app.vezuna.ui.components.SectionHeading
import app.vezuna.ui.nav.RouteContext
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_capsule
import vezuna.shared.generated.resources.ic_file_csv
import vezuna.shared.generated.resources.ic_file_text
import vezuna.shared.generated.resources.ic_lab
import vezuna.shared.generated.resources.ic_watch

internal class FamilyStyle(val title: L10nKey, val subtitle: L10nKey, val icon: DrawableResource, val category: Category)

internal fun ExportFamily.style(): FamilyStyle = when (this) {
    ExportFamily.HEALTH_SUMMARY -> FamilyStyle(L10nKey.EXPORT_FAMILY_SUMMARY, L10nKey.EXPORT_FAMILY_SUMMARY_SUB, Res.drawable.ic_file_text, Category.RECORDS)
    ExportFamily.LAB_HISTORY -> FamilyStyle(L10nKey.EXPORT_FAMILY_LABS, L10nKey.EXPORT_FAMILY_LABS_SUB, Res.drawable.ic_lab, Category.TRENDS)
    ExportFamily.MEDICATION_RECORD -> FamilyStyle(L10nKey.EXPORT_FAMILY_MEDS, L10nKey.EXPORT_FAMILY_MEDS_SUB, Res.drawable.ic_capsule, Category.CARE)
    ExportFamily.DEVICE_MEASUREMENTS -> FamilyStyle(L10nKey.EXPORT_FAMILY_DEVICE, L10nKey.EXPORT_FAMILY_DEVICE_SUB, Res.drawable.ic_watch, Category.ACTIVITY)
}

/** Image 115: the active profile, one of four PDF layouts, or the CSV spreadsheet. Export never depends on AI or a licence. */
@Composable
internal fun ExportHubScreen(ctx: RouteContext) {
    val data = ctx.state.data ?: return
    var family by rememberSaveable { mutableStateOf(ExportFamily.HEALTH_SUMMARY.name) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = VezunaTokens.GutterNarrow),
        verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
    ) {
        Text(vitaText(L10nKey.EXPORT_HUB_SUBTITLE, data.profile.displayName), style = MaterialTheme.typography.bodyLarge)
        GroupedRows {
            ListRow(data.profile.displayName, supporting = vitaText(L10nKey.EXPORT_HUB_PROFILE), leading = { ProfileAvatar(data.profile.displayName) })
        }
        SectionHeading(vitaText(L10nKey.EXPORT_HUB_LAYOUTS))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
            ExportFamily.entries.forEach { option ->
                FamilyOption(option, selected = family == option.name) { family = option.name }
            }
        }
        SectionHeading(vitaText(L10nKey.EXPORT_HUB_SPREADSHEET))
        GroupedRows {
            ListRow(
                vitaText(L10nKey.EXPORT_HUB_CSV),
                onClick = { ctx.navigate(AppScreen.ExportOptions(CSV_KIND)) },
                supporting = vitaText(L10nKey.EXPORT_HUB_CSV_SUB),
                leadingIcon = Res.drawable.ic_file_csv,
                category = Category.RECORDS,
            )
        }
        PrimaryButton(vitaText(L10nKey.EXPORT_CONTINUE), { ctx.navigate(AppScreen.ExportOptions(family)) }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(VezunaTokens.SpaceLg))
    }
}

/** A category-colored radio row; one focus stop announcing the layout, its description and the selected state. */
@Composable
private fun FamilyOption(family: ExportFamily, selected: Boolean, onSelect: () -> Unit) {
    val style = family.style()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(VezunaTokens.CardRadius))
            .background(style.category.fill)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = VezunaTokens.RowHeight)
            .padding(horizontal = VezunaTokens.SpaceMd, vertical = VezunaTokens.SpaceSmd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(VezunaTokens.ControlRadius)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            Icon(painterResource(style.icon), contentDescription = null, tint = style.category.onFill, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(vitaText(style.title), style = MaterialTheme.typography.titleMedium, color = style.category.onFill)
            Text(vitaText(style.subtitle), style = MaterialTheme.typography.bodyMedium, color = style.category.onFill)
        }
        // The row owns the click; the radio only shows the state.
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = style.category.onFill, unselectedColor = style.category.onFill))
    }
}
