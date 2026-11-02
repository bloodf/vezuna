package app.vezuna.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
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

    /** iOS native export and share sheet. */
    EXPORT,

    /** Health Connect (Android) or HealthKit (iOS) import. */
    HEALTH,

    /** Android notification permission and exact-alarm settings for reminders. */
    REMINDERS,

    /** iOS store activation for the optional AI capability. */
    LICENSE,
    PRIVACY,
    NOTICES,
}

class NativeActions(val available: Set<NativeAction> = emptySet(), val perform: (NativeAction) -> Unit = {}) {
    operator fun contains(action: NativeAction) = action in available
}

/** Everything a route needs. Built by the shell for each composition; lanes read from it, never from globals. */
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
        )
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

/** A pushed route that still renders a pre-redesign screen: back button, the old screen keeps its own title. */
@Composable
fun LegacyPushed(ctx: RouteContext, content: @Composable () -> Unit) {
    FocusedTaskScaffold(title = null, onBack = ctx::back, content = content)
}
