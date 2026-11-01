import Foundation
import VezunaShared

enum NativeString: String {
    case privacy_policy, privacy_policy_unavailable
    case third_party_notices, third_party_notices_unavailable
    case taken, skip, snooze_10_minutes, notification_permission_off, scheduled_medication_reminder
    case medication_schedules_unavailable, native_actions, storage_unavailable, opening_storage
    case import_lab_source, healthkit_import_menu, export_backup_menu, ai_activation_menu
    case source, photo_library, camera, pdf_or_image, extracting_text, review_extracted_results
    case test_code, test_name, value, unit, reference_lower, reference_upper
    case add_unconfirmed_review, offline_import, close, encrypting_saving
    case extraction_results_ocr, extraction_results_embedded
    case export_always_available, doctor_export, share_save_csv, share_save_pdf, profile_backup
    case share_save_encrypted_backup, backup_profile_description, export_title, preparing_device, export_prepare_failed
    case healthkit_description, healthkit_unavailable, authorize_import_health, reading_health, latest_batch
    case heart_rate_samples, step_samples, weight_samples, deleted_sample_references, health_import
    case premium_ai_activation, refresh_app_store, store_verification_description, premium_inactive
    case premium_verified, premium_offline_until, store_verification_failed, ai_activation
    case model_premium_required, model_asset_size_mismatch, model_asset_unavailable
    case import_unreadable_pdf, import_unreadable_image, no_text_found, no_structured_results
    case camera_unavailable, invalid_selection, healthkit_type_unavailable
    case bridge_storage_not_ready, medication_schedule_missing, dose_record_failed
    case active_profile_changed, import_save_failed, storage_open_failed, action_failed, select_profile_first
    case model_catalog_invalid
    case collection_date, collection_date_required, apply_date_to_all
    case ocr_read_token, ambiguous_number_warning, choose_date_button
    case reference_text
    case app_lock_reason
    case export_language, export_language_device
    case export_options, export_from_date, export_to_date, export_active_medications_only, export_include_medications_csv
}

enum L10n {
    static func text(_ key: NativeString, _ args: CVarArg...) -> String {
        let format = NSLocalizedString(key.rawValue, comment: "")
        guard !args.isEmpty else { return format }
        return String(format: format, locale: .current, arguments: args)
    }

    static func plural(_ key: NativeString, _ count: Int) -> String {
        String.localizedStringWithFormat(NSLocalizedString(key.rawValue, comment: ""), count)
    }

    static func message(_ message: NativeMessage) -> String {
        let format = NSLocalizedString(message.key, comment: "")
        guard !message.args.isEmpty else { return format }
        return String(format: format, locale: .current, arguments: message.args.map { $0 as CVarArg })
    }
}

extension NativeMessage {
    func localized() -> String { L10n.message(self) }
}
