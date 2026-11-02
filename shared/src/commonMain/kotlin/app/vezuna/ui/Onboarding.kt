package app.vezuna.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vezuna.domain.ProfileAge
import app.vezuna.localization.L10nKey
import app.vezuna.localization.resolve
import app.vezuna.localization.vitaText
import app.vezuna.platform.currentInstant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/** Short, skippable first-run screen: name, privacy boundary, optional birth date. It never gates access to records. */
@Composable
fun OnboardingScreen(state: AppUiState, controller: AppController, scope: CoroutineScope) {
    var name by remember { mutableStateOf(state.data?.profile?.displayName.orEmpty()) }
    var birthDate by remember { mutableStateOf<LocalDate?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(vitaText(L10nKey.ONBOARDING_TITLE), style = MaterialTheme.typography.headlineMedium)
        Text(vitaText(L10nKey.ONBOARDING_BODY), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(vitaText(L10nKey.ONBOARDING_NAME)) },
            singleLine = true,
        )
        BirthDateField(birthDate, { birthDate = it })
        Text(vitaText(L10nKey.ONBOARDING_PRIVACY), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { scope.launch { controller.completeOnboarding(name, birthDate) } },
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
            enabled = !state.loading,
        ) { Text(vitaText(L10nKey.ONBOARDING_DONE)) }
        TextButton(onClick = controller::skipOnboarding, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)) {
            Text(vitaText(L10nKey.ONBOARDING_SKIP))
        }
    }
}

private const val MAX_UTC_OFFSET_MILLIS = 14L * 60 * 60 * 1000

@OptIn(ExperimentalMaterial3Api::class)
private object NoFutureBirthDates : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= currentInstant().toEpochMilliseconds() + MAX_UTC_OFFSET_MILLIS
}

/** The picker yields UTC midnight, which read as UTC is exactly the chosen calendar day. */
internal fun pickedDateToLocalDate(utcMidnightMillis: Long): LocalDate =
    Instant.fromEpochMilliseconds(utcMidnightMillis).toLocalDateTime(TimeZone.UTC).date

internal fun LocalDate.toPickerMillis(): Long = atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BirthDateField(date: LocalDate?, onDate: (LocalDate?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val age = date?.let { ProfileAge.years(it, ProfileAge.today(currentInstant(), TimeZone.currentSystemDefault())) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { open = true }, Modifier.defaultMinSize(minHeight = 48.dp)) {
            Text("${vitaText(L10nKey.BIRTH_DATE)}: ${date?.toString() ?: vitaText(L10nKey.BIRTH_DATE_NOT_SET)}")
        }
        if (age != null) Text(vitaText(L10nKey.PROFILE_AGE, age), style = MaterialTheme.typography.bodySmall)
        if (date != null) TextButton(onClick = { onDate(null) }, Modifier.defaultMinSize(minHeight = 48.dp)) { Text(vitaText(L10nKey.BIRTH_DATE_CLEAR)) }
    }
    if (open) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = date?.toPickerMillis(), selectableDates = NoFutureBirthDates)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    onClick = { picker.selectedDateMillis?.let { onDate(pickedDateToLocalDate(it)) }; open = false },
                    enabled = picker.selectedDateMillis != null,
                ) { Text(vitaText(L10nKey.CHOOSE_DATE)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(vitaText(L10nKey.CANCEL)) } },
        ) { DatePicker(picker) }
    }
}
