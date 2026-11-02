package app.vezuna.ui.care

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import app.vezuna.domain.Medication
import app.vezuna.domain.MedicationSupplies
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.localization.vitaText
import app.vezuna.platform.currentInstant
import app.vezuna.ui.ScheduleDraft
import app.vezuna.ui.ScheduleListEditor
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.LabeledField
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.TertiaryButton
import app.vezuna.ui.nav.RouteContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Errors the form shows next to its fields; schedule errors come from [ScheduleDraft.errors] inside the editor. */
internal fun medicationFormValid(name: String, drafts: List<ScheduleDraft>): Boolean =
    name.isNotBlank() && drafts.isNotEmpty() && drafts.all { it.errors().isEmpty() }

/**
 * Add or edit a medication and its schedules (058-060, 146) as a focused form. Reuses the schedule editor with the
 * native time picker; the controller sets trackedSince when times or recurrence change. Entries survive errors.
 */
@Composable
internal fun MedicationFormBody(ctx: RouteContext, medication: Medication?) {
    val today = remember { currentInstant().toLocalDateTime(TimeZone.currentSystemDefault()).date }
    var name by rememberSaveable { mutableStateOf(medication?.displayName.orEmpty()) }
    var strength by rememberSaveable { mutableStateOf(medication?.strength.orEmpty()) }
    var instructions by rememberSaveable { mutableStateOf(medication?.instructions.orEmpty()) }
    val data = ctx.state.data
    val unitsShown = remember { medication?.let { m -> data?.let { MedicationSupplies.remaining(m, it.doseEvents) } }?.toString() }
    var supplyUnits by rememberSaveable { mutableStateOf(unitsShown.orEmpty()) }
    var lowThreshold by rememberSaveable { mutableStateOf(medication?.supply?.lowThreshold?.toString().orEmpty()) }
    var drafts by remember { mutableStateOf(medication?.schedules?.map(ScheduleDraft::from)?.ifEmpty { null } ?: listOf(ScheduleDraft(startDate = today))) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
    val editing = medication != null
    val nameError = if (showErrors && name.isBlank()) vitaText(L10nKey.MEDICATION_NAME_REQUIRED) else null

    fun save() {
        if (!medicationFormValid(name, drafts)) {
            showErrors = true
            if (name.isBlank()) nameFocus.requestFocus()
            return
        }
        ctx.runThenAnnounce(UserText.Key(L10nKey.CARE_MEDICATION_SAVED, listOf(name.trim()))) {
            if (medication == null) ctx.controller.addMedication(name, strength, instructions, drafts, supplyUnits, lowThreshold)
            else ctx.controller.updateMedication(medication.medicationId, name, strength, instructions, drafts, supplyUnits, lowThreshold, unitsShown)
            if (ctx.controller.state.value.error == null) ctx.back()
        }
    }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = VezunaTokens.GutterNarrow).padding(top = VezunaTokens.SpaceMd),
        verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
    ) {
        Text(vitaText(L10nKey.CARE_FORM_SUBTITLE), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LabeledField(vitaText(L10nKey.MEDICATION_NAME), name, { name = it }, Modifier.focusRequester(nameFocus), error = nameError)
        LabeledField(vitaText(L10nKey.STRENGTH_AS_PRESCRIBED), strength, { strength = it }, hint = vitaText(L10nKey.CARE_STRENGTH_HINT))
        LabeledField(vitaText(L10nKey.PRESCRIBER_INSTRUCTIONS), instructions, { instructions = it }, singleLine = false)
        ScheduleListEditor(drafts, today) { drafts = it }
        SupplyFields(supplyUnits, { supplyUnits = it }, lowThreshold, { lowThreshold = it })
        Text(vitaText(L10nKey.CARE_TIME_ZONE_NOTE), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (showErrors && !medicationFormValid(name, drafts)) {
            Text(
                vitaText(L10nKey.CARE_FORM_FIX),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        PrimaryButton(
            vitaText(if (editing) L10nKey.CARE_SAVE_CHANGES else L10nKey.SAVE_MEDICATION),
            ::save,
            Modifier.fillMaxWidth(),
            loading = ctx.state.loading,
        )
        TertiaryButton(vitaText(L10nKey.CANCEL), ctx::back, Modifier.fillMaxWidth())
        Spacer(Modifier.height(VezunaTokens.SpaceLg))
    }
}
