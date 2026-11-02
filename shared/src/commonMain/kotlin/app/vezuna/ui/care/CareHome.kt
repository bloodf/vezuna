package app.vezuna.ui.care

import app.vezuna.ui.components.decorativeArtShown
import app.vezuna.ui.components.clearOfArt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vezuna.domain.ProfileData
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.formatUiDateTime
import app.vezuna.localization.vitaText
import app.vezuna.platform.currentInstant
import kotlinx.coroutines.delay
import app.vezuna.ui.AppScreen
import app.vezuna.ui.Category
import app.vezuna.ui.NotificationAccess
import app.vezuna.ui.ReminderPlatformState
import app.vezuna.ui.ReminderPrivacySetting
import app.vezuna.ui.ReminderStatus
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.tabular
import app.vezuna.ui.components.CategoryPanel
import app.vezuna.ui.components.EmptyState
import app.vezuna.ui.components.GroupedRows
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.components.SectionHeading
import app.vezuna.ui.components.TertiaryButton
import app.vezuna.ui.nav.NativeAction
import app.vezuna.ui.nav.RouteContext
import kotlinx.datetime.TimeZone
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_capsule
import vezuna.shared.generated.resources.ic_check_circle
import vezuna.shared.generated.resources.ic_history
import vezuna.shared.generated.resources.ic_plus
import vezuna.shared.generated.resources.ill_capsule
import vezuna.shared.generated.resources.ill_medication_calendar
import vezuna.shared.generated.resources.ill_notification_bell
import vezuna.shared.generated.resources.ill_notification_off

/** Care root (images 055, 056, 066, 068, 070, 140): today's doses, reminder access, the medication list. */
@Composable
internal fun CareHome(ctx: RouteContext) {
    val data = ctx.state.data ?: return
    var actionFor by remember { mutableStateOf<CareDose?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = VezunaTokens.GutterNarrow),
        verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceLg),
    ) {
        Spacer(Modifier.height(VezunaTokens.SpaceSm))
        if (data.medications.isEmpty()) {
            EmptyState(
                Res.drawable.ill_medication_calendar,
                vitaText(L10nKey.CARE_EMPTY_TITLE),
                vitaText(L10nKey.CARE_EMPTY_BODY),
                actionLabel = vitaText(L10nKey.ADD_MEDICATION),
                onAction = { ctx.navigate(AppScreen.MedicationForm()) },
            )
        } else {
            TodayPanel(ctx, data) { actionFor = it }
            ctx.reminders?.let { ReminderAccessPanel(ctx, it) }
            ctx.reminders?.let { ReminderStatus(it) }
            MedicationList(ctx, data)
            GroupedRows {
                ListRow(vitaText(L10nKey.DOSE_HISTORY), onClick = { ctx.navigate(AppScreen.DoseHistory()) }, leadingIcon = Res.drawable.ic_history, category = Category.CARE)
            }
            ctx.reminders?.let {
                Column(verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
                    SectionHeading(vitaText(L10nKey.SECTION_REMINDERS))
                    ReminderPrivacySetting(it)
                }
            }
        }
        Spacer(Modifier.height(VezunaTokens.SpaceLg))
    }
    actionFor?.let { DoseActionSheet(ctx, it) { actionFor = null } }
}

private const val CLOCK_TICK_MILLIS = 60_000L

@Composable
private fun TodayPanel(ctx: RouteContext, data: ProfileData, onDose: (CareDose) -> Unit) {
    // Re-read the clock every minute so "today" rolls over at midnight while the screen stays open.
    var now by remember { mutableStateOf(currentInstant()) }
    LaunchedEffect(Unit) { while (true) { delay(CLOCK_TICK_MILLIS); now = currentInstant() } }
    val doses = CareDoses.today(data, now, TimeZone.currentSystemDefault())
    val firstOpen = doses.firstOrNull { !it.recorded }
    CategoryPanel(Category.CARE, illustration = Res.drawable.ill_capsule) {
        Text(vitaText(L10nKey.CARE_TODAY), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        Text(formatUiDate(now), style = MaterialTheme.typography.titleMedium)
        if (doses.isEmpty()) {
            Text(vitaText(L10nKey.CARE_NO_DOSES_TODAY), style = MaterialTheme.typography.bodyLarge)
        }
        doses.forEach { dose -> TodayDoseRow(dose) { onDose(dose) } }
        if (firstOpen == null) {
            ctx.state.dashboard?.nextDose?.let { next ->
                Text(vitaText(L10nKey.CARE_NEXT_SCHEDULED, "${next.medicationName} · ${formatUiDateTime(next.scheduledFor)}"), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            val label = vitaText(L10nKey.CARE_MARK_TAKEN_FOR, firstOpen.dose.medicationName)
            PrimaryButton(
                vitaText(L10nKey.CARE_MARK_TAKEN),
                {
                    ctx.runThenAnnounce(UserText.Key(L10nKey.CARE_DOSE_RECORDED_TAKEN, listOf(firstOpen.dose.medicationName))) {
                        ctx.controller.takeDose(firstOpen.dose)
                    }
                },
                Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = label; role = Role.Button },
                container = VezunaTokens.Pine,
                content = Color.White,
                icon = Res.drawable.ic_check_circle,
            )
        }
    }
}

/** One dose of today: time, medication and its recorded state, as one focus stop that opens the action sheet. */
@Composable
private fun TodayDoseRow(dose: CareDose, onClick: () -> Unit) {
    val d = dose.dose
    val time = d.scheduledFor.localTime()
    val detail = listOfNotNull(d.instructions, d.strength).joinToString(" · ")
    val state = doseStateText(dose)
    val description = vitaText(L10nKey.CARE_DOSE_ROW, time, listOf(d.medicationName, detail).filter { it.isNotBlank() }.joinToString(", "), state)
    Surface(
        shape = RoundedCornerShape(VezunaTokens.ControlRadius),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).clearAndSetSemantics {
            contentDescription = description
            role = Role.Button
        },
    ) {
        val timeText = @Composable { Text(time, style = MaterialTheme.typography.titleLarge.tabular(), fontWeight = FontWeight.Bold) }
        val details = @Composable { modifier: Modifier ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceXs)) {
                Text(d.medicationName, style = MaterialTheme.typography.titleMedium)
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DoseStateLabel(dose)
            }
        }
        val padding = Modifier.heightIn(min = VezunaTokens.RowHeight).padding(VezunaTokens.SpaceSmd)
        // At very large text the time sits above the name so the medication name keeps the full width.
        if (decorativeArtShown()) {
            Row(padding, horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd), verticalAlignment = Alignment.Top) {
                timeText()
                details(Modifier.weight(1f))
            }
        } else {
            Column(padding, verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceXs)) {
                timeText()
                details(Modifier)
            }
        }
    }
}

/**
 * Notification permission as the platform reports it (068, 070). Asking happens only through the system dialog;
 * a denied state hands off to the system settings. Schedules are saved either way, so this never blocks Care.
 */
@Composable
private fun ReminderAccessPanel(ctx: RouteContext, reminders: ReminderPlatformState) {
    val access by reminders.notificationAccess.collectAsState()
    var dismissed by rememberSaveable { mutableStateOf(false) }
    if (dismissed) return
    when (access) {
        NotificationAccess.NOT_REQUESTED -> if (NativeAction.REMINDERS in ctx.nativeActions) {
            CategoryPanel(Category.CARE, illustration = Res.drawable.ill_notification_bell) {
                Text(vitaText(L10nKey.CARE_REMINDERS_TITLE), style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearOfArt().semantics { heading() })
                Text(vitaText(L10nKey.CARE_REMINDERS_INTRO), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.clearOfArt())
                Text(vitaText(L10nKey.CARE_REMINDERS_LOCAL), style = MaterialTheme.typography.bodyMedium)
                PrimaryButton(vitaText(L10nKey.CARE_ENABLE_REMINDERS), { ctx.nativeActions.perform(NativeAction.REMINDERS) }, Modifier.fillMaxWidth(), container = VezunaTokens.Pine, content = Color.White)
                TertiaryButton(vitaText(L10nKey.CARE_NOT_NOW), { dismissed = true }, Modifier.fillMaxWidth(), color = VezunaTokens.Pine)
            }
        }
        NotificationAccess.DENIED -> CategoryPanel(Category.CARE, illustration = Res.drawable.ill_notification_off) {
            Text(vitaText(L10nKey.CARE_NOTIFICATIONS_OFF), style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearOfArt().semantics { heading() })
            Text(vitaText(L10nKey.CARE_NOTIFICATIONS_OFF_BODY), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.clearOfArt())
            reminders.openNotificationSettings?.let { open ->
                PrimaryButton(vitaText(L10nKey.CARE_OPEN_DEVICE_SETTINGS), open, Modifier.fillMaxWidth(), container = VezunaTokens.Pine, content = Color.White)
            }
            TertiaryButton(vitaText(L10nKey.CARE_NOT_NOW), { dismissed = true }, Modifier.fillMaxWidth(), color = VezunaTokens.Pine)
        }
        NotificationAccess.UNKNOWN, NotificationAccess.ALLOWED -> Unit
    }
}

@Composable
private fun MedicationList(ctx: RouteContext, data: ProfileData) {
    Column(verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
            SectionHeading(vitaText(L10nKey.MEDICATIONS), Modifier.weight(1f))
            SecondaryButton(vitaText(L10nKey.ADD_MEDICATION), { ctx.navigate(AppScreen.MedicationForm()) }, icon = Res.drawable.ic_plus)
        }
        GroupedRows {
            data.medications.forEach { medication ->
                val status = medication.status
                val detail = listOfNotNull(medication.instructions, medication.strength).joinToString(" · ").ifBlank { null }
                ListRow(
                    medication.displayName,
                    onClick = { ctx.navigate(AppScreen.MedicationDetail(medication.medicationId)) },
                    supporting = detail,
                    leadingIcon = Res.drawable.ic_capsule,
                    category = Category.CARE,
                    trailing = { MedicationStatusTag(status) },
                )
            }
        }
    }
}
