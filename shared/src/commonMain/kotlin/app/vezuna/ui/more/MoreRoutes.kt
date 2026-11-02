package app.vezuna.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import app.vezuna.ui.AppScreen
import app.vezuna.ui.Category
import app.vezuna.ui.SettingsScreen
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.GroupedRows
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.nav.LegacyPushed
import app.vezuna.ui.nav.NativeAction
import app.vezuna.ui.nav.RootScaffold
import app.vezuna.ui.nav.RouteContext
import org.jetbrains.compose.resources.DrawableResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_bell
import vezuna.shared.generated.resources.ic_chat
import vezuna.shared.generated.resources.ic_chip
import vezuna.shared.generated.resources.ic_code
import vezuna.shared.generated.resources.ic_connect
import vezuna.shared.generated.resources.ic_file_text
import vezuna.shared.generated.resources.ic_key
import vezuna.shared.generated.resources.ic_settings
import vezuna.shared.generated.resources.ic_share
import vezuna.shared.generated.resources.ic_upload

/**
 * Lane L7 (More hub, preferences, about, help). R0 ships a skeleton hub that keeps every existing entry
 * reachable: the assistant and models (formerly tabs), the current Settings screen, and each native action
 * the platform supports (formerly the Android Device menu and the iOS toolbar menu).
 */
object MoreRoutes {
    private val nativeRows: List<Triple<NativeAction, L10nKey, DrawableResource>> = listOf(
        Triple(NativeAction.IMPORT_SOURCE, L10nKey.IMPORT_LAB_REPORT, Res.drawable.ic_upload),
        Triple(NativeAction.EXPORT, L10nKey.MORE_EXPORT_SHARE, Res.drawable.ic_share),
        Triple(NativeAction.HEALTH, L10nKey.MORE_HEALTH_DATA, Res.drawable.ic_connect),
        Triple(NativeAction.REMINDERS, L10nKey.MORE_REMINDER_SETTINGS, Res.drawable.ic_bell),
        Triple(NativeAction.LICENSE, L10nKey.MORE_AI_ACTIVATION, Res.drawable.ic_key),
        Triple(NativeAction.PRIVACY, L10nKey.MORE_PRIVACY_POLICY, Res.drawable.ic_file_text),
        Triple(NativeAction.NOTICES, L10nKey.MORE_NOTICES, Res.drawable.ic_code),
    )

    @Composable
    fun More(ctx: RouteContext) = RootScaffold(ctx, vitaText(L10nKey.NAV_MORE)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = VezunaTokens.GutterNarrow),
            verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceLg),
        ) {
            Spacer(Modifier.height(VezunaTokens.SpaceSm))
            GroupedRows(title = vitaText(L10nKey.MORE_SECTION_RECORDS)) {
                ListRow(vitaText(L10nKey.SETTINGS_EXPORT), onClick = { ctx.navigate(AppScreen.Settings) }, leadingIcon = Res.drawable.ic_settings, category = Category.RECORDS)
                ListRow(vitaText(L10nKey.MORE_ASSISTANT), onClick = { ctx.navigate(AppScreen.AiChat) }, leadingIcon = Res.drawable.ic_chat, category = Category.TRENDS)
                ListRow(vitaText(L10nKey.MANAGE_LOCAL_MODELS), onClick = { ctx.navigate(AppScreen.Models) }, leadingIcon = Res.drawable.ic_chip, category = Category.TRENDS)
            }
            val available = nativeRows.filter { it.first in ctx.nativeActions }
            if (available.isNotEmpty()) {
                GroupedRows(title = vitaText(L10nKey.MORE_SECTION_DEVICE)) {
                    available.forEach { (action, label, icon) ->
                        ListRow(vitaText(label), onClick = { ctx.nativeActions.perform(action) }, leadingIcon = icon, category = Category.ACTIVITY)
                    }
                }
            }
            Spacer(Modifier.height(VezunaTokens.SpaceLg))
        }
    }

    @Composable
    private fun Legacy(ctx: RouteContext) = LegacyPushed(ctx) {
        SettingsScreen(ctx.state, ctx.controller, ctx.scope, ctx.backupFiles, ctx.reminders)
    }

    @Composable
    fun Settings(ctx: RouteContext) = Legacy(ctx)

    @Composable
    fun Appearance(ctx: RouteContext) = Legacy(ctx)

    @Composable
    fun Language(ctx: RouteContext) = Legacy(ctx)

    @Composable
    fun About(ctx: RouteContext) = Legacy(ctx)

    @Composable
    fun PrivacyPolicy(ctx: RouteContext) = Legacy(ctx)

    @Composable
    fun Notices(ctx: RouteContext) = Legacy(ctx)

    @Composable
    fun Help(ctx: RouteContext) = Legacy(ctx)
}
