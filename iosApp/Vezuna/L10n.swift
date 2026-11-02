import Foundation
import VezunaShared

enum NativeString: String {
    case privacy_policy, privacy_policy_unavailable
    case third_party_notices, third_party_notices_unavailable
    case taken, skip, snooze_10_minutes, notification_permission_off, scheduled_medication_reminder, reminder_for_profile
    case medication_schedules_unavailable, storage_unavailable, opening_storage
    case camera, add_page, photos_done, pages_in_report
    case value
    case close
    case healthkit_description, healthkit_unavailable, authorize_import_health, reading_health, latest_batch
    case heart_rate_samples, step_samples, weight_samples, health_import
    case health_no_readings, health_revoke_ios
    case refresh_app_store, store_verification_description, premium_inactive
    case premium_verified, premium_offline_until, store_verification_failed, ai_activation
    case model_premium_required, model_asset_size_mismatch, model_asset_unavailable
    case import_unreadable_pdf, import_unreadable_image, no_text_found, no_structured_results
    case camera_unavailable, invalid_selection, healthkit_type_unavailable
    case bridge_storage_not_ready, medication_schedule_missing, dose_record_failed
    case storage_open_failed, action_failed, select_profile_first
    case model_catalog_invalid
    case medication_reminder_title
    case supply_low_title, supply_low_text, supply_low_generic_text
    case app_lock_reason
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
