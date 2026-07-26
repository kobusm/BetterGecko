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
