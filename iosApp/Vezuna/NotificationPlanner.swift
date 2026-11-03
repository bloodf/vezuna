import Foundation

struct NativeDoseSchedule: Codable {
    let profileId: String
    let scheduleId: String
    let medicationId: String
    let recurrence: String
    let intervalDays: Int
    let daysOfWeek: Set<String>
    let startDate: String
    let endDate: String?
    let timesLocal: [String]
}

struct NativeMedication: Codable {
    let medicationId: String
    let displayName: String
    let strength: String?
    let instructions: String?
    let schedules: [NativeDoseSchedule]
    let active: Bool
    /// Present only when the household has several profiles, so reminders can say who the dose is for.
    var profileName: String? = nil
}

struct DoseReminder: Equatable {
    let profileId: String
    let medicationId: String
    let medicationName: String
    let scheduleId: String
    var scheduledAt: Date
    /// A snoozed copy of a delivered reminder. Planning never replaces or removes it; only an inactive medication does.
    var isSnooze = false
    var profileName: String? = nil
    /// The time this dose was first due, when `scheduledAt` is a snoozed time. Nil on notifications from before this field.
    var originalScheduledAt: Date? = nil

    /// The time to record this dose at: one real dose is recorded once, at its original time.
    var doseTime: Date { originalScheduledAt ?? scheduledAt }

    /// The name is part of the identifier so renaming a medication replaces its already scheduled requests.
    var identifier: String { identifier(hidingName: false) }

    /// The lock-screen privacy mode is part of the identifier so toggling it replaces already scheduled requests.
    func identifier(hidingName: Bool) -> String {
        let base = "vezuna.dose.\(scheduleId).\(Int(scheduledAt.timeIntervalSince1970)).\(String(UInt32(truncatingIfNeeded: stableHash(medicationName + "|" + (profileName ?? ""))), radix: 16))"
        return base + (hidingName ? ".private" : "") + (isSnooze ? Self.snoozeSuffix : "")
    }

    static let snoozeSuffix = ".snooze"

    private func stableHash(_ text: String) -> UInt64 {
        text.utf8.reduce(UInt64(14695981039346656037)) { ($0 ^ UInt64($1)) &* 1099511628211 }
    }
}

struct NotificationPlanner {
    let calendar: Calendar

    func reminders(for medications: [NativeMedication], after now: Date, horizonDays: Int = 60, limit: Int = 60) -> [DoseReminder] {
        plan(for: medications, after: now, horizonDays: horizonDays, limit: limit).reminders
    }

    /// The nearest `limit` doses, plus whether more doses existed inside the horizon than the budget could hold.
    func plan(for medications: [NativeMedication], after now: Date, horizonDays: Int = 60, limit: Int = 60) -> (reminders: [DoseReminder], truncated: Bool) {
        let horizon = calendar.date(byAdding: .day, value: horizonDays, to: now) ?? now
        var reminders: [DoseReminder] = []
        for medication in medications where medication.active {
            for schedule in medication.schedules where !schedule.profileId.isEmpty {
                guard let start = localDate(schedule.startDate) else { continue }
                let end = schedule.endDate.flatMap(localDate) ?? horizon
                for time in schedule.timesLocal {
                    guard let components = localTime(time) else { continue }
                    var day = max(start, calendar.startOfDay(for: now))
                    while day <= min(end, horizon) {
                        var dateComponents = calendar.dateComponents([.year, .month, .day], from: day)
                        dateComponents.hour = components.hour
                        dateComponents.minute = components.minute
                        if let date = calendar.date(from: dateComponents), date > now && date <= horizon && matches(schedule: schedule, day: day, start: start) {
                            reminders.append(DoseReminder(
                                profileId: schedule.profileId,
                                medicationId: schedule.medicationId,
                                medicationName: medication.displayName,
                                scheduleId: schedule.scheduleId,
                                scheduledAt: date,
                                profileName: medication.profileName
                            ))
                        }
                        guard let next = calendar.date(byAdding: .day, value: 1, to: day) else { break }
                        day = next
                    }
                }
            }
        }
        // Nearest doses first across every profile, so the shared budget never starves a profile with earlier doses.
        let ordered = reminders.sorted {
            ($0.scheduledAt, $0.profileId, $0.scheduleId) < ($1.scheduledAt, $1.profileId, $1.scheduleId)
        }
        return (Array(ordered.prefix(limit)), ordered.count > limit)
    }

    /// Latest moment up to which every dose is scheduled. Doses cut by the budget end coverage at the last reminder;
    /// a budget that exactly fits every dose (a course that ends) reaches the horizon.
    func coverageEnd(of reminders: [DoseReminder], after now: Date, horizonDays: Int = 60, truncated: Bool = false) -> Date? {
        guard let last = reminders.last else { return nil }
        if truncated { return last.scheduledAt }
        return calendar.date(byAdding: .day, value: horizonDays, to: now)
    }

    func snooze(_ reminder: DoseReminder, from now: Date, delay: TimeInterval = 10 * 60) -> DoseReminder {
        DoseReminder(
            profileId: reminder.profileId,
            medicationId: reminder.medicationId,
            medicationName: reminder.medicationName,
            scheduleId: reminder.scheduleId,
            scheduledAt: now.addingTimeInterval(delay).roundedDownToSecond.offTheMinute,
            isSnooze: true,
            profileName: reminder.profileName,
            originalScheduledAt: reminder.doseTime
        )
    }

    private func matches(schedule: NativeDoseSchedule, day: Date, start: Date) -> Bool {
        switch schedule.recurrence {
        case "DAILY":
            return true
        case "WEEKLY":
            let names = ["SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"]
            let weekday = calendar.component(.weekday, from: day)
            return schedule.daysOfWeek.contains(names[weekday - 1])
        case "EVERY_N_DAYS":
            let days = calendar.dateComponents([.day], from: start, to: day).day ?? 0
            return days % max(schedule.intervalDays, 1) == 0
        default:
            return false
        }
    }

    private func localDate(_ value: String) -> Date? {
        let parts = value.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
    }

    private func localTime(_ value: String) -> (hour: Int, minute: Int)? {
        let parts = value.split(separator: ":").compactMap { Int($0) }
        guard parts.count == 2, (0...23).contains(parts[0]), (0...59).contains(parts[1]) else { return nil }
        return (parts[0], parts[1])
    }
}

extension Date {
    /// Dose events store whole seconds, so a snooze time must not carry a fraction the stored event cannot.
    var roundedDownToSecond: Date { Date(timeIntervalSince1970: timeIntervalSince1970.rounded(.down)) }

    /// Schedule times are whole minutes, so shared code can prove a time off the minute is a snooze (DoseEvents.snoozeUntil).
    var offTheMinute: Date { Int(timeIntervalSince1970) % 60 == 0 ? addingTimeInterval(1) : self }
}

/// A low-supply reminder planned by shared code. `fireEpochSeconds` is nil once the notice was already delivered.
struct LowSupplyNotice: Codable, Equatable {
    let key: String
    let profileId: String
    let medicationId: String
    let medicationName: String
    let remaining: Int
    /// False for a projected notice: its count may be stale by delivery time, so the wording carries none.
    let lowNow: Bool
    let fireEpochSeconds: Int64?
    let profileName: String?

    /// One request per medication: scheduling again replaces the earlier one.
    var identifier: String { "vezuna.supply.\(medicationId)" }
}
