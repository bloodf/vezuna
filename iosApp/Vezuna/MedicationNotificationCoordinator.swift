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
    static let lowSupplyKey = "vezuna.lowSupplyFireTimes"
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
        let appointmentRequests = await planAppointments(hidden: hidden)
        do {
            // Every active medication of every profile, not just the selected one (household reminders).
            let medications = try await loadMedications()
            let activeMedicationIds = Set(medications.map(\.medicationId))
            await removeDeliveredReminders(notIn: activeMedicationIds)
            guard !medications.isEmpty else {
                let pending = await center.pendingNotificationRequests().filter { $0.identifier.hasPrefix("vezuna.dose.") }
                center.removePendingNotificationRequests(withIdentifiers: pending.map(\.identifier))
                await clearLowSupply()
                IosRuntime.shared.setReminderCoverage(untilEpochSeconds: -1)
                return
            }
            let settings = await center.notificationSettings()
            var authorization = settings.authorizationStatus
            if authorization == .notDetermined {
                authorization = try await center.requestAuthorization(options: [.alert, .sound, .badge]) ? .authorized : .denied
            }
            let allowed = authorization == .authorized || authorization == .provisional || authorization == .ephemeral
            IosRuntime.shared.setNotificationAuthorization(allowed: allowed, denied: authorization == .denied)
            guard allowed else {
                lastError = L10n.text(.notification_permission_off)
                // Pending requests go. Fire times already in the past stay (that notice fired, so it must not repeat once
                // notifications are back); future ones are dropped, because they never fired and must be planned again.
                await removePendingLowSupply()
                let nowSeconds = Int64(Date().timeIntervalSince1970)
                let delivered = (defaults.dictionary(forKey: Self.lowSupplyKey) as? [String: Int64] ?? [:]).filter { $0.value <= nowSeconds }
                defaults.set(delivered, forKey: Self.lowSupplyKey)
                IosRuntime.shared.setReminderCoverage(untilEpochSeconds: -1)
                return
            }
            let now = Date()
            let plan = planner.plan(for: medications, after: now, limit: max(0, 60 - appointmentRequests))
            let reminders = plan.reminders
            let pending = await center.pendingNotificationRequests().filter { $0.identifier.hasPrefix("vezuna.dose.") }
            let desired = Set(reminders.map { $0.identifier(hidingName: hidden) })
            // Stored snoozes are matched by (schedule, time), not identifier: the name hashed into it may have changed since.
            func snoozeKey(_ reminder: DoseReminder) -> String { "\(reminder.scheduleId):\(Int(reminder.scheduledAt.timeIntervalSince1970))" }
            let stored = await storedSnoozes(for: medications, now: now)
            // A snooze is user-scheduled: keep it unless its medication is no longer active or its dose was recorded while it
            // waited (a request with no stored event stays); rebuild it when the privacy mode flipped.
            var rebuiltSnoozes: [DoseReminder] = []
            let stale = pending.filter { request in
                guard !desired.contains(request.identifier) else { return false }
                guard request.identifier.hasSuffix(DoseReminder.snoozeSuffix), var snoozed = Self.reminder(from: request.content.userInfo) else { return true }
                guard activeMedicationIds.contains(snoozed.medicationId), !stored.closedKeys.contains(snoozeKey(snoozed)) else { return true }
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
            let coveredSnoozes = Set(pending.filter { $0.identifier.hasSuffix(DoseReminder.snoozeSuffix) }.compactMap { Self.reminder(from: $0.content.userInfo) }.map(snoozeKey))
            for snoozed in stored.open where !coveredSnoozes.contains(snoozeKey(snoozed)) {
                try await center.add(request(for: snoozed, hidden: hidden))
            }
            let coverage = planner.coverageEnd(of: reminders, after: now, truncated: plan.truncated)
            IosRuntime.shared.setReminderCoverage(untilEpochSeconds: coverage.map { Int64($0.timeIntervalSince1970) } ?? -1)
            await planLowSupply(hidden: hidden)
            lastError = nil
            await reconcileDeliveredMissedDoses(activeMedicationIds: activeMedicationIds)
        } catch {
            lastError = error.localizedDescription
        }
    }

    /// Visits get their own share of the 64-request budget; the dose planner is given the rest.
    static let maxAppointmentReminders = 10
    static let appointmentHandledKey = "vezuna.appointmentHandled"

    /// Visit reminders: the plan comes from shared code and is the whole truth. Whatever is pending but absent from it (deleted
    /// appointment, reminder switched off, time moved) is cancelled, and so is a delivered one whose appointment no longer has a
    /// reminder. Local notifications only; nothing leaves the device. Returns how many requests it holds, so doses plan around them.
    private func planAppointments(hidden: Bool) async -> Int {
        do {
            let handled = defaults.stringArray(forKey: Self.appointmentHandledKey) ?? []
            let handledJSON = String(decoding: try JSONSerialization.data(withJSONObject: handled), as: UTF8.self)
            let json = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<String, Error>) in
                IosRuntime.shared.planAppointmentReminders(handledJson: handledJSON) { json, error in
                    if let json { continuation.resume(returning: json) }
                    else { continuation.resume(throwing: BridgeError.message(error ?? NativeMessage(key: "action_failed", args: []))) }
                }
            }
            let plan = try JSONDecoder().decode(AppointmentPlan.self, from: Data(json.utf8))
            let notices = Array(plan.notices.prefix(Self.maxAppointmentReminders))
            let pending = await center.pendingNotificationRequests().filter { $0.identifier.hasPrefix("vezuna.appointment.") }
            var allowed = false
            if !notices.isEmpty {
                var authorization = await center.notificationSettings().authorizationStatus
                if authorization == .notDetermined {
                    authorization = try await center.requestAuthorization(options: [.alert, .sound, .badge]) ? .authorized : .denied
                }
                allowed = authorization == .authorized || authorization == .provisional || authorization == .ephemeral
                IosRuntime.shared.setNotificationAuthorization(allowed: allowed, denied: authorization == .denied)
            }
            let wanted: Set<String> = allowed ? Set(notices.map(\.identifier)) : []
            center.removePendingNotificationRequests(withIdentifiers: Self.staleAppointmentIdentifiers(pending: pending.map(\.identifier), wanted: wanted))
            let delivered = await center.deliveredNotifications().map { $0.request.identifier }.filter { $0.hasPrefix("vezuna.appointment.") }
            center.removeDeliveredNotifications(withIdentifiers: Self.staleDeliveredAppointmentIdentifiers(delivered: delivered, reminderIds: Set(plan.reminderIds)))
            guard allowed else { return 0 }
            let now = Date(timeIntervalSince1970: TimeInterval(plan.nowEpochSeconds))
            var kept = handled.filter { key in plan.reminderIds.contains { key.hasPrefix($0 + ":") } }
            for notice in notices {
                try await center.add(Self.appointmentRequest(for: notice, hidingName: hidden, now: now))
                if !kept.contains(notice.key) { kept.append(notice.key) }
            }
            defaults.set(kept, forKey: Self.appointmentHandledKey)
            return notices.count
        } catch {
            log.error("Could not plan appointment reminders: \(error.localizedDescription, privacy: .public)")
            return 0
        }
    }

    /// Pending visit requests that the plan no longer wants.
    static func staleAppointmentIdentifiers(pending: [String], wanted: Set<String>) -> [String] {
        pending.filter { !wanted.contains($0) }
    }

    /// Delivered visit notifications whose appointment was deleted or has its reminder switched off.
    static func staleDeliveredAppointmentIdentifiers(delivered: [String], reminderIds: Set<String>) -> [String] {
        delivered.filter { !reminderIds.contains(String($0.dropFirst("vezuna.appointment.".count))) }
    }

    /// A time-interval trigger, so the reminder fires at the same instant wherever the phone travels; a calendar trigger without a
    /// time zone is read in the zone the phone is in when it fires. A reminder already due fires within seconds.
    static func appointmentRequest(for notice: AppointmentNotice, hidingName: Bool, now: Date, calendar: Calendar = .current) -> UNNotificationRequest {
        let content = UNMutableNotificationContent()
        content.title = L10n.text(.appointment_reminder_title)
        if hidingName {
            content.body = L10n.text(.appointment_reminder_generic_text)
        } else {
            let formatter = DateFormatter()
            formatter.calendar = calendar
            formatter.dateStyle = .short
            formatter.timeStyle = .short
            content.body = L10n.text(.appointment_reminder_text, notice.clinician, formatter.string(from: Date(timeIntervalSince1970: TimeInterval(notice.appointmentEpochSeconds))))
            if let profileName = notice.profileName { content.subtitle = L10n.text(.reminder_for_profile, profileName) }
        }
        content.sound = .default
        content.userInfo = ["appointmentId": notice.appointmentId]
        let interval = max(2, Date(timeIntervalSince1970: TimeInterval(notice.fireEpochSeconds)).timeIntervalSince(now))
        return UNNotificationRequest(identifier: notice.identifier, content: content, trigger: UNTimeIntervalNotificationTrigger(timeInterval: interval, repeats: false))
    }

    /// Low-supply reminders: the schedule comes from shared code; the fire time stored per key keeps a delivered one from repeating.
    private func planLowSupply(hidden: Bool) async {
        let storeKey = Self.lowSupplyKey
        let previous = defaults.dictionary(forKey: storeKey) as? [String: Int64] ?? [:]
        do {
            let previousJSON = String(decoding: try JSONSerialization.data(withJSONObject: previous), as: UTF8.self)
            let json = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<String, Error>) in
                IosRuntime.shared.planLowSupplyNotices(previousJson: previousJSON) { json, error in
                    if let json { continuation.resume(returning: json) }
                    else { continuation.resume(throwing: BridgeError.message(error ?? NativeMessage(key: "action_failed", args: []))) }
                }
            }
            let notices = try JSONDecoder().decode([LowSupplyNotice].self, from: Data(json.utf8))
            let pending = await center.pendingNotificationRequests().filter { $0.identifier.hasPrefix("vezuna.supply.") }
            let wanted = Set(notices.filter { $0.fireEpochSeconds != nil }.map(\.identifier))
            center.removePendingNotificationRequests(withIdentifiers: pending.map(\.identifier).filter { !wanted.contains($0) })
            var stored: [String: Int64] = [:]
            for notice in notices {
                stored[notice.key] = previous[notice.key]
                guard let seconds = notice.fireEpochSeconds else { continue }
                stored[notice.key] = seconds
                try await center.add(Self.supplyRequest(for: notice, at: Date(timeIntervalSince1970: TimeInterval(seconds)), hidingName: hidden, calendar: planner.calendar))
            }
            defaults.set(stored, forKey: storeKey)
        } catch {
            log.error("Could not plan low-supply reminders: \(error.localizedDescription, privacy: .public)")
        }
    }

    /// No active medication: nothing may fire later for a medication that is gone or paused.
    private func clearLowSupply() async {
        await removePendingLowSupply()
        defaults.removeObject(forKey: Self.lowSupplyKey)
    }

    private func removePendingLowSupply() async {
        let pending = await center.pendingNotificationRequests().filter { $0.identifier.hasPrefix("vezuna.supply.") }
        center.removePendingNotificationRequests(withIdentifiers: pending.map(\.identifier))
    }

    static func supplyRequest(for notice: LowSupplyNotice, at date: Date, hidingName: Bool, calendar: Calendar) -> UNNotificationRequest {
        let content = UNMutableNotificationContent()
        content.title = L10n.text(.supply_low_title)
        if hidingName {
            content.body = L10n.text(.supply_low_generic_text)
        } else {
            content.body = notice.lowNow ? L10n.text(.supply_low_text, notice.medicationName, notice.remaining) : L10n.text(.supply_low_soon_text, notice.medicationName)
            if let profileName = notice.profileName { content.subtitle = L10n.text(.reminder_for_profile, profileName) }
        }
        content.sound = .default
        content.userInfo = ["medicationId": notice.medicationId, "supplyKey": notice.key]
        let components = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        return UNNotificationRequest(identifier: notice.identifier, content: content, trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: false))
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
        // The member name follows the lock-screen privacy mode: hidden together with the medication name.
        content.body = hidingName ? L10n.text(.scheduled_medication_reminder)
            : reminder.profileName.map { L10n.text(.reminder_for_profile, $0) } ?? L10n.text(.scheduled_medication_reminder)
        content.sound = .default
        content.categoryIdentifier = category
        content.userInfo = userInfo(for: reminder)
        return content
    }

    private static func userInfo(for reminder: DoseReminder) -> [String: Any] {
        var info: [String: Any] = [
            "profileId": reminder.profileId,
            "medicationId": reminder.medicationId,
            "medicationName": reminder.medicationName,
            "scheduleId": reminder.scheduleId,
            "scheduledEpochSeconds": Int(reminder.scheduledAt.timeIntervalSince1970)
        ]
        if let profileName = reminder.profileName { info["profileName"] = profileName }
        if let original = reminder.originalScheduledAt { info["originalEpochSeconds"] = Int(original.timeIntervalSince1970) }
        return info
    }

    static func reminder(from userInfo: [AnyHashable: Any]) -> DoseReminder? {
        guard let profileId = userInfo["profileId"] as? String,
              let medicationId = userInfo["medicationId"] as? String,
              let medicationName = userInfo["medicationName"] as? String,
              let scheduleId = userInfo["scheduleId"] as? String,
              let seconds = userInfo["scheduledEpochSeconds"] as? NSNumber else { return nil }
        return DoseReminder(profileId: profileId, medicationId: medicationId, medicationName: medicationName, scheduleId: scheduleId, scheduledAt: Date(timeIntervalSince1970: seconds.doubleValue), profileName: userInfo["profileName"] as? String, originalScheduledAt: (userInfo["originalEpochSeconds"] as? NSNumber).map { Date(timeIntervalSince1970: $0.doubleValue) })
    }

    private func pendingResponse(from response: UNNotificationResponse) -> PendingDoseResponse? {
        let request = response.notification.request
        guard let reminder = Self.reminder(from: request.content.userInfo) else { return nil }
        return PendingDoseResponse(
            notificationIdentifier: request.identifier,
            action: response.actionIdentifier,
            profileId: reminder.profileId,
            medicationId: reminder.medicationId,
            medicationName: reminder.medicationName,
            scheduleId: reminder.scheduleId,
            scheduledEpochSeconds: Int(reminder.scheduledAt.timeIntervalSince1970),
            receivedAt: Date(),
            profileName: reminder.profileName,
            originalEpochSeconds: reminder.originalScheduledAt.map { Int($0.timeIntervalSince1970) }
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
            scheduledAt: Date(timeIntervalSince1970: TimeInterval(response.scheduledEpochSeconds)),
            profileName: response.profileName,
            originalScheduledAt: response.originalEpochSeconds.map { Date(timeIntervalSince1970: TimeInterval($0)) }
        )
        switch response.action {
        case Self.takenAction:
            let outcome = await record(reminder, status: "TAKEN")
            guard outcome == .done else { return clearDelivered(response, after: outcome) }
        case Self.skipAction:
            let outcome = await record(reminder, status: "SKIPPED")
            guard outcome == .done else { return clearDelivered(response, after: outcome) }
        case Self.snoozeAction:
            var snoozed = planner.snooze(reminder, from: response.receivedAt)
            if snoozed.scheduledAt > Date() {
                // Same stored event as the in-app snooze, so both paths look identical to the dashboard and to reconcile.
                let outcome = await recordSnooze(reminder, snoozed: &snoozed)
                guard outcome == .done else { return clearDelivered(response, after: outcome) }
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
                    scheduledEpochSeconds: Int64(reminder.doseTime.timeIntervalSince1970),
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

    /// `snoozed` moves to the second actually stored, which differs if another dose of the schedule already snoozed to it.
    private func recordSnooze(_ reminder: DoseReminder, snoozed: inout DoseReminder) async -> Outcome {
        do {
            let requested = Int64(snoozed.scheduledAt.timeIntervalSince1970)
            let stored = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Int64, Error>) in
                IosRuntime.shared.recordSnooze(
                    profileId: reminder.profileId,
                    medicationId: reminder.medicationId,
                    scheduleId: reminder.scheduleId,
                    shownEpochSeconds: Int64(reminder.scheduledAt.timeIntervalSince1970),
                    snoozedEpochSeconds: requested
                ) { seconds, error in
                    if let seconds { continuation.resume(returning: seconds.int64Value) }
                    else { continuation.resume(throwing: BridgeError.message(error ?? NativeMessage(key: "dose_record_failed", args: []))) }
                }
            }
            snoozed.scheduledAt = Date(timeIntervalSince1970: TimeInterval(stored))
            return .done
        } catch {
            if case BridgeError.message(let message) = error, message.key == "medication_schedule_missing" { return .drop }
            lastError = error.localizedDescription
            return .retry
        }
    }

    /// Future snoozes stored as events (in-app or notification): those still waiting, which may have no pending request yet
    /// (for example after an in-app snooze), and the (schedule, time) keys of those whose dose has been recorded.
    private func storedSnoozes(for medications: [NativeMedication], now: Date) async -> (open: [DoseReminder], closedKeys: Set<String>) {
        struct Stored: Decodable { let profileId: String; let medicationId: String; let scheduleId: String; let scheduledEpochSeconds: Int; let originalEpochSeconds: Int; let open: Bool }
        do {
            let json = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<String, Error>) in
                IosRuntime.shared.listStoredSnoozes { json, error in
                    if let json { continuation.resume(returning: json) }
                    else { continuation.resume(throwing: BridgeError.message(error ?? NativeMessage(key: "action_failed", args: []))) }
                }
            }
            let snoozes = try JSONDecoder().decode([Stored].self, from: Data(json.utf8))
            let open: [DoseReminder] = snoozes.filter(\.open).compactMap { stored in
                guard let medication = medications.first(where: { $0.medicationId == stored.medicationId }) else { return nil }
                let at = Date(timeIntervalSince1970: TimeInterval(stored.scheduledEpochSeconds))
                guard at > now else { return nil }
                return DoseReminder(profileId: stored.profileId, medicationId: stored.medicationId, medicationName: medication.displayName, scheduleId: stored.scheduleId, scheduledAt: at, isSnooze: true, profileName: medication.profileName, originalScheduledAt: Date(timeIntervalSince1970: TimeInterval(stored.originalEpochSeconds)))
            }
            return (open, Set(snoozes.filter { !$0.open }.map { "\($0.scheduleId):\($0.scheduledEpochSeconds)" }))
        } catch {
            log.error("Could not list pending snoozes: \(error.localizedDescription, privacy: .public)")
            return ([], [])
        }
    }

    /// Delivered reminders for paused, stopped or deleted medications (or deleted profiles) must not linger.
    private func removeDeliveredReminders(notIn activeMedicationIds: Set<String>) async {
        let stale = await center.deliveredNotifications().filter { notification in
            guard notification.request.identifier.hasPrefix("vezuna.dose."),
                  let reminder = Self.reminder(from: notification.request.content.userInfo) else { return false }
            return !activeMedicationIds.contains(reminder.medicationId)
        }
        center.removeDeliveredNotifications(withIdentifiers: stale.map { $0.request.identifier })
    }

    /// The 24-hour MISSED rule lives in shared code (same as Android); here only the leftover notifications are cleared.
    private func reconcileDeliveredMissedDoses(activeMedicationIds: Set<String>) async {
        do {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                IosRuntime.shared.recordMissedDoses { error in
                    if let error { continuation.resume(throwing: BridgeError.message(error)) }
                    else { continuation.resume() }
                }
            }
        } catch {
            lastError = error.localizedDescription
            return
        }
        let cutoff = Date().addingTimeInterval(-24 * 60 * 60)
        let expired = await center.deliveredNotifications().filter { notification in
            guard let reminder = Self.reminder(from: notification.request.content.userInfo) else { return false }
            return reminder.scheduledAt < cutoff && activeMedicationIds.contains(reminder.medicationId)
        }
        center.removeDeliveredNotifications(withIdentifiers: expired.map { $0.request.identifier })
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
