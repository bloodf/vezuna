package app.vezuna.ui.care

import androidx.compose.runtime.Composable
import app.vezuna.domain.Medication
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import app.vezuna.ui.AppScreen
import app.vezuna.ui.components.EmptyState
import app.vezuna.ui.components.FocusedTaskScaffold
import app.vezuna.ui.nav.RootScaffold
import app.vezuna.ui.nav.RouteContext
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ill_capsule

/** Lane L4 (Care): medications, schedules, doses, reminders and dose history. */
object CareRoutes {
    @Composable
    fun Medications(ctx: RouteContext) = RootScaffold(ctx, vitaText(L10nKey.NAV_CARE), vitaText(L10nKey.CARE_SUBTITLE)) {
        CareHome(ctx)
    }

    @Composable
    fun MedicationDetail(screen: AppScreen.MedicationDetail, ctx: RouteContext) = FocusedTaskScaffold(title = null, onBack = ctx::back) {
        withMedication(ctx, screen.medicationId) { MedicationDetailBody(ctx, it) }
    }

    @Composable
    fun MedicationForm(screen: AppScreen.MedicationForm, ctx: RouteContext) =
        FocusedTaskScaffold(title = vitaText(if (screen.medicationId == null) L10nKey.ADD_MEDICATION else L10nKey.EDIT_MEDICATION), onBack = ctx::back) {
        val id = screen.medicationId
        if (id == null) MedicationFormBody(ctx, null) else withMedication(ctx, id) { MedicationFormBody(ctx, it) }
    }

    @Composable
    fun DoseHistory(screen: AppScreen.DoseHistory, ctx: RouteContext) = FocusedTaskScaffold(title = vitaText(L10nKey.DOSE_HISTORY), onBack = ctx::back) {
        val id = screen.medicationId
        if (id == null) DoseHistoryBody(ctx, null) else withMedication(ctx, id) { DoseHistoryBody(ctx, it) }
    }

    /** Routes carry IDs of the current profile; a medication deleted meanwhile says so instead of showing stale data. */
    @Composable
    private fun withMedication(ctx: RouteContext, medicationId: String, content: @Composable (Medication) -> Unit) {
        val data = ctx.state.data ?: return
        val medication = data.medications.firstOrNull { it.medicationId == medicationId }
        if (medication == null) {
            EmptyState(Res.drawable.ill_capsule, vitaText(L10nKey.MEDICATION_NOT_FOUND), vitaText(L10nKey.CARE_EMPTY_BODY))
        } else {
            content(medication)
        }
    }
}
