package app.vezuna.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vezuna.data.VezunaRepository
import app.vezuna.llm.InstalledModelManager
import app.vezuna.llm.LocalHealthChat
import app.vezuna.llm.ModelCatalog
import app.vezuna.llm.ModelFileStore
import app.vezuna.llm.ModelPack
import app.vezuna.llm.ModelPackSource
import app.vezuna.llm.llamatikLocalLlm
import app.vezuna.localization.L10nKey
import app.vezuna.localization.resolve
import app.vezuna.localization.vitaText
import app.vezuna.platform.currentInstant
import app.vezuna.ui.components.FeedbackHost
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import app.vezuna.ui.nav.LocalRouteFocus
import app.vezuna.ui.nav.Destination
import app.vezuna.ui.nav.NativeActions
import app.vezuna.ui.nav.Route
import app.vezuna.ui.nav.RouteContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_heart
import vezuna.shared.generated.resources.ic_home
import vezuna.shared.generated.resources.ic_more
import vezuna.shared.generated.resources.ic_records
import vezuna.shared.generated.resources.ic_trends
import vezuna.shared.generated.resources.vezuna_mark_dark

enum class ExtractedValueKind { NUMERIC, QUALITATIVE }

data class ExtractedCandidate(
    val testCode: String,
    val displayName: String,
    val rawValue: String,
    val rawUnit: String = "",
    val referenceRange: app.vezuna.domain.ReferenceRange = app.vezuna.domain.ReferenceRange(),
    val collectedAt: kotlinx.datetime.Instant? = null,
    val confidence: Float? = null,
    val valueKind: ExtractedValueKind = ExtractedValueKind.NUMERIC,
    val rawToken: String? = null,
)

data class PickedSource(
    val fileName: String,
    val mediaType: String,
    val bytes: ByteArray,
    val candidates: List<ExtractedCandidate> = emptyList(),
    /** Read from the page text; drafts for the reviewer, never confirmed facts. */
    val laboratoryName: String? = null,
    val orderedAt: kotlinx.datetime.Instant? = null,
    /** Further photos of the same report. [bytes] is the first page; the hash covers every page so a re-import dedupes. */
    val additionalPages: List<ByteArray> = emptyList(),
)

private fun Destination.label(): L10nKey = when (this) {
    Destination.TODAY -> L10nKey.NAV_TODAY
    Destination.RECORDS -> L10nKey.NAV_RECORDS
    Destination.CARE -> L10nKey.NAV_CARE
    Destination.TRENDS -> L10nKey.NAV_TRENDS
    Destination.MORE -> L10nKey.NAV_MORE
}

private fun Destination.icon(): DrawableResource = when (this) {
    Destination.TODAY -> Res.drawable.ic_home
    Destination.RECORDS -> Res.drawable.ic_records
    Destination.CARE -> Res.drawable.ic_heart
    Destination.TRENDS -> Res.drawable.ic_trends
    Destination.MORE -> Res.drawable.ic_more
}

@Composable
fun VezunaApp(
    repository: VezunaRepository,
    modelFiles: ModelFileStore,
    catalog: ModelCatalog,
    sourcePicker: (suspend () -> PickedSource?)? = null,
    modelPackLoader: (suspend (ModelPack) -> ModelPackSource?)? = null,
) {
    val llm = remember { llamatikLocalLlm() }
    val controller = remember(repository, modelFiles, catalog) {
        AppController(repository, InstalledModelManager(catalog, modelFiles, llm), LocalHealthChat(repository, llm))
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller) { controller.initialize() }
    VezunaApp(controller, scope, sourcePicker, modelPackLoader)
}

/**
 * The app shell: five labeled destinations (bottom bar, or a rail at 600dp and wider), one back stack per
 * destination, and the single global feedback host. App lock and vault recovery wrap this from outside, so
 * nothing here is composed while locked. [startRoute] is a debug deep link for the screenshot harness.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VezunaApp(
    controller: AppController,
    scope: CoroutineScope,
    sourcePicker: (suspend () -> PickedSource?)? = null,
    modelPackLoader: (suspend (ModelPack) -> ModelPackSource?)? = null,
    animationsEnabled: Boolean = true,
    backupFiles: BackupFileAccess? = null,
    reminders: ReminderPlatformState? = null,
    nativeActions: NativeActions = NativeActions(),
    startRoute: AppScreen? = null,
) {
    val state by controller.state.collectAsState()
    val nav by controller.navigator.state.collectAsState()
    val modelState by controller.modelManager.state.collectAsState()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val nextDoseAt = state.dashboard?.nextDose?.scheduledFor
    LaunchedEffect(controller, startRoute) { startRoute?.let(controller::navigate) }
    LaunchedEffect(controller, windowFocused) {
        if (windowFocused) controller.refreshDashboard()
    }
    LaunchedEffect(controller) {
        DashboardRefreshSignal.changes.collect { controller.refreshDashboard() }
    }
    LaunchedEffect(controller, windowFocused, nextDoseAt) {
        if (!windowFocused || nextDoseAt == null) return@LaunchedEffect
        val refreshIn = nextDoseAt.toEpochMilliseconds() - currentInstant().toEpochMilliseconds() + 1_000
        if (refreshIn > 0) delay(refreshIn)
        controller.refreshDashboard()
    }
    BackHandler(enabled = nav.canGoBack) { controller.back() }
    VezunaTheme(animationsEnabled = animationsEnabled) {
        val motionEnabled = LocalVitaAnimationsEnabled.current
        val loadingDescription = vitaText(L10nKey.LOADING_DATA)
        val savedState = rememberSaveableStateHolder()
        val profileId = state.data?.profile?.profileId
        // Drop saved UI state (drafts, scroll) of every entry that left the back stacks, so re-pushing a screen
        // never brings back an old draft. Keys are per profile, root, depth and screen.
        var liveKeys by remember { mutableStateOf(emptySet<String>()) }
        LaunchedEffect(nav, profileId) {
            val now = nav.stacks.flatMap { (root, stack) -> stack.mapIndexed { index, screen -> entryKey(profileId, root, index + 1, screen) } }.toSet()
            (liveKeys - now).forEach(savedState::removeState)
            liveKeys = now
        }
        val context = RouteContext(
            state = state,
            controller = controller,
            scope = scope,
            loadedModelId = modelState.loadedModelId,
            selectedModelId = modelState.selectedModelId,
            sourcePicker = sourcePicker,
            modelPackLoader = modelPackLoader,
            backupFiles = backupFiles,
            reminders = reminders,
            nativeActions = nativeActions,
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= VezunaTokens.RailBreakpoint
            Row(Modifier.fillMaxSize()) {
                if (wide && !state.onboarding) VezunaRail(nav.root, controller::selectRoot)
                Scaffold(
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.background,
                    // The top inset is consumed by RootHeader and FocusedTaskScaffold, whose pine band reaches
                    // under the status bar.
                    contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    bottomBar = { if (!wide && nav.isRoot && !state.onboarding) VezunaNavigationBar(nav.root, controller::selectRoot) },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
                        AnimatedContent(
                            targetState = Triple(nav.root, nav.stack.size, nav.current),
                            transitionSpec = {
                                if (motionEnabled) {
                                    fadeIn(tween(VezunaTokens.MotionNormalMs)) togetherWith fadeOut(tween(VezunaTokens.MotionFastMs))
                                } else {
                                    androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                                }
                            },
                            label = "screen-navigation",
                        ) { (root, depth, screen) ->
                            // Saved UI state (scroll, filters, drafts) is per profile and per stack entry, so a
                            // profile switch drops it and returning to a tab restores it.
                            savedState.SaveableStateProvider(entryKey(profileId, root, depth, screen)) {
                                // Each route change moves screen-reader focus to the new title (or back button).
                                val titleFocus = remember { FocusRequester() }
                                CompositionLocalProvider(LocalRouteFocus provides titleFocus) { Route(screen, context) }
                                LaunchedEffect(Unit) {
                                    withFrameNanos { }
                                    runCatching { titleFocus.requestFocus() }
                                }
                            }
                        }
                        LoadingIndicator(state.loading, motionEnabled, loadingDescription, Modifier.align(Alignment.TopCenter).statusBarsPadding())
                        FeedbackHost(
                            notice = state.notice?.resolve(),
                            error = state.error?.resolve(),
                            onDismiss = controller::clearMessages,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
    }
}

private fun entryKey(profileId: String?, root: Destination, depth: Int, screen: AppScreen) = "$profileId/$root/$depth/$screen"

@Composable
private fun LoadingIndicator(visible: Boolean, motionEnabled: Boolean, description: String, modifier: Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (motionEnabled) fadeIn() else androidx.compose.animation.EnterTransition.None,
        exit = if (motionEnabled) fadeOut() else androidx.compose.animation.ExitTransition.None,
    ) {
        CircularProgressIndicator(
            Modifier.padding(8.dp).semantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
            strokeWidth = 3.dp,
        )
    }
}

@Composable
private fun VezunaNavigationBar(current: Destination, onSelect: (Destination) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        Destination.entries.forEach { destination ->
            NavigationBarItem(
                selected = destination == current,
                onClick = { onSelect(destination) },
                icon = { Icon(painterResource(destination.icon()), contentDescription = null, modifier = Modifier.size(24.dp)) },
                // Labels never truncate: at large text they wrap to a second line instead. The Material bar size
                // (12sp) keeps "Records"/"Registros" on one line on narrow phones (about 290pt wide).
                label = {
                    Text(
                        vitaText(destination.label()),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        fontWeight = if (destination == current) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                alwaysShowLabel = true,
                // Selected tab: a filled indicator at 3:1 or more on the bar (pine on light, mint on dark) and a
                // bold label, so the state never rests on a faint tint.
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.vezuna.onNavIndicator,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.vezuna.navIndicator,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
private fun VezunaRail(current: Destination, onSelect: (Destination) -> Unit) {
    NavigationRail(
        containerColor = MaterialTheme.vezuna.header,
        header = {
            Image(
                painterResource(Res.drawable.vezuna_mark_dark),
                contentDescription = vitaText(L10nKey.VEZUNA),
                modifier = Modifier.padding(vertical = 12.dp).size(44.dp),
            )
        },
    ) {
        Column(Modifier.padding(top = 8.dp)) {
            Destination.entries.forEach { destination ->
                NavigationRailItem(
                    selected = destination == current,
                    onClick = { onSelect(destination) },
                    icon = { Icon(painterResource(destination.icon()), contentDescription = null, modifier = Modifier.size(24.dp)) },
                    label = { Text(vitaText(destination.label()), maxLines = 2, textAlign = TextAlign.Center) },
                    alwaysShowLabel = true,
                    colors = NavigationRailItemDefaults.colors(
                        selectedIconColor = VezunaTokens.OnCategory,
                        selectedTextColor = MaterialTheme.vezuna.onHeader,
                        indicatorColor = VezunaTokens.Mint,
                        unselectedIconColor = MaterialTheme.vezuna.onHeader,
                        unselectedTextColor = MaterialTheme.vezuna.onHeader,
                    ),
                )
            }
        }
    }
}
