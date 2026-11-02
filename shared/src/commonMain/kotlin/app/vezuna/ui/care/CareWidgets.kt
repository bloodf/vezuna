package app.vezuna.ui.care

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.localization.formatUiDateTime
import app.vezuna.localization.vitaText
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.StatusKind
import app.vezuna.ui.components.StatusTag
import app.vezuna.ui.formatTime
import app.vezuna.ui.nav.RouteContext
import app.vezuna.ui.vita
import kotlinx.coroutines.launch
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_check_circle
import vezuna.shared.generated.resources.ic_clock
import vezuna.shared.generated.resources.ic_minus
import vezuna.shared.generated.resources.ic_skip

internal fun Instant.localTime(zone: TimeZone = TimeZone.currentSystemDefault()): String =
    toLocalDateTime(zone).let { formatTime(it.hour, it.minute) }

/** Time alone when [at] falls on the same local day as [reference], otherwise date and time. */
internal fun timeOrDateTime(at: Instant, reference: Instant, zone: TimeZone = TimeZone.currentSystemDefault()): String =
    if (at.toLocalDateTime(zone).date == reference.toLocalDateTime(zone).date) at.localTime(zone) else formatUiDateTime(at)

@Composable
internal fun MedicationStatusTag(status: MedicationStatus, modifier: Modifier = Modifier) = when (status) {
    MedicationStatus.ACTIVE -> StatusTag(vitaText(L10nKey.STATUS_ACTIVE), StatusKind.CONFIRMED, modifier)
    MedicationStatus.PAUSED -> StatusTag(vitaText(L10nKey.STATUS_PAUSED), StatusKind.INFO, modifier)
    MedicationStatus.STOPPED -> StatusTag(vitaText(L10nKey.STATUS_STOPPED), StatusKind.INFO, modifier)
}

/** The recorded state of a dose in words, e.g. "Taken at 08:04" or "Recorded as missed". */
@Composable
internal fun doseStateText(dose: CareDose): String {
    val at = dose.recordedAt?.let { timeOrDateTime(it, dose.dose.scheduledFor) }
    return when (dose.state) {
        DoseState.PENDING -> vitaText(L10nKey.CARE_DOSE_PENDING)
        DoseState.SNOOZED -> vitaText(L10nKey.CARE_DOSE_SNOOZED_UNTIL, dose.dose.scheduledFor.localTime())
        DoseState.TAKEN -> at?.let { vitaText(L10nKey.CARE_DOSE_TAKEN_AT, it) } ?: vitaText(L10nKey.CARE_DOSE_TAKEN)
        DoseState.SKIPPED -> at?.let { vitaText(L10nKey.CARE_DOSE_SKIPPED_AT, it) } ?: vitaText(L10nKey.CARE_DOSE_SKIPPED)
        DoseState.MISSED -> vitaText(L10nKey.CARE_DOSE_MISSED)
    }
}

/** Icon plus words; each state has its own glyph so color is never the only signal. */
@Composable
internal fun DoseStateLabel(dose: CareDose, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val (icon, tint) = doseStateIcon(dose.state, color)
    val text = doseStateText(dose)
    Row(modifier.clearAndSetSemantics { contentDescription = text }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun doseStateIcon(state: DoseState, neutral: Color): Pair<DrawableResource, Color> = when (state) {
    DoseState.TAKEN -> Res.drawable.ic_check_circle to MaterialTheme.vita.success
    DoseState.SKIPPED -> Res.drawable.ic_skip to neutral
    DoseState.MISSED -> Res.drawable.ic_minus to neutral
    DoseState.PENDING, DoseState.SNOOZED -> Res.drawable.ic_clock to neutral
}

/**
 * Runs one controller operation and, only when it succeeded, announces [notice] through the shell's live
 * feedback region. A failed operation leaves the controller's own error in place.
 */
internal fun RouteContext.runThenAnnounce(notice: UserText, operation: suspend () -> Unit) {
    scope.launch {
        operation()
        if (controller.state.value.error == null) controller.announce(notice)
    }
}
