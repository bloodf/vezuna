package app.vezuna.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.vitaText
import app.vezuna.platform.currentInstant

/** Reminder coverage (iOS) and exact-alarm status (Android), shown above the medication list. */
@Composable
internal fun ReminderStatus(reminders: ReminderPlatformState) {
    val coverageUntil by reminders.coverageUntil.collectAsState()
    val denied by reminders.exactAlarmsDenied.collectAsState()
    coverageUntil?.let { until ->
        val low = ReminderPlatformState.isCoverageLow(until, currentInstant())
        StatusCard(
            icon = if (low) Icons.Rounded.Warning else Icons.Rounded.Schedule,
            title = vitaText(L10nKey.REMINDERS_SCHEDULED_UNTIL, formatUiDate(until)),
            body = if (low) vitaText(L10nKey.REMINDERS_COVERAGE_LOW) else null,
            warning = low,
        )
    }
    val openSettings = reminders.openExactAlarmSettings
    if (denied && openSettings != null) {
        StatusCard(Icons.Rounded.Warning, vitaText(L10nKey.EXACT_ALARMS_OFF_TITLE), vitaText(L10nKey.EXACT_ALARMS_OFF_EXPLANATION), warning = true) {
            TextButton(openSettings, Modifier.defaultMinSize(minHeight = 48.dp)) { Text(vitaText(L10nKey.OPEN_ALARM_SETTINGS)) }
        }
    }
}

@Composable
private fun StatusCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String?, warning: Boolean, action: @Composable () -> Unit = {}) {
    val colors = if (warning) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
    else CardDefaults.cardColors()
    Card(Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(22.dp))
                Text(title, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleSmall)
            }
            body?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            action()
        }
    }
}

@Composable
internal fun ReminderPrivacySetting(reminders: ReminderPlatformState) {
    val hidden by reminders.hideMedicationNames.collectAsState()
    val title = vitaText(L10nKey.HIDE_MEDICATION_NAMES)
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp)
                .toggleable(value = hidden, role = Role.Switch, onValueChange = reminders::setHideMedicationNames)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Lock, null, Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(vitaText(L10nKey.HIDE_MEDICATION_NAMES_EXPLANATION), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = hidden, onCheckedChange = null, modifier = Modifier.semantics { contentDescription = title })
        }
    }
}
