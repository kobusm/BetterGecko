import Foundation
import UserNotifications

/// A local rule: at `hour:minute` every day, switch the device to `mode`.
///
/// There is no server-side scheduling API, so schedules live entirely on-device.
/// iOS cannot run code in the background at an exact time, so enforcement is best
/// effort: a daily local notification fires at the set time (tap it to apply), and
/// the app also "catches up" to whichever rule should be active whenever it opens.
struct ModeSchedule: Identifiable, Codable, Equatable {
    var id = UUID()
    var hour: Int
    var minute: Int
    var mode: String            // geckoMode: ALL_OFF | PV_ONLY | AC_AND_PV | AC_ONLY
    var enabled: Bool = true

    var timeLabel: String { String(format: "%02d:%02d", hour, minute) }
    var modeLabel: String { ModeSchedule.label(for: mode) }

    static let allModes = ["ALL_OFF", "PV_ONLY", "AC_AND_PV", "AC_ONLY"]

    static func label(for mode: String) -> String {
        switch mode {
        case "ALL_OFF":   return "Off"
        case "PV_ONLY":   return "Solar Only"
        case "AC_AND_PV": return "Solar + AC"
        case "AC_ONLY":   return "AC Only"
        default:          return mode
        }
    }
}

enum ScheduleStore {
    private static func key(_ gsn: String) -> String { "mode_schedules_\(gsn)" }
    private static func appliedKey(_ gsn: String) -> String { "schedule_applied_\(gsn)" }

    static func load(gsn: String) -> [ModeSchedule] {
        guard let data = UserDefaults.standard.data(forKey: key(gsn)),
              let list = try? JSONDecoder().decode([ModeSchedule].self, from: data) else { return [] }
        return list.sorted { ($0.hour, $0.minute) < ($1.hour, $1.minute) }
    }

    static func save(_ schedules: [ModeSchedule], gsn: String, deviceName: String) {
        let sorted = schedules.sorted { ($0.hour, $0.minute) < ($1.hour, $1.minute) }
        if let data = try? JSONEncoder().encode(sorted) {
            UserDefaults.standard.set(data, forKey: key(gsn))
        }
        rescheduleNotifications(sorted, gsn: gsn, deviceName: deviceName)
    }

    // MARK: - Catch-up

    /// The most recent past occurrence of any enabled schedule, relative to `now`.
    /// Returns the mode to apply and the exact date of that occurrence, or nil.
    static func activeOccurrence(for schedules: [ModeSchedule], now: Date = Date(),
                                 calendar: Calendar = .current) -> (mode: String, date: Date)? {
        var best: (mode: String, date: Date)?
        for s in schedules where s.enabled {
            var comps = calendar.dateComponents([.year, .month, .day], from: now)
            comps.hour = s.hour; comps.minute = s.minute; comps.second = 0
            guard var occ = calendar.date(from: comps) else { continue }
            if occ > now { occ = calendar.date(byAdding: .day, value: -1, to: occ) ?? occ } // fired yesterday
            if best == nil || occ > best!.date { best = (s.mode, occ) }
        }
        return best
    }

    /// Returns the mode that should be applied now if it hasn't been applied for this
    /// occurrence yet, and records it. Returns nil when nothing needs applying.
    static func modeToApplyOnOpen(gsn: String, now: Date = Date()) -> String? {
        let schedules = load(gsn: gsn)
        guard let occ = activeOccurrence(for: schedules, now: now) else { return nil }
        let stamp = ISO8601DateFormatter().string(from: occ.date)
        if UserDefaults.standard.string(forKey: appliedKey(gsn)) == stamp { return nil }
        UserDefaults.standard.set(stamp, forKey: appliedKey(gsn))
        return occ.mode
    }

    // MARK: - Notifications

    static func rescheduleNotifications(_ schedules: [ModeSchedule], gsn: String, deviceName: String) {
        let center = UNUserNotificationCenter.current()
        let prefix = "sched_\(gsn)_"
        center.getPendingNotificationRequests { pending in
            let stale = pending.map(\.identifier).filter { $0.hasPrefix(prefix) }
            center.removePendingNotificationRequests(withIdentifiers: stale)

            for s in schedules where s.enabled {
                var comps = DateComponents()
                comps.hour = s.hour; comps.minute = s.minute
                let trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: true)

                let content = UNMutableNotificationContent()
                content.title = deviceName
                content.body = "Tap to switch to \(ModeSchedule.label(for: s.mode))"
                content.sound = .default
                content.userInfo = ["gsn": gsn, "mode": s.mode]
                content.categoryIdentifier = NotificationManager.modeCategory

                let req = UNNotificationRequest(identifier: "\(prefix)\(s.id.uuidString)",
                                                content: content, trigger: trigger)
                center.add(req)
            }
        }
    }
}
