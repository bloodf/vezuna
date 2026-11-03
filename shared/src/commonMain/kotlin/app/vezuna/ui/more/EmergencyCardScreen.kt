package app.vezuna.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.vezuna.domain.AllergyRecord
import app.vezuna.domain.EmergencySummary
import app.vezuna.domain.HealthRecordKind
import app.vezuna.domain.Medication
import app.vezuna.domain.ProfileAge
import app.vezuna.domain.emergencySummary
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.vitaText
import app.vezuna.platform.currentInstant
import app.vezuna.ui.AppScreen
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.EmptyState
import app.vezuna.ui.components.FocusedTaskScaffold
import app.vezuna.ui.components.SectionHeading
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.entry.FormColumn
import app.vezuna.ui.export.atNoonUtc
import app.vezuna.ui.nav.RouteContext
import app.vezuna.ui.records.severityKey
import kotlinx.datetime.TimeZone
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ill_record_empty

/** In-app emergency card (#102): read-only, on-device only. Nothing here reaches the lock screen, a widget or a notification. */
@Composable
fun EmergencyCardScreen(ctx: RouteContext) {
    FocusedTaskScaffold(title = vitaText(L10nKey.EM_TITLE), onBack = ctx::back) {
        val data = ctx.state.data ?: return@FocusedTaskScaffold
        val summary = data.emergencySummary(ProfileAge.today(currentInstant(), TimeZone.currentSystemDefault()))
        FormColumn {
            Text(vitaText(L10nKey.EM_INTRO, summary.profileName), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Identity(summary)
            if (summary.isEmpty) {
                EmptyState(
                    Res.drawable.ill_record_empty, vitaText(L10nKey.EM_EMPTY_TITLE), vitaText(L10nKey.EM_EMPTY_BODY, summary.profileName),
                    actionLabel = vitaText(L10nKey.HR_ADD_ALLERGY), onAction = { ctx.navigate(AppScreen.HealthRecords(HealthRecordKind.ALLERGY)) },
                )
            } else {
                Section(L10nKey.EM_ALLERGIES, summary.allergies.map { allergyLine(it) }, L10nKey.EM_NONE_ALLERGIES)
                Section(L10nKey.EM_CONDITIONS, summary.conditions.map { it.name to listOfNotNull(it.notes).joinToString() }, L10nKey.EM_NONE_CONDITIONS)
                Section(L10nKey.EM_MEDICATIONS, summary.medications.map { medicationLine(it) }, L10nKey.EM_NONE_MEDICATIONS)
            }
            PhoneInfo()
        }
    }
}

@Composable
private fun Identity(summary: EmergencySummary) {
    val description = vitaText(L10nKey.EM_CARD_A11Y, summary.profileName)
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceXs)) {
        Text(summary.profileName, style = MaterialTheme.typography.headlineMedium)
        summary.birthDate?.let {
            val born = formatUiDate(it.atNoonUtc())
            Text(
                summary.ageYears?.let { age -> vitaText(L10nKey.EM_BORN_AGE, born, age) } ?: vitaText(L10nKey.EM_BORN, born),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun allergyLine(record: AllergyRecord): Pair<String, String> =
    record.substance to listOfNotNull(record.severity?.let { vitaText(severityKey(it)) }, record.reaction, record.notes).joinToString(" · ")

private fun medicationLine(medication: Medication): Pair<String, String> =
    medication.displayName to listOfNotNull(medication.strength, medication.instructions).joinToString(" · ")

@Composable
private fun Section(title: L10nKey, lines: List<Pair<String, String>>, none: L10nKey) {
    Column(verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
        SectionHeading(vitaText(title))
        if (lines.isEmpty()) {
            Text(vitaText(none), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            lines.forEach { (name, detail) ->
                val spoken = if (detail.isEmpty()) name else "$name, $detail"
                Card(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = spoken }) {
                    Column(Modifier.padding(VezunaTokens.SpaceMd)) {
                        Text(name, style = MaterialTheme.typography.titleLarge)
                        if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneInfo() {
    val ios = MorePlatform.phoneIsIos
    Column(verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
        SectionHeading(vitaText(L10nKey.EM_PHONE_TITLE))
        Text(vitaText(if (ios) L10nKey.EM_PHONE_IOS else L10nKey.EM_PHONE_ANDROID), style = MaterialTheme.typography.bodyLarge)
        MorePlatform.openEmergencyInfo?.let { open ->
            SecondaryButton(vitaText(if (ios) L10nKey.EM_PHONE_OPEN_IOS else L10nKey.EM_PHONE_OPEN_ANDROID), open, Modifier.fillMaxWidth())
        }
    }
}
