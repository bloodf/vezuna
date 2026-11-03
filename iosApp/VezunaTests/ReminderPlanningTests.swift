import UserNotifications
import XCTest
@testable import Vezuna

final class ReminderPlanningTests: XCTestCase {
    private var calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar
    }()

    private func medication(_ id: String, profile: String, times: [String], startDate: String = "2026-11-01", endDate: String? = nil) -> NativeMedication {
        NativeMedication(
            medicationId: id, displayName: "Med \(id)", strength: nil, instructions: nil,
            schedules: [NativeDoseSchedule(
                profileId: profile, scheduleId: "s-\(id)", medicationId: id, recurrence: "DAILY", intervalDays: 1,
                daysOfWeek: [], startDate: startDate, endDate: endDate, timesLocal: times
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

    func testDosesStayAtTheLocalWallClockTimeOutsideUtc() throws {
        var local = Calendar(identifier: .gregorian)
        local.timeZone = try XCTUnwrap(TimeZone(identifier: "America/Sao_Paulo"))
        let start = try XCTUnwrap(local.date(from: DateComponents(year: 2026, month: 11, day: 1, hour: 6)))
        let reminders = NotificationPlanner(calendar: local).reminders(for: [medication("a", profile: "p1", times: ["08:00"])], after: start, horizonDays: 2, limit: 10)
        XCTAssertEqual(reminders.count, 2)
        for reminder in reminders {
            let parts = local.dateComponents([.hour, .minute], from: reminder.scheduledAt)
            XCTAssertEqual(parts.hour, 8)
            XCTAssertEqual(parts.minute, 0)
        }
    }

    func testDosesKeepTheirLocalTimeAcrossADaylightSavingChange() throws {
        var local = Calendar(identifier: .gregorian)
        local.timeZone = try XCTUnwrap(TimeZone(identifier: "America/New_York"))
        // US clocks jump forward on 2026-03-08, so the second dose is 23 hours after the first, not 24.
        let start = try XCTUnwrap(local.date(from: DateComponents(year: 2026, month: 3, day: 7, hour: 6)))
        let meds = [medication("a", profile: "p1", times: ["08:00"], startDate: "2026-03-01")]
        let reminders = NotificationPlanner(calendar: local).reminders(for: meds, after: start, horizonDays: 3, limit: 10)
        XCTAssertGreaterThanOrEqual(reminders.count, 2)
        XCTAssertEqual(reminders.prefix(3).map { local.component(.hour, from: $0.scheduledAt) }, [8, 8, 8])
        XCTAssertEqual(reminders[1].scheduledAt.timeIntervalSince(reminders[0].scheduledAt), 23 * 3600)
    }

    func testCoverageEndsAtLastScheduledDoseWhenBudgetIsFull() {
        let planner = NotificationPlanner(calendar: calendar)
        let meds = [medication("a", profile: "p1", times: ["08:00", "12:00", "16:00", "20:00"])]
        let full = planner.plan(for: meds, after: now, limit: 8)
        XCTAssertTrue(full.truncated)
        XCTAssertEqual(planner.coverageEnd(of: full.reminders, after: now, truncated: full.truncated), full.reminders.last?.scheduledAt)
        let few = planner.plan(for: meds, after: now, horizonDays: 1, limit: 60)
        XCTAssertFalse(few.truncated)
        XCTAssertEqual(planner.coverageEnd(of: few.reminders, after: now, horizonDays: 1, truncated: few.truncated), calendar.date(byAdding: .day, value: 1, to: now))
        XCTAssertNil(planner.coverageEnd(of: [], after: now))
    }

    func testCoverageReachesTheHorizonWhenTheScheduleEndsBeforeIt() {
        let planner = NotificationPlanner(calendar: calendar)
        let meds = [medication("a", profile: "p1", times: ["08:00"], endDate: "2026-11-06")]
        let plan = planner.plan(for: meds, after: now, horizonDays: 14, limit: 60)
        XCTAssertEqual(plan.reminders.count, 6)
        XCTAssertFalse(plan.truncated)
        XCTAssertEqual(planner.coverageEnd(of: plan.reminders, after: now, horizonDays: 14, truncated: plan.truncated), calendar.date(byAdding: .day, value: 14, to: now))
    }

    func testExactlyFullBudgetOnAScheduleThatEndsIsNotTruncated() {
        let planner = NotificationPlanner(calendar: calendar)
        // 6 doses a day for 10 days is exactly the 60-notification budget.
        let meds = [medication("a", profile: "p1", times: ["07:00", "09:00", "11:00", "13:00", "15:00", "17:00"], endDate: "2026-11-10")]
        let plan = planner.plan(for: meds, after: now, horizonDays: 14, limit: 60)
        XCTAssertEqual(plan.reminders.count, 60)
        XCTAssertFalse(plan.truncated)
        XCTAssertEqual(planner.coverageEnd(of: plan.reminders, after: now, horizonDays: 14, truncated: plan.truncated), calendar.date(byAdding: .day, value: 14, to: now))
    }

    func testOneDoseOverTheBudgetIsTruncated() {
        let planner = NotificationPlanner(calendar: calendar)
        let meds = [medication("a", profile: "p1", times: ["07:00", "09:00", "11:00", "13:00", "15:00", "17:00"], endDate: "2026-11-10")]
        let plan = planner.plan(for: meds, after: now, horizonDays: 14, limit: 59)
        XCTAssertTrue(plan.truncated)
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

    func testProfileNameIsShownOnlyWhenNamesAreNotHidden() {
        let reminder = DoseReminder(profileId: "p", medicationId: "m", medicationName: "Aspirin", scheduleId: "s", scheduledAt: now, profileName: "Ana")
        XCTAssertTrue(MedicationNotificationCoordinator.content(for: reminder, hidingName: false).body.contains("Ana"))
        let hidden = MedicationNotificationCoordinator.content(for: reminder, hidingName: true)
        XCTAssertFalse(hidden.title.contains("Ana"))
        XCTAssertFalse(hidden.body.contains("Ana"))
        let snoozed = NotificationPlanner(calendar: calendar).snooze(reminder, from: now)
        XCTAssertEqual(snoozed.profileName, "Ana")
        XCTAssertEqual(snoozed.scheduledAt.timeIntervalSince1970, snoozed.scheduledAt.timeIntervalSince1970.rounded(.down))
    }

    func testASnoozeCarriesTheOriginalTimeThroughThePayloadAndASecondSnoozeDoesNotDrift() throws {
        let planner = NotificationPlanner(calendar: calendar)
        let original = Date(timeIntervalSince1970: 1_800_000_000)
        let first = planner.snooze(DoseReminder(profileId: "p", medicationId: "m", medicationName: "Aspirin", scheduleId: "s", scheduledAt: original), from: original)
        XCTAssertNotEqual(Int(first.scheduledAt.timeIntervalSince1970) % 60, 0)
        XCTAssertEqual(first.originalScheduledAt, original)
        let shown = try XCTUnwrap(MedicationNotificationCoordinator.reminder(from: MedicationNotificationCoordinator.content(for: first, hidingName: true).userInfo))
        XCTAssertEqual(shown.doseTime, original)
        let second = planner.snooze(shown, from: first.scheduledAt)
        XCTAssertEqual(second.doseTime, original)
        XCTAssertNotEqual(second.scheduledAt, first.scheduledAt)
    }

    func testAPayloadFromBeforeTheUpdateRecordsAtItsShownTimeForSharedCodeToResolve() throws {
        let at = Date(timeIntervalSince1970: 1_800_000_601)
        let old: [AnyHashable: Any] = ["profileId": "p", "medicationId": "m", "medicationName": "Aspirin", "scheduleId": "s", "scheduledEpochSeconds": Int(at.timeIntervalSince1970)]
        let reminder = try XCTUnwrap(MedicationNotificationCoordinator.reminder(from: old))
        XCTAssertNil(reminder.originalScheduledAt)
        XCTAssertEqual(reminder.doseTime, at)
        let saved = #"{"notificationIdentifier":"n","action":"a","profileId":"p","medicationId":"m","medicationName":"x","scheduleId":"s","scheduledEpochSeconds":1,"receivedAt":0}"#
        XCTAssertNil(try JSONDecoder().decode(PendingDoseResponse.self, from: Data(saved.utf8)).originalEpochSeconds)
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

    func testAppointmentNoticeSchedulesOneLocalRequestAndHidesTheClinicianWhenAsked() throws {
        let json = #"[{"profileId":"p","appointmentId":"a1","clinician":"Dr. Silva","appointmentEpochSeconds":1793610000,"fireEpochSeconds":1793606400,"profileName":null}]"#
        let notices = try JSONDecoder().decode([AppointmentNotice].self, from: Data(json.utf8))
        XCTAssertEqual(notices.map(\.identifier), ["vezuna.appointment.a1"])
        let named = MedicationNotificationCoordinator.appointmentRequest(for: notices[0], hidingName: false, calendar: calendar)
        XCTAssertTrue(named.content.body.contains("Dr. Silva"))
        XCTAssertTrue(named.trigger is UNCalendarNotificationTrigger)
        XCTAssertFalse((named.trigger as? UNCalendarNotificationTrigger)?.repeats ?? true)
        let hidden = MedicationNotificationCoordinator.appointmentRequest(for: notices[0], hidingName: true, calendar: calendar)
        XCTAssertFalse(hidden.content.body.contains("Dr. Silva"))
    }

    func testLowSupplyNoticeDecodesAndSchedulesOneRequestPerMedication() throws {
        let json = #"[{"key":"m|1|3","profileId":"p","medicationId":"m","medicationName":"Vitamin D","remaining":3,"lowNow":true,"fireEpochSeconds":1793610000,"profileName":null},{"key":"n|1|3","profileId":"p","medicationId":"n","medicationName":"X","remaining":2,"lowNow":false,"fireEpochSeconds":null,"profileName":null}]"#
        let notices = try JSONDecoder().decode([LowSupplyNotice].self, from: Data(json.utf8))
        XCTAssertEqual(notices.map(\.identifier), ["vezuna.supply.m", "vezuna.supply.n"])
        XCTAssertNil(notices[1].fireEpochSeconds)
        let at = Date(timeIntervalSince1970: 1_793_610_000)
        let named = MedicationNotificationCoordinator.supplyRequest(for: notices[0], at: at, hidingName: false, calendar: calendar)
        XCTAssertTrue(named.content.body.contains("Vitamin D"))
        let hidden = MedicationNotificationCoordinator.supplyRequest(for: notices[0], at: at, hidingName: true, calendar: calendar)
        XCTAssertFalse(hidden.content.body.contains("Vitamin D"))
    }
}
