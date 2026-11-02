package app.vezuna.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import app.vezuna.llm.ModelPack
import app.vezuna.llm.ModelPackSource
import app.vezuna.ui.AppController
import app.vezuna.ui.AppScreen
import app.vezuna.ui.AppUiState
import app.vezuna.ui.BackupFileAccess
import app.vezuna.ui.PickedSource
import app.vezuna.ui.ReminderPlatformState
import app.vezuna.ui.components.FocusedTaskScaffold
import app.vezuna.ui.components.RootHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** OS-owned or platform-native screens the shared UI can open. Each platform reports what it supports. */
enum class NativeAction {
    /** iOS native picker and extractor (Android imports through the shared source picker). */
    IMPORT_SOURCE,

    /** Health Connect (Android) or HealthKit (iOS) import. */
    HEALTH,

    /** Android notification permission and exact-alarm settings for reminders. */
    REMINDERS,

    /** iOS store activation for the optional AI capability. */
    LICENSE,
    PRIVACY,
    NOTICES,
}

/** [enabled] is the subset usable right now (for example Health while an import runs is listed but disabled). */
class NativeActions(
    val available: Set<NativeAction> = emptySet(),
    val enabled: Set<NativeAction> = available,
    val perform: (NativeAction) -> Unit = {},
) {
    operator fun contains(action: NativeAction) = action in available
}

/**
 * Focus target the shell requests on every route change, so screen readers start at the new screen. Root and
 * focused-task scaffolds attach it to their title (or the back button when there is no title).
 */
val LocalRouteFocus = staticCompositionLocalOf<FocusRequester?> { null }

/**
 * Everything a route needs.
 * Saved UI state (rememberSaveable) is kept per stack entry and dropped when the entry leaves the stack; on
 * Android it can also land in the saved-instance Bundle, so never put health data or secrets (passphrases)
 * in rememberSaveable: keep them in plain remember or the controller.
 * Built by the shell for each composition; lanes read from it, never from globals.
 */
class RouteContext(
    val state: AppUiState,
    val controller: AppController,
    val scope: CoroutineScope,
    val loadedModelId: String?,
    val selectedModelId: String?,
    val sourcePicker: (suspend () -> PickedSource?)?,
    val modelPackLoader: (suspend (ModelPack) -> ModelPackSource?)?,
    val backupFiles: BackupFileAccess?,
    val reminders: ReminderPlatformState?,
    val nativeActions: NativeActions,
) {
    fun navigate(screen: AppScreen) = controller.navigate(screen)

    fun back() {
        controller.back()
    }

    fun switchProfile(profileId: String) {
        scope.launch { controller.switchProfile(profileId) }
    }
}

/** Root screen layout: the pine [RootHeader] with the profile switcher, then the screen body. */
@Composable
fun RootScaffold(ctx: RouteContext, title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        RootHeader(
            title = title,
            subtitle = subtitle,
            profiles = ctx.state.profiles,
            currentProfileId = ctx.state.data?.profile?.profileId,
            onSwitchProfile = ctx::switchProfile,
            onManageProfiles = { ctx.navigate(AppScreen.Profiles) },
            onAddProfile = { ctx.navigate(AppScreen.Profiles) },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

/** A pushed route that still renders a pre-redesign screen: back button, the old screen keeps its own title. */
@Composable
fun LegacyPushed(ctx: RouteContext, content: @Composable () -> Unit) {
    FocusedTaskScaffold(title = null, onBack = ctx::back, content = content)
}
