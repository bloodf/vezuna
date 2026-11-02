package app.vezuna.ui.care

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.localization.vitaText
import app.vezuna.platform.currentInstant
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.components.TertiaryButton
import app.vezuna.ui.components.VezunaSheet
import app.vezuna.ui.nav.RouteContext
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_check_circle
import vezuna.shared.generated.resources.ic_clock
import vezuna.shared.generated.resources.ic_skip

/**
 * Taken / skipped / snooze for one dose (images 064-066). A recorded dose can be changed: the latest user action
 * wins (DoseEvents.merge), which is the undo. Snooze is 10 minutes, the same as the notification action, and only
 * applies to a dose that is not recorded yet. No dose advice.
 */
@Composable
internal fun DoseActionSheet(ctx: RouteContext, dose: CareDose, onDismiss: () -> Unit) {
    val d = dose.dose
    val name = d.medicationName
    fun act(notice: UserText, operation: suspend () -> Unit) {
        onDismiss()
        ctx.runThenAnnounce(notice, operation)
    }
    VezunaSheet(title = name, onDismiss = onDismiss) {
        listOfNotNull(d.instructions, d.strength).takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString(" · "), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
            Icon(painterResource(Res.drawable.ic_clock), contentDescription = null, modifier = Modifier.size(22.dp))
            Text(vitaText(L10nKey.CARE_SCHEDULED_FOR, timeOrDateTime(d.scheduledFor, currentInstant())), style = MaterialTheme.typography.bodyLarge)
        }
        if (dose.recorded) {
            DoseStateLabel(dose, color = MaterialTheme.colorScheme.onSurface)
            Text(vitaText(L10nKey.CARE_RECORD_CHANGE_NOTE), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd)) {
            if (dose.state != DoseState.TAKEN) {
                PrimaryButton(
                    vitaText(if (dose.recorded) L10nKey.CARE_CHANGE_TO_TAKEN else L10nKey.CARE_MARK_TAKEN),
                    { act(UserText.Key(L10nKey.CARE_DOSE_RECORDED_TAKEN, listOf(name))) { ctx.controller.takeDose(d) } },
                    Modifier.fillMaxWidth(),
                    icon = Res.drawable.ic_check_circle,
                )
            }
            if (dose.state != DoseState.SKIPPED) {
                SecondaryButton(
                    vitaText(if (dose.recorded) L10nKey.CARE_MARK_SKIPPED else L10nKey.CARE_SKIP_DOSE),
                    { act(UserText.Key(L10nKey.CARE_DOSE_RECORDED_SKIPPED, listOf(name))) { ctx.controller.skipDose(d) } },
                    Modifier.fillMaxWidth(),
                    icon = Res.drawable.ic_skip,
                )
            }
            if (!dose.recorded) {
                val until = currentInstant().plusMinutes(SNOOZE_MINUTES).localTime()
                SecondaryButton(
                    vitaText(L10nKey.CARE_SNOOZE),
                    { act(UserText.Key(L10nKey.CARE_DOSE_SNOOZED, listOf(name, until))) { ctx.controller.snoozeDose(d) } },
                    Modifier.fillMaxWidth(),
                    icon = Res.drawable.ic_clock,
                )
                Text(vitaText(L10nKey.CARE_SNOOZE_NOTE), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TertiaryButton(vitaText(L10nKey.CANCEL), onDismiss, Modifier.fillMaxWidth())
        }
    }
}

/** Matches AppController.snoozeDose and the notification snooze action (#51). */
private const val SNOOZE_MINUTES = 10

private fun kotlin.time.Instant.plusMinutes(minutes: Int) = kotlin.time.Instant.fromEpochSeconds(epochSeconds + minutes * 60L)
