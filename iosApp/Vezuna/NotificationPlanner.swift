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
}

struct DoseReminder: Equatable {
    let profileId: String
    let medicationId: String
    let medicationName: String
    let scheduleId: String
    let scheduledAt: Date
    /// A snoozed copy of a delivered reminder. Planning never replaces or removes it; only an inactive medication does.
    var isSnooze = false

    /// The name is part of the identifier so renaming a medication replaces its already scheduled requests.
    var identifier: String { identifier(hidingName: false) }

    /// The lock-screen privacy mode is part of the identifier so toggling it replaces already scheduled requests.
    func identifier(hidingName: Bool) -> String {
        let base = "vezuna.dose.\(scheduleId).\(Int(scheduledAt.timeIntervalSince1970)).\(String(UInt32(truncatingIfNeeded: stableHash(medicationName)), radix: 16))"
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
                                scheduledAt: date
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
        return Array(ordered.prefix(limit))
    }

    /// Latest moment up to which every dose is scheduled. A full budget ends at its last reminder; otherwise the horizon.
    func coverageEnd(of reminders: [DoseReminder], after now: Date, horizonDays: Int = 60, limit: Int = 60) -> Date? {
        guard let last = reminders.last else { return nil }
        if reminders.count >= limit { return last.scheduledAt }
        return calendar.date(byAdding: .day, value: horizonDays, to: now)
    }

    func snooze(_ reminder: DoseReminder, from now: Date, delay: TimeInterval = 10 * 60) -> DoseReminder {
        DoseReminder(
            profileId: reminder.profileId,
            medicationId: reminder.medicationId,
            medicationName: reminder.medicationName,
            scheduleId: reminder.scheduleId,
            scheduledAt: now.addingTimeInterval(delay),
            isSnooze: true
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
