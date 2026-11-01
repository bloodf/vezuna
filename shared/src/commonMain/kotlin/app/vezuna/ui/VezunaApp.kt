package app.vezuna.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

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
)

data class PickedSource(
    val fileName: String,
    val mediaType: String,
    val bytes: ByteArray,
    val candidates: List<ExtractedCandidate> = emptyList(),
)

private data class Destination(val screen: AppScreen, val label: L10nKey, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val destinations = listOf(
    Destination(AppScreen.Dashboard, L10nKey.NAV_HOME, Icons.Rounded.Home),
    Destination(AppScreen.Labs, L10nKey.NAV_LABS, VezunaIcons.Lab),
    Destination(AppScreen.ImportReview, L10nKey.IMPORT, VezunaIcons.Plus),
    Destination(AppScreen.Medications, L10nKey.MEDS, VezunaIcons.Medication),
    Destination(AppScreen.AiChat, L10nKey.AI, Icons.Rounded.AutoAwesome),
    Destination(AppScreen.Settings, L10nKey.NAV_SETTINGS, VezunaIcons.Settings),
)

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

@Composable
fun VezunaApp(
    controller: AppController,
    scope: CoroutineScope,
    sourcePicker: (suspend () -> PickedSource?)? = null,
    modelPackLoader: (suspend (ModelPack) -> ModelPackSource?)? = null,
    onDevice: (() -> Unit)? = null,
    animationsEnabled: Boolean = true,
) {
    val state by controller.state.collectAsState()
    val modelState by controller.modelManager.state.collectAsState()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val nextDoseAt = state.dashboard?.nextDose?.scheduledFor
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
    VezunaTheme(animationsEnabled = animationsEnabled) {
        val motionEnabled = LocalVitaAnimationsEnabled.current
        val loadingDescription = vitaText(L10nKey.LOADING_DATA)
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { MiniBrand(onDevice = onDevice ?: { controller.navigate(AppScreen.Settings) }) },
            bottomBar = { FloatingNavigation(state.screen, controller::navigate) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
                AnimatedContent(
                    targetState = state.screen,
                    transitionSpec = {
                        if (motionEnabled) {
                            (fadeIn(tween(VezunaTokens.MotionNormalMs)) + slideInHorizontally(tween(VezunaTokens.MotionNormalMs)) { it / 12 }) togetherWith fadeOut(tween(VezunaTokens.MotionFastMs))
                        } else {
                            androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                        }
                    },
                    label = "screen-navigation",
                ) { screen ->
                    when (screen) {
                        AppScreen.Dashboard -> DashboardScreen(state, controller, scope)
                        AppScreen.Labs -> LabsScreen(state, controller)
                        AppScreen.ImportReview -> ImportReviewScreen(state, controller, scope, sourcePicker)
                        is AppScreen.ResultDetail -> ResultDetailScreen(state, screen.observationId, controller)
                        AppScreen.Trends -> TrendsScreen(state, controller)
                        AppScreen.Medications -> MedicationsScreen(state, controller, scope)
                        AppScreen.AiChat -> AiChatScreen(state, modelState.loadedModelId, modelState.selectedModelId, controller, scope)
                        AppScreen.Models -> ModelsScreen(state, controller, scope, modelPackLoader)
                        AppScreen.Settings -> SettingsScreen(state, controller, scope)
                        AppScreen.Vitals -> VitalsScreen(state, controller, scope)
                    }
                }
                AnimatedVisibility(
                    visible = state.loading,
                    modifier = Modifier.align(Alignment.TopCenter),
                    enter = if (motionEnabled) fadeIn() else androidx.compose.animation.EnterTransition.None,
                    exit = if (motionEnabled) fadeOut() else androidx.compose.animation.ExitTransition.None,
                ) {
                    CircularProgressIndicator(Modifier.padding(8.dp).semantics { contentDescription = loadingDescription }, strokeWidth = 3.dp)
                }
                state.error?.let { error ->
                    Snackbar(
                        Modifier.align(Alignment.BottomCenter).padding(12.dp),
                        containerColor = MaterialTheme.vita.ink,
                        contentColor = MaterialTheme.vita.onInk,
                        dismissAction = { TextButton(onClick = controller::clearMessages) { Text(vitaText(L10nKey.DISMISS), color = MaterialTheme.vita.accentOnInk) } },
                    ) { Text(error.resolve()) }
                }
                state.notice?.let { notice ->
                    Snackbar(
                        Modifier.align(Alignment.BottomCenter).padding(12.dp),
                        containerColor = MaterialTheme.vita.ink,
                        contentColor = MaterialTheme.vita.onInk,
                        dismissAction = { TextButton(onClick = controller::clearMessages) { Text(vitaText(L10nKey.DISMISS), color = MaterialTheme.vita.accentOnInk) } },
                    ) { Text(notice.resolve()) }
                }
            }
        }
    }
}

@Composable
private fun FloatingNavigation(current: AppScreen, onNavigate: (AppScreen) -> Unit) {
    val motionEnabled = LocalVitaAnimationsEnabled.current
    val importDescription = vitaText(L10nKey.IMPORT_LAB_REPORT)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.vita.ink,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            destinations.forEachIndexed { index, destination ->
                val selected = current == destination.screen
                val destinationLabel = vitaText(destination.label)
                if (index == 2) {
                    Box(Modifier.weight(1.1f).offset(y = (-18).dp), contentAlignment = Alignment.Center) {
                        Card(
                            onClick = { onNavigate(AppScreen.ImportReview) },
                            modifier = Modifier.size(66.dp).semantics { contentDescription = importDescription },
                            shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
                            elevation = CardDefaults.cardElevation(defaultElevation = 5.dp),
                        ) {
                            Column(
                                Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(VezunaIcons.Plus, vitaText(L10nKey.IMPORT), Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onPrimary)
                                Text(vitaText(L10nKey.IMPORT), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                } else {
                    TextButton(
                        onClick = { onNavigate(destination.screen) },
                        modifier = Modifier.weight(1f).semantics { contentDescription = destinationLabel },
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (motionEnabled) {
                                AnimatedContent(targetState = selected, label = "nav-selection") { selectedState ->
                                    Icon(
                                        destination.icon,
                                        vitaText(destination.label),
                                        Modifier.padding(if (selectedState) 2.dp else 0.dp),
                                        tint = if (selectedState) MaterialTheme.vita.accentOnInk else MaterialTheme.vita.onInk.copy(alpha = 0.68f),
                                    )
                                }
                            } else {
                                Icon(destination.icon, vitaText(destination.label), tint = if (selected) MaterialTheme.vita.accentOnInk else MaterialTheme.vita.onInk.copy(alpha = 0.68f))
                            }
                        }
                    }
                }
            }
        }
    }
}
