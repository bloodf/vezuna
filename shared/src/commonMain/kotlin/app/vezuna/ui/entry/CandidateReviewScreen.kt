package app.vezuna.ui.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.OcrCandidate
import app.vezuna.domain.ProfileData
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatConfidence
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.localizeDecimalText
import app.vezuna.localization.vitaText
import app.vezuna.ui.AppScreen
import app.vezuna.ui.Category
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.ConfirmDialog
import app.vezuna.ui.components.EmptyState
import app.vezuna.ui.components.FocusedTaskScaffold
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.components.StatusKind
import app.vezuna.ui.components.StatusTag
import app.vezuna.ui.nav.RouteContext
import app.vezuna.ui.observationValueText
import app.vezuna.ui.reportDisplayName
import app.vezuna.ui.tabular
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_error
import vezuna.shared.generated.resources.ic_file_text
import vezuna.shared.generated.resources.ill_record_search

/** How the person settles an ambiguous number: one of its two readings, or a value they type. */
private enum class NumberChoice { WHOLE, DECIMAL, OTHER }

/**
 * One draft (040 numeric, 041 text, 042 ambiguous number, 043 date, 044 duplicate, 045 reject, 148 unavailable).
 * Nothing is confirmed until Confirm succeeds; a duplicate or a failed save keeps everything typed.
 */
@Composable
internal fun CandidateReviewScreen(ctx: RouteContext, candidateId: String) {
    val session = ctx.controller.importSession
    val chosenDates by session.chosenDates.collectAsState()
    val data = ctx.state.data
    val candidate = data?.ocrCandidates?.firstOrNull { it.candidateId == candidateId }
    var busy by remember(candidateId) { mutableStateOf(false) }
    if (data == null) return
    if (candidate == null || (candidate.status != ConfirmationStatus.UNREVIEWED && !busy)) {
        FocusedTaskScaffold(title = vitaText(L10nKey.ENTRY_REVIEW_TITLE), onBack = ctx::back) {
            EmptyState(
                Res.drawable.ill_record_search, vitaText(L10nKey.ENTRY_UNAVAILABLE_TITLE), vitaText(L10nKey.ENTRY_UNAVAILABLE_BODY),
                Modifier.padding(VezunaTokens.Gutter), actionLabel = vitaText(L10nKey.ENTRY_RETURN_TO_QUEUE),
                onAction = { ctx.controller.navigator.finishAndGo(AppScreen.ImportReview) },
            )
        }
        return
    }
    val initial = remember(candidateId) { candidate.toDraft(chosenDates[candidateId]) }
    var draft by rememberSaveable(candidateId, stateSaver = ResultDraftSaver) { mutableStateOf(initial) }
    var choiceName by rememberSaveable(candidateId) { mutableStateOf<String?>(null) }
    val choice = choiceName?.let(NumberChoice::valueOf)
    var showErrors by remember(candidateId) { mutableStateOf(false) }
    var focusTick by remember(candidateId) { mutableIntStateOf(0) }
    var duplicate by remember(candidateId) { mutableStateOf(false) }
    var rejecting by remember(candidateId) { mutableStateOf(false) }
    val sameSource = remember(candidateId) { data.ocrCandidates.filter { it.status == ConfirmationStatus.UNREVIEWED && it.sourceDocumentId == candidate.sourceDocumentId } }
    val position = sameSource.indexOfFirst { it.candidateId == candidateId } + 1
    val token = candidate.rawToken
    val needsChoice = candidate.needsNumberChoice && !draft.asText
    val submitted = when {
        !needsChoice || token == null -> draft
        choice == NumberChoice.WHOLE -> draft.copy(value = readingInput(token, NumberReading.WHOLE))
        choice == NumberChoice.DECIMAL -> draft.copy(value = readingInput(token, NumberReading.DECIMAL))
        else -> draft
    }
    val prefills = listOf(candidate.rawValue.trim(), localizeDecimalText(candidate.rawValue))
    val errors = submitted.validate(requireIdentity = true, prefills = prefills)
    val choiceMissing = needsChoice && choice == null
    val back = guardedBack(draft != initial || choice != null) { ctx.back() }

    fun moveOn() {
        val fresh = ctx.controller.state.value.data
        val next = fresh?.nextPending(candidateId)
        ctx.controller.navigator.finishAndGo(next?.let { AppScreen.CandidateReview(it.candidateId) } ?: AppScreen.ImportReview)
    }

    fun confirm() {
        if (!errors.isEmpty || choiceMissing) {
            showErrors = true
            focusTick++
            return
        }
        busy = true
        duplicate = false
        ctx.scope.launch {
            ctx.controller.confirmCandidate(
                candidateId, submitted.value, submitted.unit, submitted.lower, submitted.upper, submitted.date,
                submitted.code, submitted.name, submitted.asText, submitted.rangeText,
            )
            if (ctx.controller.state.value.error == null) {
                session.confirmed()
                moveOn()
            } else {
                duplicate = ctx.controller.lastErrorWasDuplicate
                busy = false
            }
        }
    }

    fun reject() {
        rejecting = false
        busy = true
        ctx.scope.launch {
            ctx.controller.rejectCandidate(candidateId)
            if (ctx.controller.state.value.error == null) moveOn() else busy = false
        }
    }

    FocusedTaskScaffold(title = vitaText(L10nKey.ENTRY_REVIEW_POSITION, position.coerceAtLeast(1), sameSource.size.coerceAtLeast(1)), onBack = back) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 680.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = VezunaTokens.Gutter, vertical = VezunaTokens.SpaceMd),
                verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
            ) {
                SourceLine(ctx, data, candidate)
                if (needsChoice && token != null) {
                    AmbiguousNumber(token, choice, showErrors && choiceMissing, focusTick) { picked ->
                        choiceName = picked.name
                        draft = when (picked) {
                            NumberChoice.WHOLE -> draft.copy(value = readingLabel(token, NumberReading.WHOLE))
                            NumberChoice.DECIMAL -> draft.copy(value = readingLabel(token, NumberReading.DECIMAL))
                            NumberChoice.OTHER -> draft.copy(value = "")
                        }
                    }
                }
                if (duplicate) {
                    DuplicatePanel(data, submitted, onReject = { reject() }, onKeepEditing = { duplicate = false })
                }
                ResultFieldSet(
                    draft = draft,
                    onChange = { draft = it },
                    errors = if (showErrors) errors else ResultErrors(),
                    valueEnabled = !needsChoice || choice == NumberChoice.OTHER,
                    applyDateToAllLabel = vitaText(L10nKey.ENTRY_APPLY_DATE_TO_REPORT).takeIf { sameSource.size > 1 },
                    onDateChosen = { date, applyToAll ->
                        val ids = if (applyToAll) sameSource.map { it.candidateId } else listOf(candidateId)
                        session.chooseDate(ids, date)
                    },
                    focusFirstError = focusTick,
                )
                Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceXs)) {
                    StatusTag(vitaText(L10nKey.STATUS_NEEDS_REVIEW), StatusKind.NEEDS_REVIEW)
                    Text(vitaText(L10nKey.ENTRY_CHECK_AGAINST_REPORT), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        vitaText(L10nKey.CONFIDENCE, candidate.confidence?.let(::formatConfidence) ?: vitaText(L10nKey.CONFIDENCE_NOT_REPORTED)),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showErrors && (!errors.isEmpty || choiceMissing)) {
                    Text(
                        vitaText(L10nKey.ENTRY_FIX_FIELDS),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
                PrimaryButton(vitaText(L10nKey.CONFIRM_RESULT), ::confirm, Modifier.fillMaxWidth(), loading = busy, enabled = !busy)
                SecondaryButton(vitaText(L10nKey.REJECT), { rejecting = true }, Modifier.fillMaxWidth().padding(top = VezunaTokens.SpaceSm), enabled = !busy)
            }
        }
    }
    if (rejecting) {
        ConfirmDialog(
            title = vitaText(L10nKey.ENTRY_REJECT_TITLE),
            body = vitaText(L10nKey.ENTRY_REJECT_BODY, candidate.displayName, candidate.valueText()),
            confirmLabel = vitaText(L10nKey.ENTRY_REJECT_CONFIRM),
            onConfirm = ::reject,
            onDismiss = { rejecting = false },
        )
    }
}

/** Where the draft came from, with the original file one tap away when it is stored on this device. */
@Composable
private fun SourceLine(ctx: RouteContext, data: ProfileData, candidate: OcrCandidate) {
    val document = data.documents.firstOrNull { it.documentId == candidate.sourceDocumentId }
    val report = data.reports.firstOrNull { it.sourceDocumentId == candidate.sourceDocumentId }
    val name = report?.let { reportDisplayName(it, data, "") }?.ifBlank { null } ?: document?.displayName ?: return
    val canOpen = document?.vaultKey != null
    Column(Modifier.fillMaxWidth()) {
        ListRow(
            name,
            onClick = if (canOpen && document != null) ({ ctx.navigate(AppScreen.SourceViewer(document.documentId)) }) else null,
            supporting = vitaText(if (canOpen) L10nKey.ENTRY_VIEW_ORIGINAL else L10nKey.ENTRY_FROM_SOURCE),
            leadingIcon = Res.drawable.ic_file_text,
            category = Category.RECORDS,
        )
    }
}

/** Image 042: the printed token and both readings; nothing is chosen for the person. */
@Composable
private fun AmbiguousNumber(token: String, choice: NumberChoice?, showError: Boolean, focusTick: Int, onChoose: (NumberChoice) -> Unit) {
    val firstOption = remember { FocusRequester() }
    LaunchedEffect(focusTick) { if (showError) runCatching { firstOption.requestFocus() } }
    Surface(shape = RoundedCornerShape(VezunaTokens.CardRadius), color = Category.CARE.fill, contentColor = Category.CARE.onFill) {
        Column(Modifier.fillMaxWidth().padding(VezunaTokens.SpaceMd), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd)) {
                Icon(painterResource(Res.drawable.ic_error), contentDescription = null, modifier = Modifier.size(28.dp))
                Text(vitaText(L10nKey.ENTRY_CHECK_NUMBER), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            }
            Text(vitaText(L10nKey.ENTRY_CHECK_NUMBER_BODY), style = MaterialTheme.typography.bodyLarge)
            Text(vitaText(L10nKey.ENTRY_PRINTED_TOKEN), style = MaterialTheme.typography.labelLarge)
            Text(token, style = MaterialTheme.typography.headlineMedium.tabular())
        }
    }
    Text(vitaText(L10nKey.ENTRY_SELECT_VALUE), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceXs)) {
        listOf(
            NumberChoice.WHOLE to vitaText(L10nKey.ENTRY_READING_WHOLE, readingLabel(token, NumberReading.WHOLE)),
            NumberChoice.DECIMAL to vitaText(L10nKey.ENTRY_READING_DECIMAL, readingLabel(token, NumberReading.DECIMAL)),
            NumberChoice.OTHER to vitaText(L10nKey.ENTRY_READING_OTHER),
        ).forEachIndexed { index, (option, label) ->
            Row(
                Modifier.fillMaxWidth()
                    .then(if (index == 0) Modifier.focusRequester(firstOption) else Modifier)
                    .heightIn(min = VezunaTokens.RowHeight)
                    .selectable(selected = choice == option, role = Role.RadioButton) { onChoose(option) }
                    .padding(horizontal = VezunaTokens.SpaceSm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd),
            ) {
                RadioButton(selected = choice == option, onClick = null)
                Text(label, style = MaterialTheme.typography.bodyLarge.tabular())
            }
        }
    }
    if (showError) {
        Text(
            vitaText(L10nKey.ENTRY_CHOOSE_READING),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }
}

/** Image 044: what is already recorded for this test on this day, next to the draft. The draft stays editable. */
@Composable
private fun DuplicatePanel(data: ProfileData, draft: ResultDraft, onReject: () -> Unit, onKeepEditing: () -> Unit) {
    val existing = draft.date?.let { data.sameDayResults(draft.code, it) }.orEmpty()
    Surface(shape = RoundedCornerShape(VezunaTokens.CardRadius), color = Category.TRENDS.fill, contentColor = Category.TRENDS.onFill) {
        Column(
            Modifier.fillMaxWidth().padding(VezunaTokens.SpaceMd).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm),
        ) {
            Text(vitaText(L10nKey.ENTRY_DUPLICATE_TITLE), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(vitaText(L10nKey.ENTRY_DUPLICATE_BODY), style = MaterialTheme.typography.bodyLarge)
            existing.forEach { observation ->
                Text(
                    vitaText(L10nKey.ENTRY_DUPLICATE_EXISTING, observation.displayName, observationValueText(observation), formatUiDate(observation.collectedAt)),
                    style = MaterialTheme.typography.bodyLarge.tabular(),
                )
            }
            val draftValue = if (draft.asText) draft.value else "${draft.value} ${draft.unit}".trim()
            Text(vitaText(L10nKey.ENTRY_DUPLICATE_IMPORTED, draft.name, draftValue), style = MaterialTheme.typography.bodyLarge.tabular())
        }
    }
    PrimaryButton(vitaText(L10nKey.ENTRY_DUPLICATE_SKIP), onReject, Modifier.fillMaxWidth())
    SecondaryButton(vitaText(L10nKey.ENTRY_KEEP_EDITING), onKeepEditing, Modifier.fillMaxWidth())
}
