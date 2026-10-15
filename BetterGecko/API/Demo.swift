import Foundation

/// Demo mode: a simulated GeyserGecko so the app can be explored without an account or
/// device (App Review, testers). When `Demo.isEnabled` is on, `APIClient.request` and
/// `GeckoRealtime.fetchSettings` answer from here and nothing goes to the network.
/// Mirrors the Android `Demo` object so both apps show the same simulated geyser.
enum Demo {
    static let gsn = "DEMO00000001"
    static let deviceID = "demo"
    static let device = GeckoDevice(geckoSerialNumber: gsn, friendlyName: "Demo Geyser")
    static let session = SessionInfo(email: "Demo mode (sample data)", token: "demo", role: "demo", deviceID: deviceID)

    private static let enabledKey = "demo_mode"
    private static let stepSecs = 300            // 5-minute samples, like the real device
    private static let spanSecs = 3 * 24 * 3600  // the history endpoint covers ~3 days

    static var isEnabled: Bool {
        get { UserDefaults.standard.bool(forKey: enabledKey) }
        set { UserDefaults.standard.set(newValue, forKey: enabledKey) }
    }

    // MARK: - Canned responses

    /// JSON body for a REST path; setTemperature, setMode and logout are simply accepted.
    static func response(for path: String) -> Data {
        let object: Any
        switch path {
        case "/app/getPerformanceHistory": object = history(now: Date(), settings: settings()).json
        case "/app/hasControl": object = ["hasControl": true]
        case "/app/deviceid": object = ["deviceID": deviceID, "role": "demo"]
        case "/uv/uvindex": object = ["uvIndex": 6.0]
        default: object = [String: Any]()
        }
        return (try? JSONSerialization.data(withJSONObject: object)) ?? Data("{}".utf8)
    }

    /// What the socket.io snapshot would report; setpoints and mode follow what the user saved.
    static func live() -> GeckoRealtime.Live {
        let s = settings()
        var live = GeckoRealtime.Live()
        live.acMax = Int(s.targetTemp.rounded())
        live.pvMax = Int(s.pvTargetTemp.rounded())
        live.geckoMode = s.mode
        live.temperature = history(now: Date(), settings: s).temperatures.first
        live.fwVersion = "1.0.46 (demo)"
        live.mcuFwVersion = 12
        live.connectedAP = "Demo-WiFi"
        live.rssi = -58
        return live
    }

    private static func settings() -> PersistedSettings {
        PersistedSettings.load(gsn: gsn) ?? PersistedSettings(targetTemp: 45, pvTargetTemp: 65)
    }

    // MARK: - Simulation

    /// Newest-first arrays, shaped like the server's getPerformanceHistory response.
    struct History {
        var timestamps: [String] = []
        var temperatures: [Double] = []
        var states: [String] = []
        var pvVoltage: [Int] = []
        var mpptVoltage: [Int] = []
        var pvOutPower: [Int] = []
        var acOutPower: [Int] = []
        var pvUsageWeek = 0
        var acUsageWeek = 0
        var savingsWeek = 0.0

        var json: [String: Any] {
            ["timestamps": timestamps, "temperatures": temperatures, "state": states,
             "pvVoltage": pvVoltage, "mpptVoltage": mpptVoltage,
             "pvOutPower": pvOutPower, "acOutPower": acOutPower,
             "pvUsageWeek": pvUsageWeek, "acUsageWeek": acUsageWeek, "savingsWeek": savingsWeek]
        }
    }

    private static let sast: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "Africa/Johannesburg")!
        return c
    }()

    private static let iso: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]   // what toHistoryPoints parses
        return f
    }()

    /// Simulates the last three days: the panels heat the water through the day up to the PV
    /// setpoint, the element tops it up to the AC setpoint when it cools below, and morning and
    /// evening showers draw it down. Deterministic for a given `now`.
    static func history(now: Date, settings s: PersistedSettings) -> History {
        let nowSecs = Int(now.timeIntervalSince1970)
        let end = nowSecs - nowSecs % stepSecs
        let solarAllowed = s.mode == "PV_ONLY" || s.mode == "AC_AND_PV"
        let acAllowed = s.mode == "AC_ONLY" || s.mode == "AC_AND_PV"

        var h = History()
        var temp = 48.0
        var heatingAC = false
        for t in stride(from: end - spanSecs, through: end, by: stepSecs) {
            let date = Date(timeIntervalSince1970: TimeInterval(t))
            let parts = sast.dateComponents([.hour, .minute], from: date)
            let hour = Double(parts.hour ?? 0) + Double(parts.minute ?? 0) / 60
            let day = sast.ordinality(of: .day, in: .era, for: date) ?? 0
            let cloud = 0.65 + 0.35 * dayFactor(day)
            let sun = max(0, sin(.pi * (hour - 6.5) / 12.5)) * cloud   // 06:30–19:00

            // Hot-water draws: a morning and an evening shower.
            if (6.0...6.5).contains(hour) { temp -= 1.6 }
            if (19.0...19.5).contains(hour) { temp -= 1.3 }
            temp -= 0.035                                               // standing loss

            let state: String
            if solarAllowed, sun > 0.2, temp < s.pvTargetTemp {
                temp += 0.45 * sun; state = "pv_out"
            } else if acAllowed, heatingAC || temp < s.targetTemp - 3, temp < s.targetTemp {
                heatingAC = true; temp += 0.55; state = "ac_out"
            } else if !solarAllowed && !acAllowed {
                heatingAC = false; state = "off"
            } else {
                heatingAC = false; state = "idle"
            }
            temp = min(max(temp, 18), 75)

            h.timestamps.append(iso.string(from: date))
            h.temperatures.append((temp * 10).rounded() / 10)
            h.states.append(state)
            h.pvVoltage.append(sun > 0 ? Int(((110 + 95 * sun) * 3).rounded()) : 0)   // raw, server reports ×3
            h.mpptVoltage.append(sun > 0 ? Int((205 + 35 * sun).rounded()) : 0)
            h.pvOutPower.append(state == "pv_out" ? Int((1900 * sun).rounded()) : 0)
            h.acOutPower.append(state == "ac_out" ? 3900 : 0)
        }

        let stepMin = stepSecs / 60
        h.pvUsageWeek = h.states.filter { $0 == "pv_out" }.count * stepMin * 7 / 3
        h.acUsageWeek = h.states.filter { $0 == "ac_out" }.count * stepMin * 7 / 3
        h.savingsWeek = Double(h.pvUsageWeek) / 60 * CostSettings.defaultElementKW * CostSettings.defaultRate

        h.timestamps.reverse(); h.temperatures.reverse(); h.states.reverse()
        h.pvVoltage.reverse(); h.mpptVoltage.reverse(); h.pvOutPower.reverse(); h.acOutPower.reverse()
        return h
    }

    /// Stable 0...1 "how sunny" value per day, so a given day always looks the same.
    private static func dayFactor(_ day: Int) -> Double {
        let x = sin(Double(day) * 12.9898) * 43758.5453
        return x - x.rounded(.down)
    }
}
