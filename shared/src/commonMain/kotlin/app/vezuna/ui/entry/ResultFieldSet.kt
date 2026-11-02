package app.vezuna.ui.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import app.vezuna.ui.CollectionDateField
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.FieldPair
import app.vezuna.ui.components.LabeledField
import app.vezuna.ui.components.SegmentedControl
import kotlin.time.Instant

@Composable
fun FieldError.text(): String = vitaText(
    when (this) {
        FieldError.REQUIRED -> L10nKey.ENTRY_ERROR_REQUIRED
        FieldError.NOT_A_NUMBER -> L10nKey.ENTRY_ERROR_NUMBER
        FieldError.AMBIGUOUS -> L10nKey.NUMBER_AMBIGUOUS_INPUT
        FieldError.UNIT_REQUIRED -> L10nKey.ENTRY_ERROR_UNIT
        FieldError.RANGE_REVERSED -> L10nKey.ENTRY_ERROR_RANGE
        FieldError.DATE_REQUIRED -> L10nKey.ENTRY_ERROR_DATE
    },
)

/**
 * The one result editor (manual entry, OCR review, correction). Every field has a persistent label and its own
 * error; numbers get the decimal keyboard and accept a decimal comma. [focusFirstError] changes when a save is
 * refused, which moves focus to the first field with an error.
 */
@Composable
fun ResultFieldSet(
    draft: ResultDraft,
    onChange: (ResultDraft) -> Unit,
    errors: ResultErrors,
    modifier: Modifier = Modifier,
    editIdentity: Boolean = true,
    editKind: Boolean = true,
    valueEnabled: Boolean = true,
    applyDateToAllLabel: String? = null,
    onDateChosen: ((Instant, Boolean) -> Unit)? = null,
    focusFirstError: Int = 0,
) {
    val focus = remember { List(7) { FocusRequester() } }
    LaunchedEffect(focusFirstError) {
        if (focusFirstError == 0) return@LaunchedEffect
        val order = listOf(errors.name, errors.code, errors.value, errors.unit, errors.lower, errors.upper, errors.date)
        val index = order.indexOfFirst { it != null }
        if (index >= 0) runCatching { focus[index].requestFocus() }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd)) {
        if (editIdentity) {
            LabeledField(vitaText(L10nKey.TEST_NAME), draft.name, { onChange(draft.copy(name = it)) }, Modifier.focusRequester(focus[0]), error = errors.name?.text())
            LabeledField(vitaText(L10nKey.TEST_CODE_EXAMPLE), draft.code, { onChange(draft.copy(code = it)) }, Modifier.focusRequester(focus[1]), error = errors.code?.text())
        }
        if (editKind) {
            SegmentedControl(
                options = listOf(vitaText(L10nKey.ENTRY_KIND_NUMERIC), vitaText(L10nKey.ENTRY_KIND_TEXT)),
                selectedIndex = if (draft.asText) 1 else 0,
                onSelect = { onChange(draft.copy(asText = it == 1)) },
            )
        }
        if (draft.asText) {
            LabeledField(
                vitaText(L10nKey.ENTRY_TEXT_VALUE), draft.value, { onChange(draft.copy(value = it)) }, Modifier.focusRequester(focus[2]),
                hint = vitaText(L10nKey.ENTRY_TEXT_VALUE_HINT), error = errors.value?.text(), enabled = valueEnabled,
            )
        } else {
            FieldPair(
                first = { m -> LabeledField(vitaText(L10nKey.ENTRY_VALUE), draft.value, { onChange(draft.copy(value = it)) }, m.focusRequester(focus[2]), error = errors.value?.text(), keyboardType = KeyboardType.Decimal, enabled = valueEnabled) },
                second = { m -> LabeledField(vitaText(L10nKey.ENTRY_UNIT), draft.unit, { onChange(draft.copy(unit = it)) }, m.focusRequester(focus[3]), error = errors.unit?.text()) },
            )
            FieldPair(
                first = { m -> LabeledField(vitaText(L10nKey.ENTRY_REFERENCE_LOW), draft.lower, { onChange(draft.copy(lower = it)) }, m.focusRequester(focus[4]), error = errors.lower?.text(), keyboardType = KeyboardType.Decimal) },
                second = { m -> LabeledField(vitaText(L10nKey.ENTRY_REFERENCE_HIGH), draft.upper, { onChange(draft.copy(upper = it)) }, m.focusRequester(focus[5]), error = errors.upper?.text(), keyboardType = KeyboardType.Decimal) },
            )
        }
        LabeledField(vitaText(L10nKey.REFERENCE_TEXT_OPTIONAL), draft.rangeText, { onChange(draft.copy(rangeText = it)) }, hint = vitaText(L10nKey.ENTRY_REFERENCE_TEXT_HINT))
        CollectionDateField(
            date = draft.date,
            onDate = { date, applyToAll ->
                onChange(draft.copy(date = date))
                onDateChosen?.invoke(date, applyToAll)
            },
            error = errors.date?.text(),
            applyToAllLabel = applyDateToAllLabel,
            focusRequester = focus[6],
        )
    }
}
