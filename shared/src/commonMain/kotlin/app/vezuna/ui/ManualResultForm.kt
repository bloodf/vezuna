package app.vezuna.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

/** Result typed from a paper report: no source file, a required collection date, saved as a confirmed manual result. */
@Composable
internal fun ManualResultForm(controller: AppController, scope: CoroutineScope) {
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var lower by remember { mutableStateOf("") }
    var upper by remember { mutableStateOf("") }
    var rangeText by remember { mutableStateOf("") }
    var asText by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf<Instant?>(null) }
    SectionHeading(vitaText(L10nKey.MANUAL_SINGLE_RESULT), vitaText(L10nKey.MANUAL_ENTRY_HINT))
    OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.TEST_CODE_EXAMPLE)) }, singleLine = true)
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.TEST_NAME)) }, singleLine = true)
    FilterChip(selected = asText, onClick = { asText = !asText }, label = { Text(vitaText(L10nKey.TEXT_RESULT_TOGGLE)) })
    if (asText) {
        OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.CONFIRMED_QUALITATIVE_VALUE)) }, singleLine = true)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value, { value = it }, Modifier.weight(1f), label = { Text(vitaText(L10nKey.EXTRACTED_VALUE)) }, singleLine = true)
            OutlinedTextField(unit, { unit = it }, Modifier.weight(1f), label = { Text(vitaText(L10nKey.EXTRACTED_UNIT)) }, singleLine = true)
        }
        OutlinedTextField(lower, { lower = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.REFERENCE_LOWER_OPTIONAL)) }, singleLine = true)
        OutlinedTextField(upper, { upper = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.REFERENCE_UPPER_OPTIONAL)) }, singleLine = true)
    }
    OutlinedTextField(rangeText, { rangeText = it }, Modifier.fillMaxWidth(), label = { Text(vitaText(L10nKey.REFERENCE_TEXT_OPTIONAL)) }, singleLine = true)
    CollectionDateField(date, { date = it }, onApplyToAll = {}, showApplyToAll = false)
    VitaButton(vitaText(L10nKey.SAVE_MANUAL_RESULT), Icons.Rounded.Add, {
        scope.launch { controller.addManualResult(code, name, value, unit, lower, upper, rangeText, date, asText) }
    }, enabled = code.isNotBlank() && name.isNotBlank() && value.isNotBlank() && (asText || unit.isNotBlank()) && date != null)
}
