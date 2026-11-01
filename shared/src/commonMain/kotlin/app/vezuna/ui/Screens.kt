package app.vezuna.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabComparator
import app.vezuna.domain.LabObservation
import app.vezuna.domain.Profile
import app.vezuna.domain.TrendPreparation
import app.vezuna.domain.TrendSeries
import app.vezuna.export.DoctorExportContent
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.formatUiDateTime
import app.vezuna.localization.vitaPlural
import app.vezuna.localization.vitaText
import app.vezuna.platform.currentInstant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(state: AppUiState, controller: AppController, scope: CoroutineScope) {
    val data = state.data ?: return EmptyState(vitaText(L10nKey.NO_PROFILE_SELECTED), vitaText(L10nKey.NO_UPCOMING_DOSES_EXPLANATION))
    var newProfile by remember { mutableStateOf("") }
    var manageProfiles by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Profile?>(null) }
    var deleteTarget by remember { mutableStateOf<Profile?>(null) }
    val motionEnabled = LocalVitaAnimationsEnabled.current
    val reviewCount = data.ocrCandidates.count { it.status == ConfirmationStatus.UNREVIEWED }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            FlowRow(Modifier.fillMaxWidth().padding(end = 56.dp), verticalArrangement = Arrangement.Center) {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.vita.lilacAura) {
                    Icon(Icons.Rounded.Group, vitaText(L10nKey.CURRENT_PROFILE), Modifier.padding(10.dp).size(26.dp), tint = MaterialTheme.vita.onAura)
                }
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(data.profile.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(vitaText(L10nKey.HOUSEHOLD_PRIVATE), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { manageProfiles = !manageProfiles }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                    Text(vitaText(if (manageProfiles) L10nKey.HIDE_PROFILES else L10nKey.MANAGE_PROFILES))
                }
            }
            androidx.compose.material3.IconButton(onClick = { controller.navigate(AppScreen.Settings) }, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Rounded.Settings, vitaText(L10nKey.SETTINGS))
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = manageProfiles,
            enter = if (motionEnabled) androidx.compose.animation.fadeIn() else androidx.compose.animation.EnterTransition.None,
            exit = if (motionEnabled) androidx.compose.animation.fadeOut() else androidx.compose.animation.ExitTransition.None,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.profiles.forEach { profile ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { scope.launch { controller.switchProfile(profile.profileId) } },
                            enabled = profile.profileId != data.profile.profileId && !state.loading,
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                        ) {
                            Text(if (profile.profileId == data.profile.profileId) vitaText(L10nKey.CURRENT_PROFILE) + " — ${profile.displayName}" else vitaText(L10nKey.SWITCH_TO_PROFILE, profile.displayName))
                        }
                        IconButton(onClick = { renameTarget = profile }, enabled = !state.loading, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.Edit, vitaText(L10nKey.RENAME_PROFILE) + " — ${profile.displayName}")
                        }
                        IconButton(onClick = { deleteTarget = profile }, enabled = !state.loading && state.profiles.size > 1, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.Delete, vitaText(L10nKey.DELETE_PROFILE) + " — ${profile.displayName}")
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newProfile,
                        onValueChange = { newProfile = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(vitaText(L10nKey.NEW_PROFILE_NAME)) },
                        leadingIcon = { Icon(Icons.Rounded.Group, null) },
                        singleLine = true,
                    )
                    Button(
                        onClick = { scope.launch { controller.createProfile(newProfile); newProfile = "" } },
                        enabled = newProfile.isNotBlank(),
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    ) {
                        Icon(Icons.Rounded.Add, vitaText(L10nKey.ADD_PROFILE))
                        Spacer(Modifier.size(4.dp))
                        Text(vitaText(L10nKey.ADD))
                    }
                }
            }
        }
        renameTarget?.let { profile ->
            TextInputDialog(
                title = vitaText(L10nKey.RENAME_PROFILE),
                label = vitaText(L10nKey.NEW_PROFILE_NAME),
                initial = profile.displayName,
                onConfirm = { name -> renameTarget = null; scope.launch { controller.renameProfile(profile.profileId, name) } },
                onDismiss = { renameTarget = null },
            )
        }
        deleteTarget?.let { profile ->
            TypeToConfirmDialog(
                title = vitaText(L10nKey.DELETE_PROFILE),
                message = vitaText(L10nKey.DELETE_PROFILE_WARNING, profile.displayName),
                label = vitaText(L10nKey.PROFILE_NAME_CONFIRMATION),
                expected = profile.displayName.trim(),
                confirmLabel = vitaText(L10nKey.DELETE),
                onConfirm = { typed -> deleteTarget = null; scope.launch { controller.deleteProfile(profile.profileId, typed) } },
                onDismiss = { deleteTarget = null },
            )
        }
        Text(vitaText(L10nKey.HOME_HERO), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
        PrivacyAura()
        DashboardToday(state.dashboard, controller, scope)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WorkflowTile(vitaText(L10nKey.WORKFLOW_IMPORT_REVIEW), Icons.Rounded.UploadFile, MaterialTheme.vita.mintAura, { controller.navigate(AppScreen.ImportReview) }, Modifier.weight(1f))
            WorkflowTile(vitaText(L10nKey.WORKFLOW_TRENDS), Icons.Rounded.ShowChart, MaterialTheme.vita.lilacAura, { controller.navigate(AppScreen.Trends) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WorkflowTile(vitaText(L10nKey.MEDICATION), Icons.Rounded.Medication, MaterialTheme.vita.apricotAura, { controller.navigate(AppScreen.Medications) }, Modifier.weight(1f))
            WorkflowTile(vitaText(L10nKey.AI), Icons.Rounded.AutoAwesome, MaterialTheme.vita.skyAura, { controller.navigate(AppScreen.AiChat) }, Modifier.weight(1f))
        }
        if (reviewCount > 0) {
            InlineNotice(vitaPlural(L10nKey.REVIEW_CANDIDATES_NOTICE, reviewCount), Icons.Rounded.FactCheck)
        }
        SectionHeading(vitaText(L10nKey.RECENT_RESULTS), vitaText(L10nKey.RECENT_RESULTS_SUBTITLE))
        val recentResults = state.dashboard?.recentResults.orEmpty()
        if (recentResults.isEmpty()) {
            EmptyState(vitaText(L10nKey.NO_CONFIRMED_RESULTS_YET), vitaText(L10nKey.TREND_INCOMPATIBLE_UNITS), Icons.Rounded.Science)
        } else {
            recentResults.forEach { recent ->
                ResultRow(recent.observation) { controller.navigate(AppScreen.ResultDetail(recent.observation.observationId)) }
                Text(
                    vitaText(L10nKey.PREVIOUS_CHANGE_CONTEXT, changeLabel(recent.changeFromPrevious)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun changeLabel(direction: app.vezuna.domain.ChangeDirection): String = vitaText(when (direction) {
    app.vezuna.domain.ChangeDirection.INCREASE -> L10nKey.CHANGE_INCREASE
    app.vezuna.domain.ChangeDirection.DECREASE -> L10nKey.CHANGE_DECREASE
    app.vezuna.domain.ChangeDirection.UNCHANGED -> L10nKey.CHANGE_UNCHANGED
    app.vezuna.domain.ChangeDirection.NOT_COMPARABLE -> L10nKey.CHANGE_NOT_COMPARABLE
})

@Composable
private fun rangeLabel(assessment: app.vezuna.domain.RangeAssessment): String = vitaText(when (assessment) {
    app.vezuna.domain.RangeAssessment.BELOW -> L10nKey.RANGE_BELOW
    app.vezuna.domain.RangeAssessment.WITHIN -> L10nKey.RANGE_WITHIN
    app.vezuna.domain.RangeAssessment.ABOVE -> L10nKey.RANGE_ABOVE
    app.vezuna.domain.RangeAssessment.UNKNOWN -> L10nKey.RANGE_UNKNOWN
})

/** UI-only missing-reference label; all clinical formatting still comes from the export-neutral model. */
@Composable
private fun uiRangeText(range: app.vezuna.domain.ReferenceRange): String =
    if (range.text == null && range.lower == null && range.upper == null) {
        vitaText(L10nKey.REFERENCE_NOT_PROVIDED)
    } else {
        DoctorExportContent.rangeText(range)
    }

@Composable
private fun DashboardToday(snapshot: app.vezuna.domain.DashboardSnapshot?, controller: AppController, scope: CoroutineScope) {
    snapshot ?: return
    SectionHeading(vitaText(L10nKey.TODAY))
    val next = snapshot.nextDose
    if (next == null) {
        EmptyState(vitaText(L10nKey.NO_UPCOMING_DOSES), vitaText(L10nKey.NO_UPCOMING_DOSES_EXPLANATION), Icons.Rounded.Medication)
        LinkRow(vitaText(L10nKey.ADD_MEDICATION)) { controller.navigate(AppScreen.Medications) }
    } else {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.vita.apricotAura)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Schedule, vitaText(L10nKey.NEXT_DOSE), tint = MaterialTheme.vita.onAura)
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(next.medicationName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.vita.onAura)
                        next.strength?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.vita.onAura) }
                        Text(formatUiDateTime(next.scheduledFor), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.vita.onAura)
                    }
                }
                next.instructions?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.vita.onAura) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VitaButton(vitaText(L10nKey.TAKEN), Icons.Rounded.CheckCircle, { scope.launch { controller.takeDose(next) } }, Modifier.weight(1f))
                    VitaButton(vitaText(L10nKey.SKIP), Icons.Rounded.WarningAmber, { scope.launch { controller.skipDose(next) } }, Modifier.weight(1f), outlined = true)
                }
                VitaButton(vitaText(L10nKey.MINUTES_SNOOZE), Icons.Rounded.Schedule, { scope.launch { controller.snoozeDose(next) } }, outlined = true)
            }
        }
    }
    val later = snapshot.upcoming.drop(1)
    if (later.isNotEmpty()) {
        SectionHeading(vitaText(L10nKey.NEXT_REMINDERS))
        later.forEach { dose ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Medication, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Text(dose.medicationName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(formatUiDateTime(dose.scheduledFor), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    val fraction = snapshot.adherence.fraction
    SectionHeading(vitaText(L10nKey.ADHERENCE_30_DAY))
    if (fraction == null) {
        Text(vitaText(L10nKey.NO_DOSE_EVENTS), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text(vitaText(L10nKey.ADHERENCE_SUMMARY, (fraction * 100).toInt(), snapshot.adherence.taken, snapshot.adherence.completed), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun LabsScreen(state: AppUiState, controller: AppController) {
    val data = state.data ?: return EmptyState(vitaText(L10nKey.NO_LABS), vitaText(L10nKey.SELECT_PROFILE_FIRST))
    val observations = data.observations.filter { it.confirmation == ConfirmationStatus.CONFIRMED }.sortedByDescending { it.collectedAt }
    ScreenColumn(vitaText(L10nKey.LAB_RECORDS), vitaText(L10nKey.LAB_RECORDS_SUBTITLE)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VitaButton(vitaText(L10nKey.IMPORT_REVIEW), Icons.Rounded.UploadFile, { controller.navigate(AppScreen.ImportReview) }, Modifier.weight(1f))
            VitaButton(vitaText(L10nKey.TRENDS), Icons.Rounded.Insights, { controller.navigate(AppScreen.Trends) }, Modifier.weight(0.7f), outlined = true)
        }
        if (observations.isEmpty()) {
            EmptyState(vitaText(L10nKey.NO_CONFIRMED_RESULTS), vitaText(L10nKey.TREND_INCOMPATIBLE_UNITS), Icons.Rounded.Science)
        } else observations.forEach { observation ->
            ResultRow(observation) { controller.navigate(AppScreen.ResultDetail(observation.observationId)) }
        }
    }
}

@Composable
private fun ResultRow(observation: LabObservation, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().vitaAnimateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Rounded.Science, vitaText(L10nKey.CONFIRMED_RESULT) + ": ${observation.displayName}", Modifier.padding(8.dp).size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(observation.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(observation.testCode, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(vitaText(L10nKey.CONFIRMED), Icons.Rounded.CheckCircle, MaterialTheme.vita.successContainer, MaterialTheme.vita.onSuccessContainer)
            }
            Text("${observation.originalValue} ${observation.originalUnit}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(vitaText(L10nKey.REFERENCE, uiRangeText(observation.referenceRange)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(5.dp))
                Text(formatUiDate(observation.collectedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                TextButton(onClick, Modifier.defaultMinSize(minHeight = 48.dp)) {
                    Text(vitaText(L10nKey.RESULT_DETAILS))
                    Icon(Icons.Rounded.ArrowForward, null, Modifier.size(17.dp))
                }
            }
        }
    }
}

@Composable
fun ImportReviewScreen(state: AppUiState, controller: AppController, scope: CoroutineScope, sourcePicker: (suspend () -> PickedSource?)?) {
    val data = state.data ?: return EmptyState(vitaText(L10nKey.NO_PROFILE), vitaText(L10nKey.SELECT_PROFILE_FIRST))
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var referenceLower by remember { mutableStateOf("") }
    var referenceUpper by remember { mutableStateOf("") }
    ScreenColumn(vitaText(L10nKey.IMPORT_REVIEW), vitaText(L10nKey.IMPORT_REVIEW_SUBTITLE)) {
        if (sourcePicker == null) {
            EmptyState(vitaText(L10nKey.NATIVE_SOURCE_PICKER), vitaText(L10nKey.NATIVE_SOURCE_PICKER_EXPLANATION), Icons.Rounded.UploadFile)
        } else {
            SectionHeading(vitaText(L10nKey.SECTION_AUTOMATIC_EXTRACTION))
            VitaButton(vitaText(L10nKey.CHOOSE_SOURCE_EXTRACT_ALL), Icons.Rounded.UploadFile, {
                scope.launch { sourcePicker()?.let { controller.importSource(it) } }
            }, enabled = !state.loading)
            SectionHeading(vitaText(L10nKey.MANUAL_SINGLE_RESULT))
            OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.TEST_CODE_EXAMPLE)) }, singleLine = true)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.TEST_NAME)) }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value, { value = it }, Modifier.weight(1f), label = { Text(vitaText(L10nKey.EXTRACTED_VALUE)) }, singleLine = true)
                OutlinedTextField(unit, { unit = it }, Modifier.weight(1f), label = { Text(vitaText(L10nKey.EXTRACTED_UNIT)) }, singleLine = true)
            }
            OutlinedTextField(referenceLower, { referenceLower = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.REFERENCE_LOWER_OPTIONAL)) }, singleLine = true)
            OutlinedTextField(referenceUpper, { referenceUpper = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.REFERENCE_UPPER_OPTIONAL)) }, singleLine = true)
            VitaButton(vitaText(L10nKey.CHOOSE_SOURCE_CREATE_MANUAL), Icons.Rounded.Add, {
                scope.launch {
                    val source = sourcePicker() ?: return@launch
                    controller.importCandidate(source.fileName, source.mediaType, source.bytes, code, name, value, unit, referenceLower, referenceUpper, currentInstant())
                }
            }, enabled = code.isNotBlank() && name.isNotBlank() && value.isNotBlank() && unit.isNotBlank())
        }
        val pending = data.ocrCandidates.filter { it.status == ConfirmationStatus.UNREVIEWED }
        SectionHeading(vitaPlural(L10nKey.PENDING_CANDIDATES, pending.size))
        if (pending.isEmpty()) EmptyState(vitaText(L10nKey.NOTHING_AWAITING_REVIEW), vitaText(L10nKey.REVIEW_TRACKING), Icons.Rounded.FactCheck)
        pending.forEach { candidate -> CandidateEditor(candidate, controller, scope) }
    }
}

@Composable
private fun CandidateEditor(candidate: app.vezuna.domain.OcrCandidate, controller: AppController, scope: CoroutineScope) {
    var value by remember(candidate.candidateId) { mutableStateOf(candidate.rawValue) }
    var unit by remember(candidate.candidateId) { mutableStateOf(candidate.rawUnit) }
    var lower by remember(candidate.candidateId) { mutableStateOf(candidate.referenceRange.lower?.toString().orEmpty()) }
    var upper by remember(candidate.candidateId) { mutableStateOf(candidate.referenceRange.upper?.toString().orEmpty()) }
    Card(Modifier.fillMaxWidth().vitaAnimateContentSize(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WarningAmber, vitaText(L10nKey.UNREVIEWED_OCR_CANDIDATE), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(candidate.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(candidate.testCode, style = MaterialTheme.typography.labelSmall)
                }
                StatusPill(vitaText(L10nKey.DRAFT), Icons.Rounded.FactCheck, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Text(vitaText(L10nKey.CONFIDENCE, candidate.confidence?.toString() ?: vitaText(L10nKey.CONFIDENCE_NOT_REPORTED)), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.CONFIRMED_NUMERIC_VALUE)) }, singleLine = true)
            OutlinedTextField(unit, { unit = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.CONFIRMED_ORIGINAL_UNIT)) }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(lower, { lower = it }, Modifier.weight(1f), label = { Text(vitaText(L10nKey.REFERENCE_LOWER)) }, singleLine = true)
                OutlinedTextField(upper, { upper = it }, Modifier.weight(1f), label = { Text(vitaText(L10nKey.REFERENCE_UPPER)) }, singleLine = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { controller.confirmCandidate(candidate.candidateId, value, unit, lower, upper) } }, Modifier.defaultMinSize(minHeight = 48.dp)) { Text(vitaText(L10nKey.CONFIRM_RESULT)) }
                OutlinedButton(onClick = { scope.launch { controller.rejectCandidate(candidate.candidateId) } }, Modifier.defaultMinSize(minHeight = 48.dp)) { Text(vitaText(L10nKey.REJECT)) }
            }
        }
    }
}

@Composable
fun ResultDetailScreen(state: AppUiState, observationId: String, controller: AppController) {
    val data = state.data ?: return
    val observation = data.observations.firstOrNull { it.observationId == observationId }
        ?: return EmptyState(vitaText(L10nKey.RESULT_UNAVAILABLE), vitaText(L10nKey.RESULT_UNAVAILABLE_EXPLANATION))
    val previous = data.observations.filter {
        it.confirmation == ConfirmationStatus.CONFIRMED && it.collectedAt < observation.collectedAt && it.testCode.equals(observation.testCode, true)
    }.maxByOrNull { it.collectedAt }
    val comparison = previous?.let { LabComparator.compare(it, observation) }
    ScreenColumn(observation.displayName, "${observation.testCode} · ${formatUiDate(observation.collectedAt)}") {
        StatusPill(vitaText(L10nKey.CONFIRMED_RESULT), Icons.Rounded.CheckCircle, MaterialTheme.vita.successContainer, MaterialTheme.vita.onSuccessContainer)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(20.dp)) {
                Text(vitaText(L10nKey.ORIGINAL_RESULT), style = MaterialTheme.typography.labelLarge)
                Text("${observation.originalValue} ${observation.originalUnit}", style = MaterialTheme.typography.headlineMedium)
                if (observation.normalizedUnit != observation.originalUnit || observation.normalizedValue != observation.originalValue) {
                    Text(vitaText(L10nKey.NORMALIZED, observation.normalizedValue, observation.normalizedUnit), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        SectionHeading(vitaText(L10nKey.CLINICAL_CONTEXT))
        Text(vitaText(L10nKey.PER_RESULT_REFERENCE, uiRangeText(observation.referenceRange)))
        Text(vitaText(L10nKey.RANGE_ASSESSMENT, rangeLabel(LabComparator.assess(observation))))
        Text(vitaText(L10nKey.CHANGE_FROM_PREVIOUS, if (comparison == null) vitaText(L10nKey.NO_PREVIOUS_RESULT) else changeLabel(comparison.direction)))
        Text(vitaText(L10nKey.SOURCE_REPORT, observation.reportId), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinkRow(vitaText(L10nKey.BACK_TO_LAB_RECORDS)) { controller.navigate(AppScreen.Labs) }
    }
}

@Composable
fun TrendsScreen(state: AppUiState, controller: AppController) {
    val data = state.data ?: return EmptyState(vitaText(L10nKey.NO_TRENDS), vitaText(L10nKey.SELECT_PROFILE_FIRST))
    val codes = data.observations.filter { it.confirmation == ConfirmationStatus.CONFIRMED }.map { it.testCode }.distinct().sorted()
    var selected by remember(data.profile.profileId) { mutableStateOf(codes.firstOrNull().orEmpty()) }
    if (selected !in codes) selected = codes.firstOrNull().orEmpty()
    ScreenColumn(vitaText(L10nKey.LAB_TRENDS), vitaText(L10nKey.LAB_TRENDS_SUBTITLE)) {
        if (codes.isEmpty()) {
            EmptyState(vitaText(L10nKey.NO_CONFIRMED_TREND_DATA), vitaText(L10nKey.TREND_INCOMPATIBLE_UNITS), Icons.Rounded.ShowChart)
        } else {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                codes.forEach { code ->
                    FilterChip(selected = selected == code, onClick = { selected = code }, label = { Text(code) }, leadingIcon = { Icon(Icons.Rounded.Science, null, Modifier.size(17.dp)) })
                }
            }
            val series = TrendPreparation.forTest(data.profile.profileId, selected, data.observations)
            val motionEnabled = LocalVitaAnimationsEnabled.current
            AnimatedContent(
                targetState = series,
                transitionSpec = {
                    if (motionEnabled) androidx.compose.animation.fadeIn() togetherWith androidx.compose.animation.fadeOut()
                    else androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                },
                label = "trend-selection",
            ) { current ->
                if (current != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(18.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(current.points.last().observation.displayName, style = MaterialTheme.typography.titleLarge)
                                        Text(vitaPlural(L10nKey.CONFIRMED_POINTS, current.points.size, current.unit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Icon(Icons.Rounded.Insights, vitaText(L10nKey.TREND_CHART), tint = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(Modifier.height(12.dp))
                                TrendChart(current, Modifier.fillMaxWidth().height(210.dp))
                                Row {
                                    Text(formatUiDate(current.points.first().observation.collectedAt), style = MaterialTheme.typography.labelSmall)
                                    Spacer(Modifier.weight(1f))
                                    Text(formatUiDate(current.points.last().observation.collectedAt), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        SectionHeading(vitaText(L10nKey.CONFIRMED_DATA), vitaText(L10nKey.CONFIRMED_DATA_SUBTITLE))
                        current.points.forEach { point ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.History, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.size(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(formatUiDate(point.observation.collectedAt), style = MaterialTheme.typography.bodyMedium)
                                    Text(vitaText(L10nKey.REFERENCE, uiRangeText(point.observation.referenceRange)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("${point.y} ${current.unit}", style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            }
        }
        LinkRow(vitaText(L10nKey.BACK_TO_LABS)) { controller.navigate(AppScreen.Labs) }
    }
}

@Composable
private fun TrendChart(series: TrendSeries, modifier: Modifier) {
    val description = vitaPlural(L10nKey.TREND_CHART_ACCESSIBILITY, series.points.size, series.unit)
    LineChart(series.points.map { it.xEpochSeconds to it.y }, "${series.testCode}|${series.unit}", description, modifier)
}

/** Points are (epoch seconds, value) sorted by time. [identity] restarts the draw-in animation when it changes. */
@Composable
internal fun LineChart(points: List<Pair<Long, Double>>, identity: String, description: String, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val pointCenter = MaterialTheme.colorScheme.surface
    val motionEnabled = LocalVitaAnimationsEnabled.current
    val progress = remember(identity) { Animatable(if (motionEnabled) 0.2f else 1f) }
    LaunchedEffect(identity, points.size) {
        if (motionEnabled) progress.animateTo(1f, tween(VezunaTokens.MotionNormalMs)) else progress.snapTo(1f)
    }
    Canvas(modifier.semantics { contentDescription = description }) {
        if (points.isEmpty()) return@Canvas
        repeat(4) { index ->
            val y = size.height * index / 3f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        val min = points.minOf { it.second }
        val max = points.maxOf { it.second }
        val spread = (max - min).takeIf { it > 0.0 } ?: 1.0
        val first = points.first().first
        val last = points.last().first
        val offsets = points.map { (x, y) ->
            val xFraction = if (first == last) 0.5f else (x - first).toFloat() / (last - first).toFloat()
            Offset(xFraction * size.width, size.height - ((y - min) / spread).toFloat() * size.height)
        }
        offsets.zipWithNext().forEachIndexed { index, pair ->
            if ((index + 1).toFloat() / offsets.lastIndex <= progress.value) {
                drawLine(color, pair.first, pair.second, strokeWidth = 4f)
            }
        }
        offsets.forEachIndexed { index, point ->
            if (offsets.size == 1 || index.toFloat() / offsets.lastIndex <= progress.value) {
                drawCircle(color, radius = 6f, center = point)
                drawCircle(pointCenter, radius = 2.5f, center = point)
            }
        }
    }
}
