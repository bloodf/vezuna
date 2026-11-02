package app.vezuna.ui.care

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import app.vezuna.domain.Medication
import app.vezuna.domain.MedicationSupplies
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.vitaText
import app.vezuna.platform.currentInstant
import app.vezuna.ui.Category
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.GroupedRows
import app.vezuna.ui.components.LabeledField
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.components.SectionHeading
import app.vezuna.ui.components.TertiaryButton
import app.vezuna.ui.nav.RouteContext
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** Supply count, estimated run-out date and the refill action. Shown only for a medication that tracks supply. */
@Composable
internal fun SupplySection(ctx: RouteContext, medication: Medication) {
    val data = ctx.state.data ?: return
    val supply = medication.supply ?: return
    val remaining = MedicationSupplies.remaining(medication, data.doseEvents) ?: return
    val today = currentInstant().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val runOut = MedicationSupplies.runOutDate(medication, remaining, today)
    var refilling by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf("") }
    val busy = ctx.state.loading
    val low = MedicationSupplies.isLow(medication, remaining)
    Column(verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
        SectionHeading(vitaText(L10nKey.CARE_SUPPLY_TITLE))
        GroupedRows {
            val left = vitaText(L10nKey.CARE_SUPPLY_REMAINING, remaining)
            ListRow(left, supporting = if (low) vitaText(L10nKey.CARE_SUPPLY_LOW) else null, category = Category.CARE)
            ListRow(
                runOut?.let { vitaText(L10nKey.CARE_SUPPLY_RUNS_OUT, formatUiDate(it.atTime(12, 0).toInstant(TimeZone.UTC))) }
                    ?: vitaText(L10nKey.CARE_SUPPLY_NO_ESTIMATE),
                category = Category.CARE,
            )
        }
        Text(vitaText(L10nKey.CARE_SUPPLY_ESTIMATE_NOTE), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (refilling) {
            LabeledField(vitaText(L10nKey.CARE_REFILL_UNITS), added, { added = it.filter(Char::isDigit) }, keyboardType = KeyboardType.Number)
            PrimaryButton(
                vitaText(L10nKey.CARE_REFILL_SAVE),
                {
                    ctx.runThenAnnounce(UserText.Key(L10nKey.CARE_REFILL_RECORDED, listOf(medication.displayName))) {
                        ctx.controller.recordRefill(medication.medicationId, added)
                        if (ctx.controller.state.value.error == null) { refilling = false; added = "" }
                    }
                },
                Modifier.fillMaxWidth(),
                enabled = !busy && added.isNotBlank(),
            )
            TertiaryButton(vitaText(L10nKey.CANCEL), { refilling = false; added = "" }, Modifier.fillMaxWidth())
        } else {
            SecondaryButton(vitaText(L10nKey.CARE_RECORD_REFILL), { refilling = true }, Modifier.fillMaxWidth(), enabled = !busy)
        }
    }
}

/** Optional supply fields of the medication form; blank units means supply is not tracked. */
@Composable
internal fun SupplyFields(units: String, onUnits: (String) -> Unit, threshold: String, onThreshold: (String) -> Unit) {
    LabeledField(vitaText(L10nKey.CARE_SUPPLY_UNITS_LABEL), units, { onUnits(it.filter(Char::isDigit)) }, hint = vitaText(L10nKey.CARE_SUPPLY_UNITS_HINT), keyboardType = KeyboardType.Number)
    if (units.isNotBlank()) {
        LabeledField(vitaText(L10nKey.CARE_SUPPLY_THRESHOLD_LABEL), threshold, { onThreshold(it.filter(Char::isDigit)) }, keyboardType = KeyboardType.Number)
    }
}
