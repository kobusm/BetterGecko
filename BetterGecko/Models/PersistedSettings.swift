import Foundation

/// Locally-cached device settings, keyed by GSN. The REST API has no endpoint that
/// returns current setpoints/mode, so these are the last-known values (refreshed from
/// the socket.io snapshot when Settings opens, and written after each successful save).
struct PersistedSettings: Codable {
    var targetTemp: Double
    var pvTargetTemp: Double = 60
    var mode: String = "AC_AND_PV"

    static func load(gsn: String) -> PersistedSettings? {
        guard let data = UserDefaults.standard.data(forKey: "device_settings_\(gsn)"),
              let settings = try? JSONDecoder().decode(PersistedSettings.self, from: data)
        else { return nil }
        return settings
    }

    func save(gsn: String) {
        if let data = try? JSONEncoder().encode(self) {
            UserDefaults.standard.set(data, forKey: "device_settings_\(gsn)")
        }
    }

    /// Updates just the cached mode, preserving any cached setpoints.
    static func updateMode(_ mode: String, gsn: String) {
        var s = load(gsn: gsn) ?? PersistedSettings(targetTemp: 50)
        s.mode = mode
        s.save(gsn: gsn)
    }
}

/// Electricity cost settings. The rate is global; element wattage is per device.
enum CostSettings {
    static let rateKey = "electricity_rate_zar_per_kwh"
    static func elementKey(_ gsn: String) -> String { "element_kw_\(gsn)" }
    static let defaultElementKW = 4.0
    static let defaultRate = 4.0

    static var rate: Double { UserDefaults.standard.object(forKey: rateKey) as? Double ?? defaultRate }

    static func elementKW(gsn: String) -> Double {
        UserDefaults.standard.object(forKey: elementKey(gsn)) as? Double ?? defaultElementKW
    }
}

/// Locally-accumulated AC (element) on-time per SAST calendar day, keyed by GSN.
/// The performance-history endpoint only covers the last few days, so monthly totals
/// are built up by merging each fetched window into this ledger.
struct ACUsageLedger: Codable {
    /// "yyyy-MM-dd" (SAST) → seconds the element was on that day.
    var days: [String: Double] = [:]

    private static func key(_ gsn: String) -> String { "ac_usage_ledger_\(gsn)" }

    private static let calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Africa/Johannesburg") ?? .current
        return c
    }()

    private static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.calendar = calendar
        f.timeZone = calendar.timeZone
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    static func load(gsn: String) -> ACUsageLedger {
        guard let data = UserDefaults.standard.data(forKey: key(gsn)),
              let ledger = try? JSONDecoder().decode(ACUsageLedger.self, from: data)
        else { return ACUsageLedger() }
        return ledger
    }

    func save(gsn: String) {
        if let data = try? JSONEncoder().encode(self) {
            UserDefaults.standard.set(data, forKey: Self.key(gsn))
        }
    }

    /// Merges AC on-time from a history window (oldest-first). The window's oldest day
    /// may be only partially covered, so each day keeps the larger of old and new totals.
    mutating func merge(_ history: [HistoryPoint]) {
        guard history.count > 1 else { return }
        var fresh: [String: Double] = [:]
        for i in 0 ..< history.count - 1 where GeckoState(rawString: history[i].state) == .elementHeating {
            // Cap gaps so missing data isn't billed as an hour-long element run.
            let dt = min(history[i + 1].timestamp.timeIntervalSince(history[i].timestamp), 3600)
            guard dt > 0 else { continue }
            fresh[Self.dayFormatter.string(from: history[i].timestamp), default: 0] += dt
        }
        for (day, secs) in fresh { days[day] = max(days[day] ?? 0, secs) }
    }

    /// AC on-hours for the calendar month containing `date`.
    func hours(inMonthOf date: Date) -> Double {
        let prefix = String(Self.dayFormatter.string(from: date).prefix(7))   // "yyyy-MM"
        return days.filter { $0.key.hasPrefix(prefix) }.values.reduce(0, +) / 3600
    }

    static func previousMonth(from date: Date) -> Date {
        calendar.date(byAdding: .month, value: -1, to: date) ?? date
    }
}
