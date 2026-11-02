import BackgroundTasks
import Foundation
import os
import UserNotifications
import VezunaShared

final class MedicationNotificationCoordinator: NSObject, ObservableObject, NativeRepositoryObserver {
    static let takenAction = "VEZUNA_DOSE_TAKEN"
    static let skipAction = "VEZUNA_DOSE_SKIPPED"
    static let snoozeAction = "VEZUNA_DOSE_SNOOZE"
    static let category = "VEZUNA_DOSE_REMINDER"
    static let refreshTaskIdentifier = "app.vezuna.reminders.refresh"
    static let hideNamesKey = "vezuna.hideMedicationNames"
    /// Re-plan well before the 60-request budget (about 6 days at 9 doses a day) runs out.
    static let refreshInterval: TimeInterval = 12 * 60 * 60

    @Published private(set) var lastError: String?

    private let center: UNUserNotificationCenter
    private let planner: NotificationPlanner
    private let pendingResponses: PendingDoseResponseStore?
    private let defaults: UserDefaults
    private let log = Logger(subsystem: "app.vezuna", category: "reminders")
    private var ready = false
    private let reconcileLock = NSLock()
    private var reconcileTail: Task<Void, Never>?
    private var settingsObserver: NSObjectProtocol?
    private var lastHideNames: Bool

    init(
        center: UNUserNotificationCenter = .current(),
        calendar: Calendar = .current,
        pendingResponses: PendingDoseResponseStore? = try? PendingDoseResponseStore.standard(),
        defaults: UserDefaults = .standard
    ) {
        self.center = center
        self.pendingResponses = pendingResponses
        self.defaults = defaults
        self.lastHideNames = defaults.bool(forKey: Self.hideNamesKey)
        self.planner = NotificationPlanner(calendar: calendar)
        super.init()
        let taken = UNNotificationAction(identifier: Self.takenAction, title: L10n.text(.taken), options: [])
        let skip = UNNotificationAction(identifier: Self.skipAction, title: L10n.text(.skip), options: [])
        let snooze = UNNotificationAction(identifier: Self.snoozeAction, title: L10n.text(.snooze_10_minutes), options: [])
        center.setNotificationCategories([UNNotificationCategory(identifier: Self.category, actions: [taken, skip, snooze], intentIdentifiers: [])])
        center.delegate = self
        // The Settings toggle (written from the shared UI) changes how reminders are worded; re-plan when it flips.
        settingsObserver = NotificationCenter.default.addObserver(forName: UserDefaults.didChangeNotification, object: defaults, queue: .main) { [weak self] _ in
            guard let self, self.ready, self.defaults.bool(forKey: Self.hideNamesKey) != self.lastHideNames else { return }
            Task { await self.reconcile() }
        }
    }

    private var hideNames: Bool { defaults.bool(forKey: Self.hideNamesKey) }

    func repositoryDidChange() {
        guard ready else { return }
        Task { await reconcile() }
    }

    /// Registers the background task. Must run before `application(_:didFinishLaunchingWithOptions:)` returns.
    static func registerBackgroundRefresh(_ handler: @escaping (BGAppRefreshTask) -> Void) {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: refreshTaskIdentifier, using: nil) { task in
            guard let refresh = task as? BGAppRefreshTask else { task.setTaskCompleted(success: false); return }
            handler(refresh)
        }
    }

    func scheduleBackgroundRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: Self.refreshTaskIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: Self.refreshInterval)
        do {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.refreshTaskIdentifier)
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // Background refresh disabled by the user or unavailable (simulator): foreground reconciles still run.
            log.error("Could not schedule background refresh: \(error.localizedDescription, privacy: .public)")
        }
    }

    /// Reconciles run one after another: concurrent passes (activation, delivery, repository change, background
    /// refresh) would otherwise race on the pending-request diff and add duplicates or remove each other's requests.
    func reconcile() async {
        reconcileLock.lock()
        let previous = reconcileTail
        let task = Task { await previous?.value; await performReconcile() }
        reconcileTail = task
        reconcileLock.unlock()
        await task.value
    }

    private func performReconcile() async {
        ready = true
        await replayPendingResponses()
        let hidden = hideNames
        lastHideNames = hidden
        defer { scheduleBackgroundRefresh() }
        do {
            // Every active medication of every profile, not just the selected one (household reminders).
            let medications = try await loadMedications()
            let activeMedicationIds = Set(medications.map(\.medicationId))
            await removeDeliveredReminders(notIn: activeMedicationIds)
            guard !medications.isEmpty else {
                let pending = await center.pendingNotificationRequests().filter { $0.identifier.hasPrefix("vezuna.dose.") }
                center.removePendingNotificationRequests(withIdentifiers: pending.map(\.identifier))
                IosRuntime.shared.setReminderCoverage(untilEpochSeconds: -1)
                return
            }
            let settings = await center.notificationSettings()
            var authorization = settings.authorizationStatus
            if authorization == .notDetermined {
                authorization = try await center.requestAuthorization(options: [.alert, .sound, .badge]) ? .authorized : .denied
            }
            guard authorization == .authorized || authorization == .provisional || authorization == .ephemeral else {
                lastError = L10n.text(.notification_permission_off)
                IosRuntime.shared.setReminderCoverage(untilEpochSeconds: -1)
                return
            }
            let now = Date()
            let reminders = planner.reminders(for: medications, after: now)
            let pending = await center.pendingNotificationRequests().filter { $0.identifier.hasPrefix("vezuna.dose.") }
            let desired = Set(reminders.map { $0.identifier(hidingName: hidden) })
            // A snooze is user-scheduled: keep it unless its medication is no longer active; rebuild it when the privacy mode flipped.
            var rebuiltSnoozes: [DoseReminder] = []
            let stale = pending.filter { request in
                guard !desired.contains(request.identifier) else { return false }
                guard request.identifier.hasSuffix(DoseReminder.snoozeSuffix), var snoozed = reminder(from: request.content.userInfo) else { return true }
                guard activeMedicationIds.contains(snoozed.medicationId) else { return true }
                snoozed.isSnooze = true
                if snoozed.identifier(hidingName: hidden) != request.identifier, snoozed.scheduledAt > now { rebuiltSnoozes.append(snoozed) }
                return snoozed.identifier(hidingName: hidden) != request.identifier
            }
            center.removePendingNotificationRequests(withIdentifiers: stale.map(\.identifier))
            let existing = Set(pending.map(\.identifier))
            for reminder in reminders where !existing.contains(reminder.identifier(hidingName: hidden)) {
                try await center.add(request(for: reminder, hidden: hidden))
            }
            for snoozed in rebuiltSnoozes { try await center.add(request(for: snoozed, hidden: hidden)) }
            let coverage = planner.coverageEnd(of: reminders, after: now)
            IosRuntime.shared.setReminderCoverage(untilEpochSeconds: coverage.map { Int64($0.timeIntervalSince1970) } ?? -1)
            await reconcileDeliveredMissedDoses(activeMedicationIds: activeMedicationIds)
            lastError = nil
        } catch {
            lastError = error.localizedDescription
        }
    }

    private func request(for reminder: DoseReminder, hidden: Bool) -> UNNotificationRequest {
        let components = planner.calendar.dateComponents([.year, .month, .day, .hour, .minute], from: reminder.scheduledAt)
        return UNNotificationRequest(
            identifier: reminder.identifier(hidingName: hidden),
            content: Self.content(for: reminder, hidingName: hidden),
            trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
        )
    }

    /// With names hidden the lock screen shows a generic title; the name stays only in userInfo for the action handler.
    static func content(for reminder: DoseReminder, hidingName: Bool) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = hidingName ? L10n.text(.medication_reminder_title) : reminder.medicationName
        content.body = L10n.text(.scheduled_medication_reminder)
        content.sound = .default
        content.categoryIdentifier = category
        content.userInfo = userInfo(for: reminder)
        return content
    }

    private static func userInfo(for reminder: DoseReminder) -> [String: Any] {
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

    private func pendingResponse(from response: UNNotificationResponse) -> PendingDoseResponse? {
        let request = response.notification.request
        guard let reminder = reminder(from: request.content.userInfo) else { return nil }
        return PendingDoseResponse(
            notificationIdentifier: request.identifier,
            action: response.actionIdentifier,
            profileId: reminder.profileId,
            medicationId: reminder.medicationId,
            medicationName: reminder.medicationName,
            scheduleId: reminder.scheduleId,
            scheduledEpochSeconds: Int(reminder.scheduledAt.timeIntervalSince1970),
            receivedAt: Date()
        )
    }

    enum Outcome { case done, retry, drop }

    /// Records an action. `.retry` keeps it for a later attempt; `.drop` means it can never succeed (deleted medication).
    private func handle(_ response: PendingDoseResponse) async -> Outcome {
        let reminder = DoseReminder(
            profileId: response.profileId,
            medicationId: response.medicationId,
            medicationName: response.medicationName,
            scheduleId: response.scheduleId,
            scheduledAt: Date(timeIntervalSince1970: TimeInterval(response.scheduledEpochSeconds))
        )
        switch response.action {
        case Self.takenAction:
            let outcome = await record(reminder, status: "TAKEN")
            guard outcome == .done else { return clearDelivered(response, after: outcome) }
        case Self.skipAction:
            let outcome = await record(reminder, status: "SKIPPED")
            guard outcome == .done else { return clearDelivered(response, after: outcome) }
        case Self.snoozeAction:
            let snoozed = planner.snooze(reminder, from: response.receivedAt)
            if snoozed.scheduledAt > Date() {
                do { try await center.add(request(for: snoozed, hidden: hideNames)) } catch { lastError = L10n.text(.action_failed); return .retry }
            }
        default:
            return .done
        }
        center.removeDeliveredNotifications(withIdentifiers: [response.notificationIdentifier])
        return .done
    }

    /// A dropped action also clears its notification, so a button that can never work does not stay on screen.
    private func clearDelivered(_ response: PendingDoseResponse, after outcome: Outcome) -> Outcome {
        if outcome == .drop { center.removeDeliveredNotifications(withIdentifiers: [response.notificationIdentifier]) }
        return outcome
    }

    /// Replays actions saved while the runtime was not ready (for example a cold background launch from an action button).
    private func replayPendingResponses() async {
        guard let pendingResponses else { return }
        do {
            for response in try pendingResponses.load() {
                // The dose event id is deterministic, so a replay of an already recorded action is a no-op.
                let outcome = response.isExpired(now: Date()) ? .drop : await handle(response)
                if outcome != .retry { try pendingResponses.remove(response) }
            }
        } catch {
            log.error("Could not replay pending dose responses: \(error.localizedDescription, privacy: .public)")
            lastError = error.localizedDescription
        }
    }

    private func record(_ reminder: DoseReminder, status: String) async -> Outcome {
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
            return .done
        } catch {
            if case BridgeError.message(let message) = error, message.key == "medication_schedule_missing" { return .drop }
            lastError = error.localizedDescription
            return .retry
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
            guard await record(reminder, status: "MISSED") != .retry else { continue }
            center.removeDeliveredNotifications(withIdentifiers: [notification.request.identifier])
        }
    }

    private func loadMedications() async throws -> [NativeMedication] {
        let json = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<String, Error>) in
            let completion: (String?, NativeMessage?) -> Void = { json, error in
                if let json { continuation.resume(returning: json) }
                else { continuation.resume(throwing: BridgeError.message(error ?? NativeMessage(key: "medication_schedules_unavailable", args: []))) }
            }
            IosRuntime.shared.listAllActiveMedications(completion: completion)
        }
        return try JSONDecoder().decode([NativeMedication].self, from: Data(json.utf8))
    }
}

extension MedicationNotificationCoordinator: UNUserNotificationCenterDelegate {
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        // A delivery consumes budget: top the schedule up while the app is running.
        if ready { Task { await reconcile() } }
        return [.banner, .sound, .badge]
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard let pending = pendingResponse(from: response) else { return }
        guard ready else {
            // Persist first: the process may be suspended or killed before the vault is open.
            persist(pending)
            return
        }
        switch await handle(pending) {
        case .done: await reconcile()
        case .retry: persist(pending)
        case .drop: break
        }
    }

    private func persist(_ response: PendingDoseResponse) {
        do { try pendingResponses?.append(response) } catch {
            log.error("Could not persist notification response: \(error.localizedDescription, privacy: .public)")
            lastError = error.localizedDescription
        }
    }
}

enum BridgeError: LocalizedError {
    case message(NativeMessage)
    var errorDescription: String? {
        if case .message(let message) = self { return message.localized() }
        return nil
    }
}
