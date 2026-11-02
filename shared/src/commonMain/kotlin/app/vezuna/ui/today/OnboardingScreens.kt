package app.vezuna.ui.today

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.localization.resolve
import app.vezuna.localization.vitaText
import app.vezuna.ui.AppScreen
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.LabeledField
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.TertiaryButton
import app.vezuna.ui.components.routeFocus
import app.vezuna.ui.nav.RouteContext
import app.vezuna.ui.vezuna
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_back
import vezuna.shared.generated.resources.ill_foliage
import vezuna.shared.generated.resources.ill_records
import vezuna.shared.generated.resources.vezuna_lockup_white

/**
 * First run on a fresh install only (AppUiState.onboarding): welcome, then the first profile. Skippable at each
 * step and never gates records. The step is plain UI state; nothing here is health data.
 */
@Composable
internal fun OnboardingFlow(ctx: RouteContext) {
    var naming by rememberSaveable { mutableStateOf(false) }
    if (naming) {
        FirstProfileScreen(ctx, onBack = { naming = false })
    } else {
        WelcomeScreen(ctx, onStart = { naming = true })
    }
}

/** Image 002. Restore leaves onboarding for the existing backup flow, which states its own restore modes. */
@Composable
internal fun WelcomeScreen(ctx: RouteContext, onStart: () -> Unit) {
    val roles = MaterialTheme.vezuna
    Surface(Modifier.fillMaxSize(), color = roles.header, contentColor = roles.onHeader) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = VezunaTokens.Gutter, vertical = VezunaTokens.SpaceLg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceLg),
        ) {
            Image(painterResource(Res.drawable.vezuna_lockup_white), contentDescription = "Vezuna", modifier = Modifier.width(200.dp))
            Text(
                vitaText(L10nKey.WELCOME_TITLE),
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.routeFocus().semantics { heading() },
            )
            Image(painterResource(Res.drawable.ill_records), contentDescription = null, modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth().heightIn(max = 220.dp))
            Text(vitaText(L10nKey.WELCOME_BODY), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd)) {
                PrimaryButton(vitaText(L10nKey.ONBOARDING_DONE), onStart, Modifier.fillMaxWidth(), container = roles.headerAction, content = roles.onHeaderAction)
                OutlinedButton(
                    onClick = { ctx.controller.skipOnboarding(); ctx.navigate(AppScreen.Backups) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = VezunaTokens.RowHeight),
                    shape = RoundedCornerShape(VezunaTokens.ControlRadius),
                    border = BorderStroke(1.dp, roles.headerAction),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = roles.onHeader),
                ) { Text(vitaText(L10nKey.WELCOME_RESTORE), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
            }
        }
    }
}

/**
 * Image 003. Names the profile created on first launch (prefilled with the localized default) and optionally
 * sets a birth date. Skip keeps the default name; nothing is created twice.
 */
@Composable
internal fun FirstProfileScreen(ctx: RouteContext, onBack: () -> Unit, onDone: () -> Unit = {}) {
    val state = ctx.state
    var name by remember { mutableStateOf(state.data?.profile?.displayName.orEmpty()) }
    var birthDate by remember { mutableStateOf<LocalDate?>(null) }
    val roles = MaterialTheme.vezuna
    val birthError = (state.error as? UserText.Key)?.takeIf { it.key == L10nKey.BIRTH_DATE_INVALID }?.resolve()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
        Surface(
            Modifier.fillMaxWidth(),
            color = roles.header,
            contentColor = roles.onHeader,
            shape = RoundedCornerShape(bottomStart = VezunaTokens.SheetRadius, bottomEnd = VezunaTokens.SheetRadius),
        ) {
            Box {
                Image(painterResource(Res.drawable.ill_foliage), contentDescription = null, modifier = Modifier.align(Alignment.BottomEnd).width(132.dp).heightIn(max = 104.dp))
                Column(Modifier.statusBarsPadding().padding(start = VezunaTokens.Gutter, end = VezunaTokens.SpaceMd, top = VezunaTokens.SpaceXs, bottom = VezunaTokens.SpaceLg)) {
                    IconButton(onClick = onBack, modifier = Modifier.size(VezunaTokens.TouchTarget)) {
                        Icon(painterResource(Res.drawable.ic_back), contentDescription = vitaText(L10nKey.NAVIGATE_BACK))
                    }
                    Text(
                        vitaText(L10nKey.FIRST_PROFILE_TITLE),
                        style = MaterialTheme.typography.displaySmall,
                        modifier = Modifier.padding(end = 120.dp).routeFocus().semantics { heading() },
                    )
                    Text(vitaText(L10nKey.FIRST_PROFILE_SUBTITLE), style = MaterialTheme.typography.bodyLarge, color = roles.onHeaderMuted, modifier = Modifier.padding(end = 120.dp, top = VezunaTokens.SpaceSm))
                }
            }
        }
        Surface(
            Modifier.fillMaxWidth().padding(VezunaTokens.GutterNarrow),
            shape = RoundedCornerShape(VezunaTokens.CardRadius),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(VezunaTokens.SpaceLg), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd)) {
                Text(vitaText(L10nKey.FIRST_PROFILE_QUESTION), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(vitaText(L10nKey.FIRST_PROFILE_HINT), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LabeledField(vitaText(L10nKey.ONBOARDING_NAME), name, { name = it })
                BirthDateField(birthDate, { birthDate = it }, error = birthError)
                Text(vitaText(L10nKey.ONBOARDING_PRIVACY), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PrimaryButton(
                    vitaText(L10nKey.FIRST_PROFILE_CREATE),
                    {
                        ctx.scope.launch {
                            ctx.controller.completeOnboarding(name, birthDate)
                            if (ctx.controller.state.value.error == null) onDone()
                        }
                    },
                    Modifier.fillMaxWidth(),
                    loading = state.loading,
                )
                TertiaryButton(vitaText(L10nKey.ONBOARDING_SKIP), { ctx.controller.skipOnboarding(); onDone() }, Modifier.fillMaxWidth())
            }
        }
    }
}
