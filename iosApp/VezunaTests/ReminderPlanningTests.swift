import XCTest
@testable import Vezuna

final class ReminderPlanningTests: XCTestCase {
    private var calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar
    }()

    private func medication(_ id: String, profile: String, times: [String]) -> NativeMedication {
        NativeMedication(
            medicationId: id, displayName: "Med \(id)", strength: nil, instructions: nil,
            schedules: [NativeDoseSchedule(
                profileId: profile, scheduleId: "s-\(id)", medicationId: id, recurrence: "DAILY", intervalDays: 1,
                daysOfWeek: [], startDate: "2026-11-01", endDate: nil, timesLocal: times
            )],
            active: true
        )
    }

    private var now: Date { calendar.date(from: DateComponents(year: 2026, month: 11, day: 1, hour: 6))! }

    func testPlansEveryProfileNearestDosesFirst() {
        let planner = NotificationPlanner(calendar: calendar)
        let meds = [medication("a", profile: "p1", times: ["08:00"]), medication("b", profile: "p2", times: ["07:00", "09:00"])]
        let reminders = planner.reminders(for: meds, after: now, horizonDays: 1, limit: 10)
        XCTAssertEqual(Set(reminders.map(\.profileId)), ["p1", "p2"])
        XCTAssertEqual(reminders.prefix(3).map(\.medicationId), ["b", "a", "b"])
        XCTAssertEqual(reminders.map(\.scheduledAt), reminders.map(\.scheduledAt).sorted())
    }

    func testSharedBudgetKeepsTheNearestDosesOfBothProfiles() {
        let planner = NotificationPlanner(calendar: calendar)
        let meds = [medication("a", profile: "p1", times: ["08:00"]), medication("b", profile: "p2", times: ["08:30"])]
        let reminders = planner.reminders(for: meds, after: now, horizonDays: 10, limit: 6)
        XCTAssertEqual(reminders.count, 6)
        XCTAssertEqual(Dictionary(grouping: reminders, by: \.profileId).mapValues(\.count), ["p1": 3, "p2": 3])
    }

    func testCoverageEndsAtLastScheduledDoseWhenBudgetIsFull() {
        let planner = NotificationPlanner(calendar: calendar)
        let meds = [medication("a", profile: "p1", times: ["08:00", "12:00", "16:00", "20:00"])]
        let full = planner.reminders(for: meds, after: now, limit: 8)
        XCTAssertEqual(planner.coverageEnd(of: full, after: now, limit: 8), full.last?.scheduledAt)
        let few = planner.reminders(for: meds, after: now, horizonDays: 1, limit: 60)
        XCTAssertEqual(planner.coverageEnd(of: few, after: now, horizonDays: 1, limit: 60), calendar.date(byAdding: .day, value: 1, to: now))
        XCTAssertNil(planner.coverageEnd(of: [], after: now))
    }

    func testPrivacyModeAndSnoozeChangeTheRequestIdentifier() {
        let reminder = DoseReminder(profileId: "p", medicationId: "m", medicationName: "Aspirin", scheduleId: "s", scheduledAt: now)
        XCTAssertNotEqual(reminder.identifier(hidingName: true), reminder.identifier(hidingName: false))
        let snoozed = NotificationPlanner(calendar: calendar).snooze(reminder, from: now)
        XCTAssertTrue(snoozed.identifier.hasSuffix(DoseReminder.snoozeSuffix))
        XCTAssertFalse(reminder.identifier.hasSuffix(DoseReminder.snoozeSuffix))
    }

    func testHiddenNamesStayOutOfLockScreenContent() {
        let reminder = DoseReminder(profileId: "p", medicationId: "m", medicationName: "Aspirin", scheduleId: "s", scheduledAt: now)
        let shown = MedicationNotificationCoordinator.content(for: reminder, hidingName: false)
        XCTAssertEqual(shown.title, "Aspirin")
        let hidden = MedicationNotificationCoordinator.content(for: reminder, hidingName: true)
        XCTAssertFalse(hidden.title.contains("Aspirin"))
        XCTAssertFalse(hidden.body.contains("Aspirin"))
        XCTAssertEqual(hidden.userInfo["medicationName"] as? String, "Aspirin")
    }

    func testPendingResponsesSurviveARestartAndAreNotDuplicated() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("responses.json")
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let response = PendingDoseResponse(
            notificationIdentifier: "vezuna.dose.s.1", action: MedicationNotificationCoordinator.takenAction,
            profileId: "p", medicationId: "m", medicationName: "Aspirin", scheduleId: "s", scheduledEpochSeconds: 1, receivedAt: Date(timeIntervalSince1970: 100)
        )
        let first = PendingDoseResponseStore(url: url)
        try first.append(response)
        try first.append(response)
        let reopened = PendingDoseResponseStore(url: url)
        XCTAssertEqual(try reopened.load(), [response])
        try reopened.remove(response)
        XCTAssertEqual(try PendingDoseResponseStore(url: url).load(), [])
    }

    func testCorruptResponseFileIsSetAsideAndStoreContinues() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent("responses.json")
        try Data("not json".utf8).write(to: url)
        let store = PendingDoseResponseStore(url: url)
        XCTAssertEqual(try store.load(), [])
        let kept = try FileManager.default.contentsOfDirectory(atPath: directory.path).filter { $0.hasPrefix("responses.corrupt-") }
        XCTAssertEqual(kept.count, 1)
        let response = PendingDoseResponse(
            notificationIdentifier: "n", action: MedicationNotificationCoordinator.skipAction, profileId: "p", medicationId: "m",
            medicationName: "Aspirin", scheduleId: "s", scheduledEpochSeconds: 1, receivedAt: Date(timeIntervalSince1970: 100)
        )
        try store.append(response)
        XCTAssertEqual(try store.load(), [response])
    }

    func testResponsesExpireAfterSevenDays() {
        let received = Date(timeIntervalSince1970: 0)
        let response = PendingDoseResponse(
            notificationIdentifier: "n", action: "a", profileId: "p", medicationId: "m",
            medicationName: "x", scheduleId: "s", scheduledEpochSeconds: 1, receivedAt: received
        )
        XCTAssertFalse(response.isExpired(now: received.addingTimeInterval(6 * 24 * 3600)))
        XCTAssertTrue(response.isExpired(now: received.addingTimeInterval(8 * 24 * 3600)))
    }
}
