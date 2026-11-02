package app.vezuna.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import app.vezuna.security.AppLock
import app.vezuna.security.AppLockProblem
import app.vezuna.security.AppLockState
import app.vezuna.ui.components.GroupedRows
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.StatusKind
import app.vezuna.ui.security.SecurityNotice
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_fingerprint
import vezuna.shared.generated.resources.ic_unlock
import vezuna.shared.generated.resources.vezuna_mark_white

val LocalAppLock = staticCompositionLocalOf<AppLock?> { null }

/** While locked nothing from [content] is composed, so no health data exists in the UI tree behind the lock. */
@Composable
fun AppLockHost(appLock: AppLock, content: @Composable () -> Unit) {
    val state by appLock.state.collectAsState()
    CompositionLocalProvider(LocalAppLock provides appLock) {
        if (state.locked) LockScreen(appLock) else content()
    }
}

/**
 * Images 009/010. Only the mark, the title and the unlock action: no profile, count or record. The biometric or
 * passcode prompt is the operating system's own UI. After a cancel or failure, focus moves to Unlock.
 */
@Composable
private fun LockScreen(appLock: AppLock) {
    val state by appLock.state.collectAsState()
    val scope = rememberCoroutineScope()
    val titleFocus = remember { FocusRequester() }
    val unlockFocus = remember { FocusRequester() }
    // One automatic prompt per lock; the button covers cancel and failure.
    LaunchedEffect(Unit) {
        titleFocus.requestFocus()
        appLock.unlock()
    }
    val failed = state.problem == AppLockProblem.FAILED
    LaunchedEffect(failed) { if (failed) unlockFocus.requestFocus() }
    VezunaTheme {
        Surface(Modifier.fillMaxSize(), color = VezunaTokens.Pine, contentColor = VezunaTokens.Light.onHeader) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()
                    .padding(horizontal = VezunaTokens.Gutter, vertical = VezunaTokens.SpaceXl),
                verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceLg, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(painterResource(Res.drawable.vezuna_mark_white), contentDescription = null, modifier = Modifier.size(120.dp))
                Text(
                    vitaText(L10nKey.LOCK_SCREEN_TITLE),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.focusRequester(titleFocus).focusable().semantics { heading() },
                )
                Text(vitaText(L10nKey.LOCK_SCREEN_BODY), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = VezunaTokens.Light.onHeaderMuted)
                if (failed) SecurityNotice(vitaText(L10nKey.APP_LOCK_FAILED), StatusKind.ERROR)
                PrimaryButton(
                    vitaText(L10nKey.LOCK_UNLOCK_DEVICE),
                    { scope.launch { appLock.unlock() } },
                    Modifier.fillMaxWidth().focusRequester(unlockFocus),
                    icon = Res.drawable.ic_unlock,
                    container = VezunaTokens.Mint,
                    content = VezunaTokens.Pine,
                )
            }
        }
    }
}

/** Settings toggle. Hidden when the platform supplied no [AppLock]. */
@Composable
fun AppLockSettings() {
    val appLock = LocalAppLock.current ?: return
    GroupedRows(title = vitaText(L10nKey.APP_LOCK_TITLE)) { AppLockToggle(appLock) }
    AppLockProblems(appLock.state.collectAsState().value)
}

/** One focus stop: the whole row toggles, the switch only mirrors the state (no separately labeled control). */
@Composable
fun AppLockToggle(appLock: AppLock) {
    val state by appLock.state.collectAsState()
    val scope = rememberCoroutineScope()
    ListRow(
        title = vitaText(L10nKey.APP_LOCK_USE_DEVICE_SECURITY),
        supporting = vitaText(L10nKey.APP_LOCK_DESCRIPTION),
        leadingIcon = Res.drawable.ic_fingerprint,
        category = Category.RECORDS,
        showChevron = false,
        trailing = { Switch(checked = state.enabled, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = state.enabled, role = Role.Switch) { scope.launch { appLock.setEnabled(it) } },
    )
}

/** Why the lock is off or did not change, each with an icon and announced when it appears. */
@Composable
fun AppLockProblems(state: AppLockState) {
    Column(verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
        if (state.suspended) SecurityNotice(vitaText(L10nKey.APP_LOCK_SUSPENDED), StatusKind.WARNING)
        when (state.problem) {
            AppLockProblem.UNAVAILABLE -> SecurityNotice(vitaText(L10nKey.APP_LOCK_UNAVAILABLE_TITLE), StatusKind.WARNING, detail = vitaText(L10nKey.APP_LOCK_UNAVAILABLE))
            AppLockProblem.FAILED -> SecurityNotice(vitaText(L10nKey.APP_LOCK_FAILED), StatusKind.ERROR)
            null -> Unit
        }
    }
}
