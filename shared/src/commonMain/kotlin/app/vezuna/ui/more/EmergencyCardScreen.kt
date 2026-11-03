package app.vezuna.ui.more

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vezuna.domain.AllergyRecord
import app.vezuna.domain.AllergySeverity
import app.vezuna.domain.ConditionRecord
import app.vezuna.domain.ConditionStatus
import app.vezuna.domain.EmergencyMedication
import app.vezuna.domain.EmergencySummary
import app.vezuna.domain.ProfileAge
import app.vezuna.domain.emergencySummary
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.vitaPlural
import app.vezuna.localization.vitaText
import app.vezuna.platform.currentInstant
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.EmptyState
import app.vezuna.ui.components.FocusedTaskScaffold
import app.vezuna.ui.components.ProgressState
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.components.SectionHeading
import app.vezuna.ui.entry.FormColumn
import app.vezuna.ui.export.atNoonUtc
import app.vezuna.ui.nav.RouteContext
import app.vezuna.ui.records.severityKey
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_error
import vezuna.shared.generated.resources.ill_record_empty

/** In-app emergency card (#102): read-only, on-device only. Nothing here reaches the lock screen, a widget or a notification. */
@Composable
fun EmergencyCardScreen(ctx: RouteContext) {
    FocusedTaskScaffold(title = vitaText(L10nKey.EM_TITLE), onBack = ctx::back) {
        val data = ctx.state.data
        if (data == null) {
            ProgressState(vitaText(L10nKey.EM_TITLE))
            return@FocusedTaskScaffold
        }
        // The date is read once per loaded profile, not on every recomposition.
        val summary = remember(data) { data.emergencySummary(ProfileAge.today(currentInstant(), TimeZone.currentSystemDefault())) }
        FormColumn {
            Identity(summary)
            if (summary.nothingRecorded) {
                EmptyState(Res.drawable.ill_record_empty, vitaText(L10nKey.EM_EMPTY_TITLE), vitaText(L10nKey.EM_EMPTY_BODY, summary.profileName))
            } else {
                Section(L10nKey.EM_ALLERGIES, L10nKey.EM_NONE_ALLERGIES, summary.allergies.map { { AllergyCard(it) } })
                Section(L10nKey.EM_CONDITIONS, L10nKey.EM_NONE_CONDITIONS, summary.conditions.map { { LineCard(it.name, conditionLines(it)) } })
                Section(L10nKey.EM_MEDICATIONS, L10nKey.EM_NONE_MEDICATIONS, summary.medications.map { { LineCard(it.medication.displayName, medicationLines(it)) } })
                if (summary.allergies.isEmpty() && summary.conditions.isEmpty() && summary.medications.isEmpty()) {
                    Text(vitaText(L10nKey.EM_PARTIAL_NOTE), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Text(vitaText(L10nKey.EM_INTRO, summary.profileName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PhoneInfo()
        }
    }
}

@Composable
private fun Identity(summary: EmergencySummary) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceXs)) {
        Text(summary.profileName, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        summary.birthDate?.let {
            val born = formatUiDate(it.atNoonUtc())
            val age = when {
                summary.ageMonths != null -> vitaPlural(L10nKey.EM_AGE_MONTHS, summary.ageMonths)
                summary.ageYears != null -> vitaPlural(L10nKey.EM_AGE_YEARS, summary.ageYears)
                else -> null
            }
            Text(if (age != null) vitaText(L10nKey.EM_BORN_AGE, born, age) else vitaText(L10nKey.EM_BORN, born), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun Section(title: L10nKey, none: L10nKey, cards: List<@Composable () -> Unit>) {
    Column(verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
        SectionHeading(vitaText(title))
        if (cards.isEmpty()) {
            Text(vitaText(none), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            cards.forEach { it() }
        }
    }
}

@Composable
private fun AllergyCard(record: AllergyRecord) {
    val severity = record.severity?.let { vitaText(severityKey(it)) } ?: vitaText(L10nKey.EM_SEVERITY_UNKNOWN)
    val detail = listOfNotNull(record.reaction, record.notes)
    // Distinct by border weight, an icon and a text label for severity, never by colour alone.
    val severe = record.severity == AllergySeverity.SEVERE
    Card(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = (listOf(record.substance, severity) + detail).joinToString(", ") },
        border = BorderStroke(if (severe) 4.dp else 2.dp, MaterialTheme.colorScheme.error),
    ) {
        Column(Modifier.padding(VezunaTokens.SpaceMd), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceXs)) {
            Text(record.substance, style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
                if (severe) Icon(painterResource(Res.drawable.ic_error), contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(severity, style = MaterialTheme.typography.titleMedium)
            }
            detail.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
private fun conditionLines(condition: ConditionRecord): List<String> =
    listOf(condition.status?.let { vitaText(if (it == ConditionStatus.ONGOING) L10nKey.HR_STATUS_ONGOING else L10nKey.HR_STATUS_RESOLVED) } ?: vitaText(L10nKey.EM_CONDITION_UNKNOWN)) +
        listOfNotNull(condition.notes)

@Composable
private fun medicationLines(entry: EmergencyMedication): List<String> {
    val medication = entry.medication
    return listOfNotNull(
        medication.strength ?: vitaText(L10nKey.EM_DOSE_NONE),
        medication.instructions,
        entry.startsOn?.let { vitaText(L10nKey.EM_MED_STARTS, formatUiDate(it.atNoonUtc())) },
        if (entry.paused) vitaText(L10nKey.EM_MED_PAUSED) else null,
    )
}

@Composable
private fun LineCard(name: String, lines: List<String>) {
    Card(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = (listOf(name) + lines).joinToString(", ") }) {
        Column(Modifier.padding(VezunaTokens.SpaceMd)) {
            Text(name, style = MaterialTheme.typography.titleLarge)
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
private fun PhoneInfo() {
    val ios = MorePlatform.phoneIsIos
    var failed by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
        SectionHeading(vitaText(L10nKey.EM_PHONE_TITLE))
        Text(vitaText(if (ios) L10nKey.EM_PHONE_IOS else L10nKey.EM_PHONE_ANDROID), style = MaterialTheme.typography.bodyLarge)
        SecondaryButton(vitaText(if (ios) L10nKey.EM_PHONE_OPEN_IOS else L10nKey.EM_PHONE_OPEN_ANDROID), { failed = false; MorePlatform.openEmergencyInfo { ok -> failed = !ok } }, Modifier.fillMaxWidth())
        if (failed) Text(vitaText(L10nKey.EM_PHONE_OPEN_FAILED), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
    }
}
