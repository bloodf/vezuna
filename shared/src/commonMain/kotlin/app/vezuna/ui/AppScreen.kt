package app.vezuna.ui

/**
 * Every route in the app, declared up front so feature lanes never edit the shell.
 * ui/nav/Routes.kt dispatches each one to its lane's `<Feature>Routes.kt`.
 * The five root screens are [Dashboard] (Today), [Labs] (Records), [Medications] (Care), [Trends] and [More].
 * IDs in routes are profile-bound; changing profile resets every back stack (AppController.onProfileChanged).
 */
sealed interface AppScreen {
    // Today, onboarding and profiles (L1)
    data object Dashboard : AppScreen
    data object Welcome : AppScreen
    data object FirstProfile : AppScreen
    data object Profiles : AppScreen

    /** The Profiles page with the new-name field focused (profile switcher "Add profile"). */
    data object AddProfile : AppScreen
    data class ProfileEdit(val profileId: String) : AppScreen

    // Records (L2)
    data object Labs : AppScreen
    data class ReportDetail(val reportId: String) : AppScreen
    data class SourceViewer(val documentId: String) : AppScreen
    data class ResultDetail(val observationId: String) : AppScreen
    data class HealthRecords(val kind: app.vezuna.domain.HealthRecordKind) : AppScreen

    /** Null adds a record. */
    data class HealthRecordForm(val kind: app.vezuna.domain.HealthRecordKind, val recordId: String? = null) : AppScreen
    data object Appointments : AppScreen

    /** Null adds a visit. */
    data class AppointmentForm(val appointmentId: String? = null) : AppScreen
    // region document vault
    data object Documents : AppScreen
    data class DocumentDetail(val documentId: String) : AppScreen
    // endregion document vault

    // Import, review and result entry (L3)
    data object ImportReview : AppScreen
    data object ImportSource : AppScreen
    data object ImportExtract : AppScreen
    data class CandidateReview(val candidateId: String) : AppScreen
    data object ManualEntry : AppScreen
    data class ResultEdit(val observationId: String) : AppScreen
    data class ResultHistory(val observationId: String) : AppScreen

    // Care (L4)
    data object Medications : AppScreen
    data class MedicationDetail(val medicationId: String) : AppScreen

    /** Null adds a medication. */
    data class MedicationForm(val medicationId: String? = null) : AppScreen

    /** Null shows every medication of the profile. */
    data class DoseHistory(val medicationId: String? = null) : AppScreen

    // Trends, device measurements and connections (L5)
    data object Trends : AppScreen
    data class TrendDetail(val testCode: String) : AppScreen
    data object Vitals : AppScreen
    data class VitalsDetail(val metric: String) : AppScreen
    data object Connections : AppScreen

    // Security, lock and backup (L6)
    data object Backups : AppScreen
    data object LockSettings : AppScreen
    data object DataPrivacy : AppScreen

    // More hub, preferences, about and help (L7)
    data object More : AppScreen
    data object Settings : AppScreen
    data object Appearance : AppScreen
    data object Language : AppScreen
    data object About : AppScreen
    data object PrivacyPolicy : AppScreen
    data object Notices : AppScreen
    data object Help : AppScreen
    data object EmergencyCard : AppScreen

    // Assistant and local models (L8)
    data object AiChat : AppScreen
    data object Models : AppScreen
    data class ModelDetail(val modelId: String) : AppScreen
    data object Activation : AppScreen

    // Exports and PDF (L9)
    data object ExportHub : AppScreen
    data class ExportOptions(val kind: String) : AppScreen
    data class ExportSelection(val kind: String) : AppScreen
    data class ExportPreview(val kind: String) : AppScreen
}
