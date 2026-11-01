package app.vezuna.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import app.vezuna.security.AppLock
import app.vezuna.security.AppLockProblem
import kotlinx.coroutines.launch

val LocalAppLock = staticCompositionLocalOf<AppLock?> { null }

/** While locked nothing from [content] is composed, so no health data exists in the UI tree behind the lock. */
@Composable
fun AppLockHost(appLock: AppLock, content: @Composable () -> Unit) {
    val state by appLock.state.collectAsState()
    CompositionLocalProvider(LocalAppLock provides appLock) {
        if (state.locked) LockScreen(appLock) else content()
    }
}

@Composable
private fun LockScreen(appLock: AppLock) {
    val state by appLock.state.collectAsState()
    val scope = rememberCoroutineScope()
    // One automatic prompt per lock; the button covers cancel and failure.
    LaunchedEffect(Unit) { appLock.unlock() }
    VezunaTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Text(vitaText(L10nKey.LOCK_SCREEN_TITLE), style = MaterialTheme.typography.headlineSmall)
                Text(vitaText(L10nKey.LOCK_SCREEN_BODY), style = MaterialTheme.typography.bodyMedium)
                if (state.problem == AppLockProblem.FAILED) {
                    Text(vitaText(L10nKey.APP_LOCK_FAILED), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                VitaButton(vitaText(L10nKey.UNLOCK), Icons.Rounded.Lock, { scope.launch { appLock.unlock() } })
            }
        }
    }
}

/** Settings toggle. Hidden when the platform supplied no [AppLock]. */
@Composable
fun AppLockSettings() {
    val appLock = LocalAppLock.current ?: return
    val state by appLock.state.collectAsState()
    val scope = rememberCoroutineScope()
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(vitaText(L10nKey.APP_LOCK_TITLE), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = state.enabled, onCheckedChange = { scope.launch { appLock.setEnabled(it) } })
            }
            Text(vitaText(L10nKey.APP_LOCK_DESCRIPTION), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (state.problem) {
                AppLockProblem.UNAVAILABLE -> Text(vitaText(L10nKey.APP_LOCK_UNAVAILABLE), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                AppLockProblem.FAILED -> Text(vitaText(L10nKey.APP_LOCK_FAILED), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                AppLockProblem.NO_SCREEN_LOCK -> Text(vitaText(L10nKey.APP_LOCK_SUSPENDED), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                null -> Unit
            }
        }
    }
}
