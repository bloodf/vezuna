package app.vezuna.ui.today

import app.vezuna.ui.components.clearOfNavigationBar
import app.vezuna.ui.components.clearOfArt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vezuna.domain.DashboardDose
import app.vezuna.domain.DashboardSnapshot
import app.vezuna.domain.ProfileData
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.formatUiDateTime
import app.vezuna.localization.message
import app.vezuna.localization.vitaPlural
import app.vezuna.localization.vitaText
import app.vezuna.platform.currentInstant
import app.vezuna.ui.AppScreen
import app.vezuna.ui.AppUiState
import app.vezuna.ui.Category
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.CategoryPanel
import app.vezuna.ui.components.EmptyState
import app.vezuna.ui.components.GroupedRows
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.ProgressState
import app.vezuna.ui.components.TertiaryButton
import app.vezuna.ui.nav.Destination
import app.vezuna.ui.nav.NativeAction
import app.vezuna.ui.nav.RouteContext
import app.vezuna.ui.reportDate
import app.vezuna.ui.reportDisplayName
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_capsule
import vezuna.shared.generated.resources.ic_chevron_right
import vezuna.shared.generated.resources.ic_clock
import vezuna.shared.generated.resources.ic_file_text
import vezuna.shared.generated.resources.ic_lock
import vezuna.shared.generated.resources.ic_plus
import vezuna.shared.generated.resources.ic_upload
import vezuna.shared.generated.resources.ill_capsule
import vezuna.shared.generated.resources.ill_file_error
import vezuna.shared.generated.resources.ill_record_empty

/** The device's calendar day, formatted like other UI dates (which read calendar days as noon UTC). */
@Composable
internal fun todayLabel(): String {
    val today = currentInstant().toLocalDateTime(TimeZone.currentSystemDefault()).date
    return formatUiDate(today.atTime(12, 0).toInstant(TimeZone.UTC))
}

/** Image 001 (populated) and 004 (first use). Every count and value comes from the active profile's state. */
@Composable
internal fun TodayScreen(ctx: RouteContext) {
    val data = ctx.state.data
    val snapshot = ctx.state.dashboard
    if (startupFailed(ctx.state)) return StartupFailed(ctx)
    if (data == null || snapshot == null) return ProgressState(vitaText(L10nKey.PLEASE_WAIT))
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).clearOfNavigationBar().padding(horizontal = VezunaTokens.GutterNarrow, vertical = VezunaTokens.SpaceMd),
        verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
    ) {
        if (isTodayEmpty(data)) {
            TodayEmpty(ctx)
        } else {
            DoseSection(ctx, data, snapshot)
            if (snapshot.reviewCount > 0) ReviewPanel(snapshot.reviewCount, reviewSourceName(data)) { ctx.navigate(AppScreen.ImportReview) }
            RecentRecords(ctx, data)
            PrimaryButton(vitaText(L10nKey.TODAY_ADD_RECORD), { addRecord(ctx) }, Modifier.fillMaxWidth(), icon = Res.drawable.ic_plus)
        }
        SavedOnDevice()
    }
}

/** Loading ended with nothing to show: the load failed, so a spinner here would never finish. */
internal fun startupFailed(state: AppUiState): Boolean = !state.loading && (state.data == null || state.dashboard == null)

@Composable
private fun StartupFailed(ctx: RouteContext) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).clearOfNavigationBar().padding(horizontal = VezunaTokens.GutterNarrow)) {
        EmptyState(
            illustration = Res.drawable.ill_file_error,
            title = vitaText(L10nKey.VAULT_RECOVERY_TITLE),
            body = vitaText(L10nKey.VAULT_RECOVERY_BODY_UNAVAILABLE),
            // Announced like the ProgressState it replaces, so a screen reader hears the wait end.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            actionLabel = vitaText(L10nKey.VAULT_RECOVERY_RETRY),
            onAction = { ctx.scope.launch { ctx.controller.initialize() } },
        )
    }
}

/** iOS imports through its native picker and extractor; Android through the shared import flow. */
private fun addRecord(ctx: RouteContext) {
    if (NativeAction.IMPORT_SOURCE in ctx.nativeActions) ctx.nativeActions.perform(NativeAction.IMPORT_SOURCE) else ctx.navigate(AppScreen.ImportSource)
}

@Composable
private fun DoseSection(ctx: RouteContext, data: ProfileData, snapshot: DashboardSnapshot) {
    val next = snapshot.nextDose
    when {
        next != null -> NextDosePanel(ctx, next)
        data.medications.isNotEmpty() -> GroupedRows {
            ListRow(
                vitaText(L10nKey.TODAY_NO_UPCOMING_DOSE),
                onClick = { ctx.controller.selectRoot(Destination.CARE) },
                leadingIcon = Res.drawable.ic_capsule,
                category = Category.CARE,
            )
        }
    }
    // Taken out of recorded terminal events, so the denominator is always shown with the percentage.
    snapshot.adherence.fraction?.let { fraction ->
        Text(
            vitaText(L10nKey.ADHERENCE_30_DAY) + ": " +
                vitaText(L10nKey.ADHERENCE_SUMMARY, (fraction * 100).roundToInt(), snapshot.adherence.taken, snapshot.adherence.completed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val later = snapshot.upcoming.drop(1)
    if (later.isNotEmpty()) {
        GroupedRows(title = vitaText(L10nKey.NEXT_REMINDERS)) {
            later.forEach { dose ->
                ListRow(dose.medicationName, supporting = formatUiDateTime(dose.scheduledFor), leadingIcon = Res.drawable.ic_clock, category = Category.CARE)
            }
        }
    }
}

@Composable
private fun NextDosePanel(ctx: RouteContext, dose: DashboardDose) {
    val ink = Category.CARE.onFill
    // Announce only after the event is stored; a failure shows in the feedback host instead.
    fun record(action: suspend () -> Unit, notice: L10nKey) {
        ctx.scope.launch {
            action()
            if (ctx.controller.state.value.error == null) ctx.controller.announce(message(notice, dose.medicationName))
        }
    }
    CategoryPanel(Category.CARE, illustration = Res.drawable.ill_capsule) {
        // The end padding keeps the text clear of the illustration; at large sizes the text wraps instead.
        val clearOfArt = Modifier.clearOfArt()
        Text(vitaText(L10nKey.NEXT_DOSE), style = MaterialTheme.typography.titleMedium, modifier = clearOfArt.semantics { heading() })
        Text(vitaText(L10nKey.TODAY_SCHEDULED_FOR, formatUiDateTime(dose.scheduledFor)), style = MaterialTheme.typography.bodyLarge, modifier = clearOfArt)
        Text(dose.medicationName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = clearOfArt)
        listOfNotNull(dose.instructions, dose.strength).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge)
        }
        PrimaryButton(
            vitaText(L10nKey.TODAY_MARK_TAKEN),
            { record({ ctx.controller.takeDose(dose) }, L10nKey.TODAY_DOSE_TAKEN) },
            Modifier.fillMaxWidth().padding(top = VezunaTokens.SpaceSm),
            loading = ctx.state.loading,
            container = VezunaTokens.Pine,
            content = Color.White,
        )
        // Wraps to two lines at large text sizes instead of truncating.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TertiaryButton(vitaText(L10nKey.SKIP), { record({ ctx.controller.skipDose(dose) }, L10nKey.TODAY_DOSE_SKIPPED) }, enabled = !ctx.state.loading, color = ink)
            TertiaryButton(vitaText(L10nKey.MINUTES_SNOOZE), { record({ ctx.controller.snoozeDose(dose) }, L10nKey.TODAY_DOSE_SNOOZED) }, enabled = !ctx.state.loading, color = ink)
        }
    }
}

@Composable
private fun ReviewPanel(count: Int, sourceName: String?, onOpen: () -> Unit) {
    CategoryPanel(Category.RECORDS, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd)) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Icon(painterResource(Res.drawable.ic_file_text), contentDescription = null, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(vitaPlural(L10nKey.TODAY_VALUES_TO_REVIEW, count), style = MaterialTheme.typography.titleLarge)
                sourceName?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            }
            Icon(painterResource(Res.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun RecentRecords(ctx: RouteContext, data: ProfileData) {
    val reports = recentConfirmedReports(data)
    if (reports.isEmpty()) return
    val manualName = vitaText(L10nKey.MANUAL_ENTRY_REPORT)
    GroupedRows(title = vitaText(L10nKey.TODAY_RECENT_RECORDS)) {
        reports.forEach { report ->
            ListRow(
                reportDisplayName(report, data, manualName),
                onClick = { ctx.navigate(AppScreen.ReportDetail(report.reportId)) },
                supporting = vitaText(L10nKey.TODAY_REPORT_CONFIRMED, formatUiDate(reportDate(report))),
                leadingIcon = Res.drawable.ic_file_text,
                category = Category.RECORDS,
            )
        }
    }
}

@Composable
private fun TodayEmpty(ctx: RouteContext) {
    CategoryPanel(Category.RECORDS) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd)) {
            Image(painterResource(Res.drawable.ill_record_empty), contentDescription = null, modifier = Modifier.widthIn(max = 220.dp).fillMaxWidth().heightIn(max = 165.dp))
            Text(vitaText(L10nKey.TODAY_EMPTY_TITLE), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Text(vitaText(L10nKey.TODAY_EMPTY_BODY), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            PrimaryButton(
                vitaText(L10nKey.TODAY_IMPORT_REPORT), { addRecord(ctx) }, Modifier.fillMaxWidth(),
                icon = Res.drawable.ic_upload, container = VezunaTokens.Pine, content = Color.White,
            )
            PrimaryButton(
                vitaText(L10nKey.ADD_MEDICATION), { ctx.navigate(AppScreen.MedicationForm()) }, Modifier.fillMaxWidth(),
                icon = Res.drawable.ic_capsule, container = VezunaTokens.Paper, content = VezunaTokens.Pine,
            )
        }
    }
}

@Composable
private fun SavedOnDevice() {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(Res.drawable.ic_lock), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(vitaText(L10nKey.TODAY_SAVED_ON_DEVICE), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
