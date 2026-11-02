package app.vezuna.ui.more

import app.vezuna.ui.components.clearOfNavigationBar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.vezuna.ui.components.decorativeArtShown
import app.vezuna.ui.components.CategoryPanel
import vezuna.shared.generated.resources.ic_chevron_right
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import app.vezuna.ui.AppScreen
import app.vezuna.ui.Category
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.FocusedTaskScaffold
import app.vezuna.ui.components.GroupedRows
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.components.SectionHeading
import app.vezuna.ui.nav.NativeAction
import app.vezuna.ui.nav.RouteContext
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_chat
import vezuna.shared.generated.resources.ic_code
import vezuna.shared.generated.resources.ic_connect
import vezuna.shared.generated.resources.ic_error
import vezuna.shared.generated.resources.ic_external_link
import vezuna.shared.generated.resources.ic_file_text
import vezuna.shared.generated.resources.ic_help
import vezuna.shared.generated.resources.ic_info
import vezuna.shared.generated.resources.ic_language
import vezuna.shared.generated.resources.ic_lock
import vezuna.shared.generated.resources.ic_moon
import vezuna.shared.generated.resources.ic_motion
import vezuna.shared.generated.resources.ic_share
import vezuna.shared.generated.resources.ic_storage
import vezuna.shared.generated.resources.ic_text_size
import vezuna.shared.generated.resources.ic_upload
import vezuna.shared.generated.resources.ic_users

@Composable
private fun PushedPage(ctx: RouteContext, title: String, content: @Composable () -> Unit) =
    FocusedTaskScaffold(title = title, onBack = ctx::back) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).clearOfNavigationBar().padding(horizontal = VezunaTokens.GutterNarrow),
            verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceLg),
        ) {
            Spacer(Modifier.height(VezunaTokens.SpaceSm))
            content()
            Spacer(Modifier.height(VezunaTokens.SpaceLg))
        }
    }

@Composable
private fun Body(text: String) =
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun LinkRow(title: String, icon: DrawableResource, category: Category, url: String, supporting: String? = null) {
    val open = MorePlatform.openUrl ?: return
    val hint = vitaText(L10nKey.ABOUT_OPENS_BROWSER)
    ListRow(
        title = title,
        supporting = supporting,
        onClick = { open(url) },
        leadingIcon = icon,
        category = category,
        showChevron = false,
        trailing = { Icon(painterResource(Res.drawable.ic_external_link), null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = Modifier.semantics { stateDescription = hint },
    )
}

@Composable
fun MoreSettingsPage(ctx: RouteContext) = PushedPage(ctx, vitaText(L10nKey.NAV_MORE)) { MoreRows(ctx) }

@Composable
fun AppearanceScreen(ctx: RouteContext) = PushedPage(ctx, vitaText(L10nKey.MORE_APPEARANCE)) {
    val modes = listOf(
        ThemeMode.SYSTEM to L10nKey.THEME_SYSTEM,
        ThemeMode.LIGHT to L10nKey.THEME_LIGHT,
        ThemeMode.DARK to L10nKey.THEME_DARK,
    )
    Column(verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
        SectionHeading(vitaText(L10nKey.THEME_HEADING))
        Body(vitaText(L10nKey.THEME_HINT))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
            modes.forEach { (mode, label) ->
                val selected = MorePlatform.themeMode == mode
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { MorePlatform.chooseTheme(mode) })
                        .heightIn(min = VezunaTokens.TouchTarget),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd),
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Text(vitaText(label), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
    GroupedRows {
        ListRow(vitaText(L10nKey.APPEARANCE_TEXT_SIZE), supporting = vitaText(L10nKey.APPEARANCE_FOLLOWS_DEVICE), leadingIcon = Res.drawable.ic_text_size, category = Category.RECORDS)
        ListRow(vitaText(L10nKey.APPEARANCE_REDUCED_MOTION), supporting = vitaText(L10nKey.APPEARANCE_FOLLOWS_DEVICE), leadingIcon = Res.drawable.ic_motion, category = Category.TRENDS)
    }
}


@Composable
fun LanguageScreen(ctx: RouteContext) = PushedPage(ctx, vitaText(L10nKey.MORE_LANGUAGE)) {
    Body(vitaText(L10nKey.LANGUAGE_EXPLANATION))
    val open = MorePlatform.openLanguageSettings
    val hint = vitaText(L10nKey.LANGUAGE_OPEN_HINT)
    SecondaryButton(
        text = vitaText(L10nKey.LANGUAGE_OPEN),
        onClick = { open?.invoke() },
        enabled = open != null,
        icon = Res.drawable.ic_language,
        modifier = Modifier.fillMaxWidth().semantics { stateDescription = hint },
    )
    Body(vitaText(L10nKey.LANGUAGE_EXPORT_NOTE))
}

@Composable
fun AboutScreen(ctx: RouteContext) = PushedPage(ctx, vitaText(L10nKey.MORE_ABOUT)) {
    Column(verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceXs)) {
        Text(vitaText(L10nKey.ABOUT_TAGLINE), style = MaterialTheme.typography.titleLarge)
        Body(vitaText(L10nKey.ABOUT_OPEN_SOURCE))
    }
    Column(verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
        LinkRow(vitaText(L10nKey.ABOUT_SOURCE_CODE), Res.drawable.ic_code, Category.RECORDS, VezunaLinks.REPOSITORY)
        LinkRow(vitaText(L10nKey.ABOUT_LICENSE), Res.drawable.ic_file_text, Category.ACTIVITY, VezunaLinks.LICENSE)
        LinkRow(vitaText(L10nKey.ABOUT_CONTRIBUTORS), Res.drawable.ic_users, Category.CARE, VezunaLinks.CONTRIBUTORS)
        if (NativeAction.NOTICES in ctx.nativeActions) {
            ListRow(vitaText(L10nKey.MORE_NOTICES), onClick = { ctx.navigate(AppScreen.Notices) }, leadingIcon = Res.drawable.ic_file_text, category = Category.TRENDS)
        }
        if (NativeAction.PRIVACY in ctx.nativeActions) {
            ListRow(vitaText(L10nKey.MORE_PRIVACY_POLICY), onClick = { ctx.navigate(AppScreen.PrivacyPolicy) }, leadingIcon = Res.drawable.ic_lock, category = Category.RECORDS)
        }
    }
    val version = MorePlatform.versionName?.let { name ->
        MorePlatform.buildNumber?.let { vitaText(L10nKey.ABOUT_VERSION_VALUE, name, it) } ?: name
    } ?: vitaText(L10nKey.ABOUT_VERSION_UNKNOWN)
    GroupedRows { ListRow(vitaText(L10nKey.ABOUT_VERSION), supporting = version, leadingIcon = Res.drawable.ic_info) }
    Column(verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
        SectionHeading(vitaText(L10nKey.ABOUT_DATA_DELETION_TITLE))
        Body(vitaText(L10nKey.ABOUT_DATA_DELETION_BODY))
    }
}

@Composable
private fun LegalScreen(ctx: RouteContext, title: String, body: String, action: NativeAction) = PushedPage(ctx, title) {
    Body(body)
    if (action in ctx.nativeActions) {
        val hint = vitaText(L10nKey.LEGAL_OPEN_HINT)
        SecondaryButton(
            text = title,
            onClick = { ctx.nativeActions.perform(action) },
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = hint },
        )
    } else {
        Body(vitaText(L10nKey.LEGAL_UNAVAILABLE))
    }
}

@Composable
fun PrivacyPolicyScreen(ctx: RouteContext) =
    LegalScreen(ctx, vitaText(L10nKey.MORE_PRIVACY_POLICY), vitaText(L10nKey.LEGAL_PRIVACY_BODY), NativeAction.PRIVACY)

@Composable
fun NoticesScreen(ctx: RouteContext) =
    LegalScreen(ctx, vitaText(L10nKey.MORE_NOTICES), vitaText(L10nKey.LEGAL_NOTICES_BODY), NativeAction.NOTICES)

@Composable
fun HelpScreen(ctx: RouteContext) = PushedPage(ctx, vitaText(L10nKey.MORE_HELP)) {
    GroupedRows {
        ListRow(vitaText(L10nKey.HELP_IMPORT), onClick = { ctx.navigate(AppScreen.ImportSource) }, supporting = vitaText(L10nKey.HELP_IMPORT_SUB), leadingIcon = Res.drawable.ic_upload, category = Category.RECORDS)
        ListRow(vitaText(L10nKey.HELP_BACKUP), onClick = { ctx.navigate(AppScreen.Backups) }, supporting = vitaText(L10nKey.HELP_BACKUP_SUB), leadingIcon = Res.drawable.ic_storage, category = Category.CARE)
        ListRow(vitaText(L10nKey.HELP_EXPORT), onClick = { ctx.navigate(AppScreen.ExportHub) }, supporting = vitaText(L10nKey.HELP_EXPORT_SUB), leadingIcon = Res.drawable.ic_share, category = Category.TRENDS)
    }
    GroupedRows {
        LinkRow(vitaText(L10nKey.HELP_REPORT), Res.drawable.ic_error, Category.ACTIVITY, VezunaLinks.NEW_ISSUE, vitaText(L10nKey.HELP_REPORT_SUB))
        LinkRow(vitaText(L10nKey.HELP_CONTRIBUTE), Res.drawable.ic_code, Category.RECORDS, VezunaLinks.REPOSITORY, vitaText(L10nKey.HELP_CONTRIBUTE_SUB))
    }
    Body(vitaText(L10nKey.HELP_NO_ACCOUNT))
}

@Composable
private fun PanelRow(title: String, supporting: String, icon: DrawableResource, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = VezunaTokens.RowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd),
    ) {
        // At very large text the icon tile gives its width back so a long name ("Exportaciones") never breaks mid-word.
        if (decorativeArtShown()) Box(Modifier.size(40.dp).background(Color.White.copy(alpha = 0.45f), RoundedCornerShape(VezunaTokens.ControlRadius)), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(supporting, style = MaterialTheme.typography.bodySmall)
        }
        Icon(painterResource(Res.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun MoreRows(ctx: RouteContext) {
    val nav = ctx::navigate
    CategoryPanel(Category.RECORDS) {
        PanelRow(vitaText(L10nKey.MORE_PROFILES), vitaText(L10nKey.MORE_PROFILES_SUB), Res.drawable.ic_users) { nav(AppScreen.Profiles) }
        PanelRow(vitaText(L10nKey.MORE_EXPORTS), vitaText(L10nKey.MORE_EXPORTS_SUB), Res.drawable.ic_share) { nav(AppScreen.ExportHub) }
        PanelRow(vitaText(L10nKey.MORE_BACKUPS), vitaText(L10nKey.MORE_BACKUPS_SUB), Res.drawable.ic_storage) { nav(AppScreen.Backups) }
    }
    CategoryPanel(Category.CARE) {
        PanelRow(vitaText(L10nKey.MORE_PRIVACY_LOCK), vitaText(L10nKey.MORE_PRIVACY_LOCK_SUB), Res.drawable.ic_lock) { nav(AppScreen.DataPrivacy) }
    }
    CategoryPanel(Category.ACTIVITY) {
        PanelRow(vitaText(L10nKey.MORE_CONNECTIONS), vitaText(L10nKey.MORE_CONNECTIONS_SUB), Res.drawable.ic_connect) { nav(AppScreen.Connections) }
    }
    CategoryPanel(Category.TRENDS) {
        PanelRow(vitaText(L10nKey.MORE_ASSISTANT_MODELS), vitaText(L10nKey.MORE_ASSISTANT_MODELS_SUB), Res.drawable.ic_chat) { nav(AppScreen.AiChat) }
    }
    GroupedRows {
        ListRow(vitaText(L10nKey.MORE_APPEARANCE), onClick = { nav(AppScreen.Appearance) }, supporting = vitaText(L10nKey.MORE_APPEARANCE_SUB), leadingIcon = Res.drawable.ic_moon, category = Category.RECORDS)
        ListRow(vitaText(L10nKey.MORE_LANGUAGE), onClick = { nav(AppScreen.Language) }, supporting = vitaText(L10nKey.MORE_LANGUAGE_SUB), leadingIcon = Res.drawable.ic_language, category = Category.RECORDS)
    }
    GroupedRows {
        ListRow(vitaText(L10nKey.MORE_HELP), onClick = { nav(AppScreen.Help) }, supporting = vitaText(L10nKey.MORE_HELP_SUB), leadingIcon = Res.drawable.ic_help, category = Category.RECORDS)
        ListRow(vitaText(L10nKey.MORE_ABOUT), onClick = { nav(AppScreen.About) }, supporting = vitaText(L10nKey.MORE_ABOUT_SUB), leadingIcon = Res.drawable.ic_info, category = Category.RECORDS)
    }
}
