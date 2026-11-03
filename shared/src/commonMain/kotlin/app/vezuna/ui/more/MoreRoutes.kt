package app.vezuna.ui.more

import app.vezuna.ui.components.clearOfNavigationBar
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
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.nav.RootScaffold
import app.vezuna.ui.nav.RouteContext

/**
 * Lane L7: the More hub and its preference, about and help pages. Backup, lock and vault rows only link to
 * the security routes (L6); the assistant and models link to the assistant routes (L8).
 */
object MoreRoutes {
    @Composable
    fun More(ctx: RouteContext) = RootScaffold(ctx, vitaText(L10nKey.NAV_MORE), vitaText(L10nKey.MORE_SUBTITLE)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).clearOfNavigationBar().padding(horizontal = VezunaTokens.GutterNarrow),
            verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
        ) {
            Spacer(Modifier.height(VezunaTokens.SpaceSm))
            MoreRows(ctx)
            Spacer(Modifier.height(VezunaTokens.SpaceLg))
        }
    }

    @Composable
    fun Settings(ctx: RouteContext) = MoreSettingsPage(ctx)

    @Composable
    fun Appearance(ctx: RouteContext) = AppearanceScreen(ctx)

    @Composable
    fun Language(ctx: RouteContext) = LanguageScreen(ctx)

    @Composable
    fun About(ctx: RouteContext) = AboutScreen(ctx)

    @Composable
    fun PrivacyPolicy(ctx: RouteContext) = PrivacyPolicyScreen(ctx)

    @Composable
    fun Notices(ctx: RouteContext) = NoticesScreen(ctx)

    @Composable
    fun Help(ctx: RouteContext) = HelpScreen(ctx)

    @Composable
    fun EmergencyCard(ctx: RouteContext) = EmergencyCardScreen(ctx)
}
