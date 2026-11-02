package app.vezuna.ui.nav

import androidx.compose.runtime.Composable
import app.vezuna.ui.AppScreen
import app.vezuna.ui.assistant.AssistantRoutes
import app.vezuna.ui.care.CareRoutes
import app.vezuna.ui.entry.EntryRoutes
import app.vezuna.ui.export.ExportRoutes
import app.vezuna.ui.more.MoreRoutes
import app.vezuna.ui.records.RecordsRoutes
import app.vezuna.ui.security.SecurityRoutes
import app.vezuna.ui.today.TodayRoutes
import app.vezuna.ui.trends.TrendsRoutes

/** Dispatches every route to its lane. Lanes edit their own `<Feature>Routes.kt`, not this file. */
@Composable
fun Route(screen: AppScreen, ctx: RouteContext) {
    when (screen) {
        AppScreen.Dashboard -> TodayRoutes.Dashboard(ctx)
        AppScreen.Welcome -> TodayRoutes.Welcome(ctx)
        AppScreen.FirstProfile -> TodayRoutes.FirstProfile(ctx)
        AppScreen.Profiles -> TodayRoutes.Profiles(ctx)
        AppScreen.AddProfile -> TodayRoutes.Profiles(ctx, focusNewName = true)
        is AppScreen.ProfileEdit -> TodayRoutes.ProfileEdit(screen, ctx)

        AppScreen.Labs -> RecordsRoutes.Labs(ctx)
        is AppScreen.ReportDetail -> RecordsRoutes.ReportDetail(screen, ctx)
        is AppScreen.SourceViewer -> RecordsRoutes.SourceViewer(screen, ctx)
        is AppScreen.ResultDetail -> RecordsRoutes.ResultDetail(screen, ctx)

        AppScreen.ImportReview -> EntryRoutes.ImportReview(ctx)
        AppScreen.ImportSource -> EntryRoutes.ImportSource(ctx)
        AppScreen.ImportExtract -> EntryRoutes.ImportExtract(ctx)
        is AppScreen.CandidateReview -> EntryRoutes.CandidateReview(screen, ctx)
        AppScreen.ManualEntry -> EntryRoutes.ManualEntry(ctx)
        is AppScreen.ResultEdit -> EntryRoutes.ResultEdit(screen, ctx)
        is AppScreen.ResultHistory -> EntryRoutes.ResultHistory(screen, ctx)

        AppScreen.Medications -> CareRoutes.Medications(ctx)
        is AppScreen.MedicationDetail -> CareRoutes.MedicationDetail(screen, ctx)
        is AppScreen.MedicationForm -> CareRoutes.MedicationForm(screen, ctx)
        is AppScreen.DoseHistory -> CareRoutes.DoseHistory(screen, ctx)

        AppScreen.Trends -> TrendsRoutes.Trends(ctx)
        is AppScreen.TrendDetail -> TrendsRoutes.TrendDetail(screen, ctx)
        AppScreen.Vitals -> TrendsRoutes.Vitals(ctx)
        is AppScreen.VitalsDetail -> TrendsRoutes.VitalsDetail(screen, ctx)
        AppScreen.Connections -> TrendsRoutes.Connections(ctx)

        AppScreen.Backups -> SecurityRoutes.Backups(ctx)
        AppScreen.LockSettings -> SecurityRoutes.LockSettings(ctx)
        AppScreen.DataPrivacy -> SecurityRoutes.DataPrivacy(ctx)

        AppScreen.More -> MoreRoutes.More(ctx)
        AppScreen.Settings -> MoreRoutes.Settings(ctx)
        AppScreen.Appearance -> MoreRoutes.Appearance(ctx)
        AppScreen.Language -> MoreRoutes.Language(ctx)
        AppScreen.About -> MoreRoutes.About(ctx)
        AppScreen.PrivacyPolicy -> MoreRoutes.PrivacyPolicy(ctx)
        AppScreen.Notices -> MoreRoutes.Notices(ctx)
        AppScreen.Help -> MoreRoutes.Help(ctx)

        AppScreen.AiChat -> AssistantRoutes.AiChat(ctx)
        AppScreen.Models -> AssistantRoutes.Models(ctx)
        is AppScreen.ModelDetail -> AssistantRoutes.ModelDetail(screen, ctx)
        AppScreen.Activation -> AssistantRoutes.Activation(ctx)

        AppScreen.ExportHub -> ExportRoutes.ExportHub(ctx)
        is AppScreen.ExportOptions -> ExportRoutes.ExportOptions(screen, ctx)
        is AppScreen.ExportSelection -> ExportRoutes.ExportSelection(screen, ctx)
        is AppScreen.ExportPreview -> ExportRoutes.ExportPreview(screen, ctx)
    }
}

/**
 * Parses a debug deep link such as `Labs`, `More` or `ResultDetail:obs-1` (screenshot harness only).
 * Returns null for an unknown name so a bad argument falls back to the normal start screen.
 */
fun routeFromName(text: String): AppScreen? {
    val name = text.substringBefore(':').trim()
    val arg = text.substringAfter(':', "").trim()
    val objects = listOf(
        AppScreen.Dashboard, AppScreen.Welcome, AppScreen.FirstProfile, AppScreen.Profiles,
        AppScreen.Labs, AppScreen.ImportReview, AppScreen.ImportSource, AppScreen.ImportExtract, AppScreen.ManualEntry,
        AppScreen.Medications, AppScreen.Trends, AppScreen.Vitals, AppScreen.Connections,
        AppScreen.Backups, AppScreen.LockSettings, AppScreen.DataPrivacy,
        AppScreen.More, AppScreen.Settings, AppScreen.Appearance, AppScreen.Language, AppScreen.About,
        AppScreen.PrivacyPolicy, AppScreen.Notices, AppScreen.Help,
        AppScreen.AiChat, AppScreen.Models, AppScreen.Activation, AppScreen.ExportHub,
    )
    objects.firstOrNull { it.toString() == name }?.let { return it }
    val withArg: Map<String, (String) -> AppScreen> = mapOf(
        "ProfileEdit" to AppScreen::ProfileEdit,
        "ReportDetail" to AppScreen::ReportDetail,
        "SourceViewer" to AppScreen::SourceViewer,
        "ResultDetail" to AppScreen::ResultDetail,
        "CandidateReview" to AppScreen::CandidateReview,
        "ResultEdit" to AppScreen::ResultEdit,
        "ResultHistory" to AppScreen::ResultHistory,
        "MedicationDetail" to AppScreen::MedicationDetail,
        "MedicationForm" to { id -> AppScreen.MedicationForm(id.ifEmpty { null }) },
        "DoseHistory" to { id -> AppScreen.DoseHistory(id.ifEmpty { null }) },
        "TrendDetail" to AppScreen::TrendDetail,
        "VitalsDetail" to AppScreen::VitalsDetail,
        "ModelDetail" to AppScreen::ModelDetail,
        "ExportOptions" to AppScreen::ExportOptions,
        "ExportSelection" to AppScreen::ExportSelection,
        "ExportPreview" to AppScreen::ExportPreview,
    )
    val factory = withArg[name] ?: return null
    if (arg.isEmpty() && name !in setOf("MedicationForm", "DoseHistory")) return null
    return factory(arg)
}
