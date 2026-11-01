import Foundation
import UserNotifications
import VezunaShared

final class MedicationNotificationCoordinator: NSObject, ObservableObject, NativeRepositoryObserver {
    static let takenAction = "VEZUNA_DOSE_TAKEN"
    static let skipAction = "VEZUNA_DOSE_SKIPPED"
    static let snoozeAction = "VEZUNA_DOSE_SNOOZE"
    static let category = "VEZUNA_DOSE_REMINDER"

    @Published private(set) var lastError: String?

    private let center: UNUserNotificationCenter
    private let planner: NotificationPlanner
    private var ready = false
    private var deferredResponses: [UNNotificationResponse] = []

    init(center: UNUserNotificationCenter = .current(), calendar: Calendar = .current) {
        self.center = center
        self.planner = NotificationPlanner(calendar: calendar)
        super.init()
        let taken = UNNotificationAction(identifier: Self.takenAction, title: L10n.text(.taken), options: [])
        let skip = UNNotificationAction(identifier: Self.skipAction, title: L10n.text(.skip), options: [])
        let snooze = UNNotificationAction(identifier: Self.snoozeAction, title: L10n.text(.snooze_10_minutes), options: [])
        center.setNotificationCategories([UNNotificationCategory(identifier: Self.category, actions: [taken, skip, snooze], intentIdentifiers: [])])
        center.delegate = self
    }

    func repositoryDidChange() {
        guard ready else { return }
        Task { await reconcile() }
    }

    func reconcile() async {
        ready = true
        let responses = deferredResponses
        deferredResponses.removeAll()
        for response in responses { await handle(response) }
        do {
            let medications = try await loadMedications()
            let activeMedicationIds = Set(try await loadMedications(all: true).map(\.medicationId))
            await removeDeliveredReminders(notIn: activeMedicationIds)
            guard !medications.isEmpty else {
                let pending = await center.pendingNotificationRequests().filter { $0.identifier.hasPrefix("vezuna.dose.") }
                center.removePendingNotificationRequests(withIdentifiers: pending.map(\.identifier))
                return
            }
            let settings = await center.notificationSettings()
            var authorization = settings.authorizationStatus
            if authorization == .notDetermined {
                authorization = try await center.requestAuthorization(options: [.alert, .sound, .badge]) ? .authorized : .denied
            }
            guard authorization == .authorized || authorization == .provisional || authorization == .ephemeral else {
                lastError = L10n.text(.notification_permission_off)
                return
            }
            let reminders = planner.reminders(for: medications, after: Date())
            let pending = await center.pendingNotificationRequests().filter { $0.identifier.hasPrefix("vezuna.dose.") }
            let desired = Set(reminders.map(\.identifier))
            center.removePendingNotificationRequests(withIdentifiers: pending.map(\.identifier).filter { !desired.contains($0) })
            let existing = Set(pending.map(\.identifier))
            for reminder in reminders where !existing.contains(reminder.identifier) {
                try await center.add(request(for: reminder))
            }
            await reconcileDeliveredMissedDoses(activeMedicationIds: activeMedicationIds)
            lastError = nil
        } catch {
            lastError = error.localizedDescription
        }
    }

    private func request(for reminder: DoseReminder) -> UNNotificationRequest {
        let content = UNMutableNotificationContent()
        content.title = reminder.medicationName
        content.body = L10n.text(.scheduled_medication_reminder)
        content.sound = .default
        content.categoryIdentifier = Self.category
        content.userInfo = userInfo(for: reminder)
        let components = planner.calendar.dateComponents([.year, .month, .day, .hour, .minute], from: reminder.scheduledAt)
        return UNNotificationRequest(identifier: reminder.identifier, content: content, trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: false))
    }

    private func userInfo(for reminder: DoseReminder) -> [String: Any] {
        [
            "profileId": reminder.profileId,
            "medicationId": reminder.medicationId,
            "medicationName": reminder.medicationName,
            "scheduleId": reminder.scheduleId,
            "scheduledEpochSeconds": Int(reminder.scheduledAt.timeIntervalSince1970)
        ]
    }

    private func reminder(from userInfo: [AnyHashable: Any]) -> DoseReminder? {
        guard let profileId = userInfo["profileId"] as? String,
              let medicationId = userInfo["medicationId"] as? String,
              let medicationName = userInfo["medicationName"] as? String,
              let scheduleId = userInfo["scheduleId"] as? String,
              let seconds = userInfo["scheduledEpochSeconds"] as? NSNumber else { return nil }
        return DoseReminder(profileId: profileId, medicationId: medicationId, medicationName: medicationName, scheduleId: scheduleId, scheduledAt: Date(timeIntervalSince1970: seconds.doubleValue))
    }

    private func handle(_ response: UNNotificationResponse) async {
        guard let reminder = reminder(from: response.notification.request.content.userInfo) else { return }
        let deliveredID = response.notification.request.identifier
        switch response.actionIdentifier {
        case Self.takenAction:
            await record(reminder, status: "TAKEN")
            center.removeDeliveredNotifications(withIdentifiers: [deliveredID])
        case Self.skipAction:
            await record(reminder, status: "SKIPPED")
            center.removeDeliveredNotifications(withIdentifiers: [deliveredID])
        case Self.snoozeAction:
            let snoozed = planner.snooze(reminder, from: Date())
            do { try await center.add(request(for: snoozed)) } catch { lastError = L10n.text(.action_failed) }
            center.removeDeliveredNotifications(withIdentifiers: [deliveredID])
        default:
            break
        }
    }

    private func record(_ reminder: DoseReminder, status: String) async {
        do {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                IosRuntime.shared.recordDoseEvent(
                    profileId: reminder.profileId,
                    medicationId: reminder.medicationId,
                    scheduleId: reminder.scheduleId,
                    scheduledEpochSeconds: Int64(reminder.scheduledAt.timeIntervalSince1970),
                    status: status
                ) { error in
                    if let error { continuation.resume(throwing: BridgeError.message(error)) }
                    else { continuation.resume() }
                }
            }
        } catch {
            lastError = error.localizedDescription
        }
    }

    /// Delivered reminders for paused, stopped or deleted medications (or deleted profiles) must not linger.
    private func removeDeliveredReminders(notIn activeMedicationIds: Set<String>) async {
        let stale = await center.deliveredNotifications().filter { notification in
            guard notification.request.identifier.hasPrefix("vezuna.dose."),
                  let reminder = reminder(from: notification.request.content.userInfo) else { return false }
            return !activeMedicationIds.contains(reminder.medicationId)
        }
        center.removeDeliveredNotifications(withIdentifiers: stale.map { $0.request.identifier })
    }

    private func reconcileDeliveredMissedDoses(activeMedicationIds: Set<String>) async {
        let delivered = await center.deliveredNotifications()
        let cutoff = Date().addingTimeInterval(-24 * 60 * 60)
        for notification in delivered {
            guard let reminder = reminder(from: notification.request.content.userInfo), reminder.scheduledAt < cutoff,
                  activeMedicationIds.contains(reminder.medicationId) else { continue }
            await record(reminder, status: "MISSED")
            center.removeDeliveredNotifications(withIdentifiers: [notification.request.identifier])
        }
    }

    private func loadMedications(all: Bool = false) async throws -> [NativeMedication] {
        let json = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<String, Error>) in
            let completion: (String?, NativeMessage?) -> Void = { json, error in
                if let json { continuation.resume(returning: json) }
                else { continuation.resume(throwing: BridgeError.message(error ?? NativeMessage(key: "medication_schedules_unavailable", args: []))) }
            }
            if all { IosRuntime.shared.listAllActiveMedications(completion: completion) }
            else { IosRuntime.shared.listMedications(completion: completion) }
        }
        return try JSONDecoder().decode([NativeMedication].self, from: Data(json.utf8))
    }
}

extension MedicationNotificationCoordinator: UNUserNotificationCenterDelegate {
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound, .badge]
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard ready else {
            deferredResponses.append(response)
            return
        }
        await handle(response)
    }
}

enum BridgeError: LocalizedError {
    case message(NativeMessage)
    var errorDescription: String? {
        if case .message(let message) = self { return message.localized() }
        return nil
    }
}
