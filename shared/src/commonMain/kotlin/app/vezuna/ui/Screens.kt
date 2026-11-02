package app.vezuna.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vezuna.domain.AnalyteConversions
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabComparator
import app.vezuna.domain.LabObservation
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatConfidence
import app.vezuna.localization.formatLabValue
import app.vezuna.localization.localizeDecimalText
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.vitaPlural
import app.vezuna.localization.vitaText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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

/** UI-only reference label: the report's own text wins, bounds use the language's number format. */
@Composable
internal fun uiRangeText(range: app.vezuna.domain.ReferenceRange): String {
    if (range.text != null) return range.text
    if (range.lower == null && range.upper == null) return vitaText(L10nKey.REFERENCE_NOT_PROVIDED)
    val unit = range.unit.orEmpty()
    val lower = range.lower?.let { formatLabValue(it) }
    val upper = range.upper?.let { formatLabValue(it) }
    return when {
        lower == null -> "≤ $upper $unit"
        upper == null -> "≥ $lower $unit"
        else -> "$lower–$upper $unit"
    }.trim()
}

@Composable
internal fun observationValueText(observation: LabObservation): String =
    observation.qualitativeValue ?: "${formatLabValue(observation.originalValue, observation.valueDecimals)} ${observation.originalUnit}".trim()

@Composable
internal fun ResultRow(observation: LabObservation, onClick: () -> Unit) {
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
            Text(observationValueText(observation), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
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
    ScreenColumn(vitaText(L10nKey.IMPORT_REVIEW), vitaText(L10nKey.IMPORT_REVIEW_SUBTITLE)) {
        if (sourcePicker == null) {
            EmptyState(vitaText(L10nKey.NATIVE_SOURCE_PICKER), vitaText(L10nKey.NATIVE_SOURCE_PICKER_EXPLANATION), Icons.Rounded.UploadFile)
        } else {
            SectionHeading(vitaText(L10nKey.SECTION_AUTOMATIC_EXTRACTION))
            VitaButton(vitaText(L10nKey.CHOOSE_SOURCE_EXTRACT_ALL), Icons.Rounded.UploadFile, {
                scope.launch { sourcePicker()?.let { controller.importSource(it) } }
            }, enabled = !state.loading)
        }
        ManualResultForm(controller, scope)
        val pending = data.ocrCandidates.filter { it.status == ConfirmationStatus.UNREVIEWED }
        val rejected = data.ocrCandidates.filter { it.status == ConfirmationStatus.REJECTED }
        var showRejected by remember { mutableStateOf(false) }
        if (rejected.isNotEmpty() || showRejected) {
            FilterChip(selected = showRejected, onClick = { showRejected = !showRejected }, label = { Text("${vitaText(L10nKey.REJECTED_FILTER)} (${rejected.size})") })
        }
        if (showRejected) {
            RejectedCandidates(rejected, controller, scope)
            return@ScreenColumn
        }
        SectionHeading(vitaPlural(L10nKey.PENDING_CANDIDATES, pending.size))
        if (pending.isEmpty()) EmptyState(vitaText(L10nKey.NOTHING_AWAITING_REVIEW), vitaText(L10nKey.REVIEW_TRACKING), Icons.Rounded.FactCheck)
        var chosenDates by remember { mutableStateOf(emptyMap<String, kotlinx.datetime.Instant>()) }
        pending.forEach { candidate ->
            val date = chosenDates[candidate.candidateId] ?: candidate.collectedAt.takeIf { candidate.collectedAtKnown }
            CandidateEditor(
                candidate = candidate,
                date = date,
                onDate = { chosenDates = chosenDates + (candidate.candidateId to it) },
                onApplyDateToReport = {
                    val sameReport = pending.filter { it.sourceDocumentId == candidate.sourceDocumentId }
                    date?.let { picked -> chosenDates = chosenDates + sameReport.associate { it.candidateId to picked } }
                },
                controller = controller,
                scope = scope,
            )
        }
    }
}

@Composable
private fun CandidateEditor(
    candidate: app.vezuna.domain.OcrCandidate,
    date: kotlinx.datetime.Instant?,
    onDate: (kotlinx.datetime.Instant) -> Unit,
    onApplyDateToReport: () -> Unit,
    controller: AppController,
    scope: CoroutineScope,
) {
    var code by remember(candidate.candidateId) { mutableStateOf(candidate.testCode) }
    var name by remember(candidate.candidateId) { mutableStateOf(candidate.displayName) }
    var value by remember(candidate.candidateId) { mutableStateOf(if (candidate.looksQualitative) candidate.rawValue else localizeDecimalText(candidate.rawValue)) }
    var unit by remember(candidate.candidateId) { mutableStateOf(candidate.rawUnit) }
    var asText by remember(candidate.candidateId) { mutableStateOf(candidate.looksQualitative) }
    var lower by remember(candidate.candidateId) { mutableStateOf(candidate.referenceRange.lower?.let { formatLabValue(it) }.orEmpty()) }
    var upper by remember(candidate.candidateId) { mutableStateOf(candidate.referenceRange.upper?.let { formatLabValue(it) }.orEmpty()) }
    var rangeText by remember(candidate.candidateId) { mutableStateOf(candidate.referenceRange.text.orEmpty()) }
    Card(Modifier.fillMaxWidth().vitaAnimateContentSize(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WarningAmber, vitaText(L10nKey.UNREVIEWED_OCR_CANDIDATE), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.size(10.dp))
                Spacer(Modifier.weight(1f))
                StatusPill(vitaText(L10nKey.DRAFT), Icons.Rounded.FactCheck, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Text(vitaText(L10nKey.CONFIDENCE, candidate.confidence?.let(::formatConfidence) ?: vitaText(L10nKey.CONFIDENCE_NOT_REPORTED)), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.TEST_NAME)) }, singleLine = true)
            OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.TEST_CODE_EXAMPLE)) }, singleLine = true)
            CollectionDateField(date, onDate, onApplyDateToReport)
            if (date == null) Text(vitaText(L10nKey.COLLECTION_DATE_REQUIRED), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            FilterChip(selected = asText, onClick = { asText = !asText }, label = { Text(vitaText(L10nKey.TEXT_RESULT_TOGGLE)) })
            if (candidate.rawToken != null && !asText) {
                Text(vitaText(L10nKey.OCR_READ_TOKEN, candidate.rawToken!!), style = MaterialTheme.typography.bodyMedium)
                Text(vitaText(L10nKey.AMBIGUOUS_NUMBER_WARNING), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (asText) {
                OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.CONFIRMED_QUALITATIVE_VALUE)) }, singleLine = true)
            } else {
                OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.CONFIRMED_NUMERIC_VALUE)) }, singleLine = true)
                OutlinedTextField(unit, { unit = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.CONFIRMED_ORIGINAL_UNIT)) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(lower, { lower = it }, Modifier.weight(1f), label = { Text(vitaText(L10nKey.REFERENCE_LOWER)) }, singleLine = true)
                    OutlinedTextField(upper, { upper = it }, Modifier.weight(1f), label = { Text(vitaText(L10nKey.REFERENCE_UPPER)) }, singleLine = true)
                }
            }
            OutlinedTextField(rangeText, { rangeText = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.REFERENCE_TEXT_OPTIONAL)) }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { controller.confirmCandidate(candidate.candidateId, value, unit, lower, upper, date, code, name, asText, rangeText) } }, enabled = date != null && code.isNotBlank() && name.isNotBlank(), modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text(vitaText(L10nKey.CONFIRM_RESULT)) }
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
        !it.isQualitative && !observation.isQualitative && it.confirmation == ConfirmationStatus.CONFIRMED && it.collectedAt < observation.collectedAt &&
            app.vezuna.domain.UnitNormalizer.canonicalTestCode(it.testCode) == app.vezuna.domain.UnitNormalizer.canonicalTestCode(observation.testCode)
    }.maxByOrNull { it.collectedAt }
    val comparison = previous?.let { LabComparator.compare(it, observation) }
    var editing by remember(observationId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (editing) {
        ResultEditDialog(
            observation,
            onSave = { edit ->
                editing = false
                scope.launch { controller.editObservation(observationId, edit.value, edit.unit, edit.lower, edit.upper, edit.rangeText, edit.date) }
            },
            onDismiss = { editing = false },
        )
    }
    ScreenColumn(observation.displayName, "${observation.testCode} · ${formatUiDate(observation.collectedAt)}") {
        StatusPill(vitaText(L10nKey.CONFIRMED_RESULT), Icons.Rounded.CheckCircle, MaterialTheme.vita.successContainer, MaterialTheme.vita.onSuccessContainer)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(20.dp)) {
                Text(vitaText(L10nKey.ORIGINAL_RESULT), style = MaterialTheme.typography.labelLarge)
                Text(observationValueText(observation), style = MaterialTheme.typography.headlineMedium)
                if (!observation.isQualitative && (observation.normalizedUnit != observation.originalUnit || observation.normalizedValue != observation.originalValue)) {
                    Text(vitaText(L10nKey.NORMALIZED, formatLabValue(observation.normalizedValue), observation.normalizedUnit), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (!observation.isQualitative) {
            AnalyteConversions.alternatives(observation.testCode, observation.originalValue, observation.originalUnit).forEach { alternative ->
                Text(
                    vitaText(L10nKey.CONVERTED_VALUE, "${formatLabValue(alternative.value)} ${alternative.unit}", formatLabValue(alternative.factor)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        SectionHeading(vitaText(L10nKey.CLINICAL_CONTEXT))
        Text(vitaText(L10nKey.PER_RESULT_REFERENCE, uiRangeText(observation.referenceRange)))
        Text(vitaText(L10nKey.RANGE_ASSESSMENT, rangeLabel(LabComparator.assess(observation))))
        Text(vitaText(L10nKey.CHANGE_FROM_PREVIOUS, if (comparison == null) vitaText(L10nKey.NO_PREVIOUS_RESULT) else changeLabel(comparison.direction)))
        SourceReportInfo(observation, data)
        VitaButton(vitaText(L10nKey.EDIT_RESULT), Icons.Rounded.Edit, { editing = true }, outlined = true)
        ResultHistory(observation)
        LinkRow(vitaText(L10nKey.BACK_TO_LAB_RECORDS)) { controller.navigate(AppScreen.Labs) }
    }
}

@Composable
private fun RejectedCandidates(rejected: List<app.vezuna.domain.OcrCandidate>, controller: AppController, scope: CoroutineScope) {
    if (rejected.isEmpty()) InlineNotice(vitaText(L10nKey.REJECTED_CANDIDATES_NONE))
    rejected.forEach { candidate ->
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(candidate.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("${candidate.rawValue} ${candidate.rawUnit}".trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton({ scope.launch { controller.restoreCandidate(candidate.candidateId) } }, Modifier.defaultMinSize(minHeight = 48.dp)) {
                    Text(vitaText(L10nKey.RESTORE_CANDIDATE))
                }
            }
        }
    }
}
