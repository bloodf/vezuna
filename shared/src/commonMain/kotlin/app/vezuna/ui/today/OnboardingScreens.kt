package app.vezuna.ui.today

import app.vezuna.ui.components.cappedScale
import app.vezuna.ui.components.decorativeArtShown
import app.vezuna.ui.components.clearOfArt
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
import app.vezuna.ui.components.clearOfNavigationBar
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
import androidx.compose.runtime.collectAsState
import app.vezuna.ui.NotificationAccess
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
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
import app.vezuna.ui.AppController
import app.vezuna.ui.AppScreen
import app.vezuna.ui.nav.NativeActions
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.LabeledField
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.TertiaryButton
import app.vezuna.ui.components.routeFocus
import app.vezuna.ui.nav.RouteContext
import app.vezuna.ui.nav.NativeAction
import app.vezuna.ui.nav.LocalRouteFocus
import app.vezuna.ui.vezuna
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_back
import vezuna.shared.generated.resources.ill_foliage
import vezuna.shared.generated.resources.ill_records
import vezuna.shared.generated.resources.vezuna_lockup_white

/**
 * First run on a fresh install only: welcome, first profile, then optional reminders and device health. Skippable at each
 * step and never gates records. The step is plain UI state; nothing here is health data.
 */
@Composable
internal fun OnboardingFlow(ctx: RouteContext) {
    var step by rememberSaveable { mutableStateOf(0) }
    val focus = LocalRouteFocus.current
    LaunchedEffect(step) {
        withFrameNanos { }
        runCatching { focus?.requestFocus() }
    }
    when (step) {
        0 -> WelcomeScreen(ctx, onStart = { step = 1 })
        1 -> FirstProfileScreen(ctx, onBack = { step = 0 }, onDone = { step = 2 }, finish = false)
        2 -> OptionalSetupScreen(ctx, notifications = true, onNext = { step = 3 })
        else -> OptionalSetupScreen(ctx, notifications = false, onNext = { ctx.controller.skipOnboarding() })
    }
}

@Composable
private fun OptionalSetupScreen(ctx: RouteContext, notifications: Boolean, onNext: () -> Unit) {
    val action = if (notifications) NativeAction.REMINDERS else NativeAction.HEALTH
    val current = ctx.state.data?.profile
    val notificationAccess = ctx.reminders?.notificationAccess?.collectAsState()?.value
    val linked = ctx.state.healthLinkedProfileId
    val linkedElsewhere = !notifications && linked != null && linked != current?.profileId
    val available = ctx.nativeActions.supportsOnboarding(action)
    var busy by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())
            .statusBarsPadding().clearOfNavigationBar().padding(VezunaTokens.Gutter),
        verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceLg),
    ) {
        Text(
            vitaText(if (notifications) L10nKey.CARE_REMINDERS_TITLE else L10nKey.TR_LINK_HEADING),
            style = MaterialTheme.typography.displaySmall.cappedScale(),
            modifier = Modifier.routeFocus().semantics { heading() },
        )
        Text(
            vitaText(if (notifications) L10nKey.CARE_REMINDERS_INTRO else L10nKey.LINKED_PROFILE_EXPLANATION),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (notifications && notificationAccess == NotificationAccess.DENIED) {
            Text(vitaText(L10nKey.CARE_NOTIFICATIONS_OFF_BODY), style = MaterialTheme.typography.bodyLarge)
        }
        if (!notifications) {
            Text(vitaText(L10nKey.TR_PERMISSION_NOTE), style = MaterialTheme.typography.bodyLarge)
            if (current != null && !linkedElsewhere) {
                Text(vitaText(L10nKey.ONBOARDING_HEALTH_LINK_NOTE, current.displayName), style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (!available && !notifications) {
            Text(vitaText(L10nKey.TR_HEALTH_UNAVAILABLE_BODY), style = MaterialTheme.typography.bodyLarge)
        }
        ctx.state.error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (!notifications && linked != null) {
            val linkedName = ctx.state.profiles.firstOrNull { it.profileId == linked }?.displayName.orEmpty()
            Text(vitaText(L10nKey.LINKED_PROFILE_CURRENT, linkedName), style = MaterialTheme.typography.bodyLarge)
        }
        if (available && action in ctx.nativeActions.enabled && !linkedElsewhere && (notifications || current != null)) {
            val label = if (notifications) vitaText(L10nKey.CARE_ENABLE_REMINDERS)
                else vitaText(L10nKey.TR_IMPORT_NOW)
            PrimaryButton(label, {
                ctx.scope.launch {
                    busy = true
                    try {
                        requestOnboardingOption(ctx.controller, ctx.nativeActions, action, current?.profileId)
                    } finally { busy = false }
                }
            }, Modifier.fillMaxWidth(), loading = busy)
        }
        TertiaryButton(vitaText(if (notifications) L10nKey.CONTINUE_ACTION else L10nKey.DONE_ACTION), onNext, Modifier.fillMaxWidth(), enabled = !busy)
    }
}

/** Invoked only by an explicit optional-setup tap; skipping never reaches a native action. */
internal suspend fun requestOnboardingOption(controller: AppController, actions: NativeActions, action: NativeAction, profileId: String?) {
    if (!controller.state.value.onboarding || !actions.supportsOnboarding(action) || action !in actions.enabled) return
    when (action) {
        NativeAction.REMINDERS -> actions.perform(action)
        NativeAction.HEALTH -> {
            val perform = actions.performHealth ?: return
            if (profileId != null) controller.linkOnboardingHealthProfile(profileId)?.let {
                currentCoroutineContext().ensureActive()
                perform(it)
            }
        }
        else -> Unit
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
                style = MaterialTheme.typography.displaySmall.cappedScale(),
                textAlign = TextAlign.Center,
                modifier = Modifier.routeFocus().semantics { heading() },
            )
            Image(painterResource(Res.drawable.ill_records), contentDescription = null, modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth().heightIn(max = 220.dp))
            Text(vitaText(L10nKey.WELCOME_BODY), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd)) {
                PrimaryButton(vitaText(L10nKey.ONBOARDING_DONE), onStart, Modifier.fillMaxWidth(), container = roles.headerAction, content = roles.onHeaderAction)
                OutlinedButton(
                    onClick = { ctx.controller.startFirstRunRestore(); ctx.navigate(AppScreen.Backups) },
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
internal fun FirstProfileScreen(ctx: RouteContext, onBack: () -> Unit, onDone: () -> Unit = {}, finish: Boolean = true) {
    val state = ctx.state
    var name by remember { mutableStateOf(state.data?.profile?.displayName.orEmpty()) }
    var birthDate by remember { mutableStateOf<LocalDate?>(null) }
    var relationship by remember { mutableStateOf("") }
    val roles = MaterialTheme.vezuna
    val birthError = (state.error as? UserText.Key)?.takeIf { it.key == L10nKey.BIRTH_DATE_INVALID }?.resolve()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).clearOfNavigationBar()) {
        Surface(
            Modifier.fillMaxWidth(),
            color = roles.header,
            contentColor = roles.onHeader,
            shape = RoundedCornerShape(bottomStart = VezunaTokens.SheetRadius, bottomEnd = VezunaTokens.SheetRadius),
        ) {
            Box {
                if (decorativeArtShown()) Image(painterResource(Res.drawable.ill_foliage), contentDescription = null, modifier = Modifier.align(Alignment.BottomEnd).width(132.dp).heightIn(max = 104.dp))
                Column(Modifier.statusBarsPadding().padding(start = VezunaTokens.Gutter, end = VezunaTokens.SpaceMd, top = VezunaTokens.SpaceXs, bottom = VezunaTokens.SpaceLg)) {
                    IconButton(onClick = onBack, modifier = Modifier.size(VezunaTokens.TouchTarget)) {
                        Icon(painterResource(Res.drawable.ic_back), contentDescription = vitaText(L10nKey.NAVIGATE_BACK))
                    }
                    Text(
                        vitaText(L10nKey.FIRST_PROFILE_TITLE),
                        style = MaterialTheme.typography.displaySmall.cappedScale(),
                        modifier = Modifier.clearOfArt(120.dp).routeFocus().semantics { heading() },
                    )
                    Text(vitaText(L10nKey.FIRST_PROFILE_SUBTITLE), style = MaterialTheme.typography.bodyLarge, color = roles.onHeaderMuted, modifier = Modifier.clearOfArt(120.dp).padding(top = VezunaTokens.SpaceSm))
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
                LabeledField(vitaText(L10nKey.PROFILE_RELATIONSHIP_OPTIONAL), relationship, { relationship = it })
                Text(vitaText(L10nKey.ONBOARDING_PRIVACY), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PrimaryButton(
                    vitaText(L10nKey.FIRST_PROFILE_CREATE),
                    {
                        ctx.scope.launch {
                            ctx.controller.completeOnboarding(name, birthDate, finish, relationship)
                            if (ctx.controller.state.value.error == null) onDone()
                        }
                    },
                    Modifier.fillMaxWidth(),
                    loading = state.loading,
                )
                TertiaryButton(vitaText(L10nKey.ONBOARDING_SKIP), { if (finish) ctx.controller.skipOnboarding(); onDone() }, Modifier.fillMaxWidth())
            }
        }
    }
}
