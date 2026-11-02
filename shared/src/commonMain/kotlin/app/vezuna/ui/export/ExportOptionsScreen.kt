package app.vezuna.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.ProfileData
import app.vezuna.export.ExportOptions
import app.vezuna.export.PaperSize
import app.vezuna.localization.ContentLanguage
import app.vezuna.localization.L10nKey
import app.vezuna.localization.devicePaperTag
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.vitaPlural
import app.vezuna.localization.vitaText
import app.vezuna.platform.currentInstant
import app.vezuna.ui.AppScreen
import app.vezuna.ui.Category
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.CategoryPanel
import app.vezuna.ui.components.FieldPair
import app.vezuna.ui.components.GroupedRows
import app.vezuna.ui.components.LabeledField
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.ProfileAvatar
import app.vezuna.ui.components.SectionHeading
import app.vezuna.ui.components.SegmentedControl
import app.vezuna.ui.components.StatusKind
import app.vezuna.ui.components.StatusTag
import app.vezuna.ui.nav.RouteContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_lab

internal fun today() = currentInstant().toLocalDateTime(TimeZone.currentSystemDefault()).date

internal fun RouteContext.draft(kind: String): ExportDraft? {
    val data = state.data ?: return null
    return ExportSession.draft(data.profile.profileId, kind, PaperSize.forLanguageTag(devicePaperTag()))
}

/** Image 116: profile, period, sections, tests, language, paper; confirmed and excluded counts before the preview. */
@Composable
internal fun ExportOptionsScreen(ctx: RouteContext, kind: String) {
    val data = ctx.state.data ?: return
    val draft = ctx.draft(kind) ?: return
    val csv = kind == CSV_KIND
    val options = draft.options(today())
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = VezunaTokens.GutterNarrow),
        verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
    ) {
        Text(vitaText(draft.family?.style()?.title ?: L10nKey.EXPORT_CSV_HEADING), style = MaterialTheme.typography.titleLarge)
        GroupedRows(title = vitaText(L10nKey.EXPORT_FIELD_PROFILE)) {
            ListRow(data.profile.displayName, leading = { ProfileAvatar(data.profile.displayName) })
        }
        PeriodPicker(draft)
        SectionHeading(vitaText(L10nKey.EXPORT_SECTIONS))
        GroupedRows {
            val labCounts = options?.let { vitaText(L10nKey.EXPORT_SECTION_LABS_COUNTS, ExportScope.confirmedCount(data, it.copy(includeLabsPdf = true)), labTestCount(data, it)) }
            if (csv) {
                ListRow(vitaText(L10nKey.EXPORT_SECTION_LABS), supporting = labCounts)
                SwitchRow(vitaText(L10nKey.EXPORT_INCLUDE_MEDICATIONS_CSV), null, draft.medicationsInCsv) { draft.medicationsInCsv = it }
            } else {
                SwitchRow(vitaText(L10nKey.EXPORT_SECTION_LABS), labCounts, draft.includeLabs) { draft.includeLabs = it }
                SwitchRow(vitaText(L10nKey.EXPORT_SECTION_MEDS), null, draft.includeMedications) { draft.includeMedications = it }
                SwitchRow(vitaText(L10nKey.EXPORT_SECTION_DOSES), null, draft.includeDoses) { draft.includeDoses = it }
                SwitchRow(
                    vitaText(L10nKey.EXPORT_SECTION_DEVICE),
                    if (data.vitals.isEmpty) vitaText(L10nKey.EXPORT_SECTION_DEVICE_NONE) else null,
                    draft.includeDevice,
                ) { draft.includeDevice = it }
                SwitchRow(vitaText(L10nKey.EXPORT_SECTION_SOURCES), null, draft.includeSources) { draft.includeSources = it }
            }
            if (csv || draft.includeMedications || draft.includeDoses) {
                SwitchRow(vitaText(L10nKey.EXPORT_ACTIVE_MEDICATIONS_ONLY), null, draft.activeMedicationsOnly) { draft.activeMedicationsOnly = it }
            }
            val tests = ExportScope.tests(data)
            if ((csv || draft.includeLabs) && tests.isNotEmpty()) {
                val chosen = draft.testCodes.count { code -> tests.any { it.code == code } }
                ListRow(
                    vitaText(L10nKey.EXPORT_TESTS_ROW),
                    onClick = { ctx.navigate(AppScreen.ExportSelection(kind)) },
                    supporting = when {
                        draft.testCodes.isEmpty() -> vitaText(L10nKey.EXPORT_TESTS_ALL)
                        chosen == 0 -> vitaText(L10nKey.EXPORT_SELECTION_NONE)
                        else -> vitaText(L10nKey.EXPORT_TESTS_SOME, chosen, tests.size)
                    },
                    leadingIcon = Res.drawable.ic_lab,
                    category = Category.TRENDS,
                )
            }
        }
        LanguagePicker(draft)
        if (!csv) {
            Text(vitaText(L10nKey.EXPORT_PAPER_SIZE), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            SegmentedControl(
                listOf(vitaText(L10nKey.EXPORT_PAPER_A4), vitaText(L10nKey.EXPORT_PAPER_LETTER)),
                if (draft.paper == PaperSize.A4) 0 else 1,
                { draft.paper = if (it == 0) PaperSize.A4 else PaperSize.LETTER },
            )
            GroupedRows { SwitchRow(vitaText(L10nKey.EXPORT_GRAYSCALE), null, draft.grayscale) { draft.grayscale = it } }
        }
        options?.let { ScopeSummary(data, it) }
        PrimaryButton(
            vitaText(if (csv) L10nKey.EXPORT_PREVIEW_CSV_ACTION else L10nKey.EXPORT_PREVIEW_ACTION),
            {
                ctx.controller.clearExports()
                ctx.navigate(AppScreen.ExportPreview(kind))
            },
            Modifier.fillMaxWidth(),
            enabled = options != null,
        )
        Spacer(Modifier.height(VezunaTokens.SpaceLg))
    }
}

private fun labTestCount(data: ProfileData, options: ExportOptions): Int =
    options.apply(data).observations.filter { it.confirmation == ConfirmationStatus.CONFIRMED }
        .map { app.vezuna.domain.UnitNormalizer.canonicalTestCode(it.testCode) }.distinct().size

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeriodPicker(draft: ExportDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
        Text(vitaText(L10nKey.EXPORT_PERIOD), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        val labels = mapOf(
            ExportPeriod.ALL to L10nKey.EXPORT_PERIOD_ALL,
            ExportPeriod.LAST_3_MONTHS to L10nKey.EXPORT_PERIOD_3M,
            ExportPeriod.LAST_12_MONTHS to L10nKey.EXPORT_PERIOD_12M,
            ExportPeriod.CUSTOM to L10nKey.EXPORT_PERIOD_CUSTOM,
        )
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
            labels.forEach { (period, key) ->
                val selected = draft.period == period
                FilterChip(
                    selected = selected,
                    onClick = { draft.period = period },
                    label = { Text(vitaText(key)) },
                    modifier = Modifier.heightIn(min = VezunaTokens.TouchTarget).semantics { role = Role.RadioButton; this.selected = selected },
                )
            }
        }
        if (draft.period == ExportPeriod.CUSTOM) {
            val error = when {
                draft.fromInvalid || draft.toInvalid -> vitaText(L10nKey.EXPORT_DATE_INVALID)
                draft.reversed -> vitaText(L10nKey.EXPORT_DATE_RANGE_REVERSED)
                else -> null
            }
            FieldPair(
                first = { modifier ->
                    LabeledField(vitaText(L10nKey.EXPORT_FROM_DATE), draft.fromText, { draft.fromText = it }, modifier, hint = vitaText(L10nKey.EXPORT_DATE_HINT), error = error.takeIf { draft.fromInvalid || draft.reversed })
                },
                second = { modifier ->
                    LabeledField(vitaText(L10nKey.EXPORT_TO_DATE), draft.toText, { draft.toText = it }, modifier, hint = vitaText(L10nKey.EXPORT_DATE_HINT), error = error.takeIf { draft.toInvalid || draft.reversed })
                },
            )
        } else {
            draft.bounds(today()).let { (from, to) ->
                if (from != null && to != null) Text("${formatUiDate(from.atNoonUtc())} – ${formatUiDate(to.atNoonUtc())}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Collection days are stored at noon UTC; formatting that instant shows the same calendar day everywhere. */
internal fun LocalDate.atNoonUtc() = LocalDateTime(this, LocalTime(12, 0)).toInstant(TimeZone.UTC)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguagePicker(draft: ExportDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
        Text(vitaText(L10nKey.EXPORT_LANGUAGE), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
            (listOf<ContentLanguage?>(null) + ContentLanguage.entries).forEach { language ->
                val selected = draft.language == language
                FilterChip(
                    selected = selected,
                    onClick = { draft.language = language },
                    label = { Text(language?.nativeName ?: vitaText(L10nKey.EXPORT_LANGUAGE_DEVICE)) },
                    modifier = Modifier.heightIn(min = VezunaTokens.TouchTarget).semantics { role = Role.RadioButton; this.selected = selected },
                )
            }
        }
        Text(vitaText(L10nKey.EXPORT_LANGUAGE_EXPLANATION), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Only confirmed results are exported; how many are in, and how many unreviewed values stay out. */
@Composable
internal fun ScopeSummary(data: ProfileData, options: ExportOptions) {
    val unreviewed = options.unreviewedCount(data)
    CategoryPanel(Category.TRENDS) {
        Text(vitaText(L10nKey.EXPORT_CONFIRMED_ONLY), style = MaterialTheme.typography.titleMedium)
        if (options.includeLabsPdf) {
            Text(vitaText(L10nKey.EXPORT_SECTION_LABS_COUNTS, ExportScope.confirmedCount(data, options), labTestCount(data, options)), style = MaterialTheme.typography.bodyLarge)
        }
        if (unreviewed > 0) StatusTag(vitaPlural(L10nKey.EXPORT_UNREVIEWED_EXCLUDED, unreviewed), StatusKind.WARNING)
    }
}

/** One focus stop that reads label, supporting text and on/off; the whole row toggles. */
@Composable
internal fun SwitchRow(label: String, supporting: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = VezunaTokens.RowHeight)
            .padding(horizontal = VezunaTokens.SpaceMd, vertical = VezunaTokens.SpaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

private const val NO_TESTS = "\u0000none"

/** Image 117: confirmed tests with their latest reading; all checked means every test (empty selection). */
@Composable
internal fun ExportSelectionScreen(ctx: RouteContext, kind: String) {
    val data = ctx.state.data ?: return
    val draft = ctx.draft(kind) ?: return
    val tests = ExportScope.tests(data)
    val selected = if (draft.testCodes.isEmpty()) tests.map { it.code }.toSet() else draft.testCodes.filterTo(mutableSetOf()) { code -> tests.any { it.code == code } }
    val unreviewed = (draft.options(today()) ?: ExportOptions()).copy(testCodes = emptySet()).unreviewedCount(data)
    fun update(codes: Set<String>) {
        // An empty set means every test, so "none" is kept as a code no test has; the export then has no results.
        draft.testCodes = when (codes.size) {
            tests.size -> emptySet()
            0 -> setOf(NO_TESTS)
            else -> codes
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = VezunaTokens.GutterNarrow),
        verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
    ) {
        Text(vitaPlural(L10nKey.EXPORT_SELECTION_COUNT, tests.sumOf { it.count }), style = MaterialTheme.typography.titleMedium)
        GroupedRows {
            tests.forEach { test ->
                val checked = test.code in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = checked, role = Role.Checkbox, onValueChange = { update(if (it) selected + test.code else selected - test.code) })
                        .heightIn(min = VezunaTokens.RowHeight)
                        .padding(horizontal = VezunaTokens.SpaceMd, vertical = VezunaTokens.SpaceSm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd),
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Column(Modifier.weight(1f)) {
                        Text(test.name, style = MaterialTheme.typography.titleMedium)
                        Text(vitaText(L10nKey.EXPORT_SELECTION_LATEST, test.latestValue, formatUiDate(test.latestAt)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (unreviewed > 0) StatusTag(vitaPlural(L10nKey.EXPORT_UNREVIEWED_EXCLUDED, unreviewed), StatusKind.WARNING)
        if (selected.isEmpty()) StatusTag(vitaText(L10nKey.EXPORT_SELECTION_NONE), StatusKind.ERROR)
        PrimaryButton(vitaText(L10nKey.EXPORT_CONTINUE), { ctx.back() }, Modifier.fillMaxWidth(), enabled = selected.isNotEmpty())
        Spacer(Modifier.height(VezunaTokens.SpaceLg))
    }
}
