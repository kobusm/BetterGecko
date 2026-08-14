import Foundation

struct SessionInfo {
    let email: String
    let token: String
    let role: String
    let deviceID: String
}

struct GeckoDevice: Identifiable, Codable, Equatable, Hashable {
    let geckoSerialNumber: String
    var friendlyName: String?

    var id: String { geckoSerialNumber }
    var displayName: String { friendlyName?.isEmpty == false ? friendlyName! : geckoSerialNumber }
}

struct HistoryPoint: Identifiable {
    let id = UUID()
    let timestamp: Date
    let temperature: Double?
    let pvVoltage: Int?
    let mpptVoltage: Int?
    let pvInPower: Int?
    let pvOutPower: Int?
    let acOutPower: Int?
    let state: String?

    /// PV current in amps: I = P / V  (uses pvOutPower, falls back to pvInPower).
    /// pvVoltage is already scaled (÷3) for display, so undo that here for the
    /// physical current calculation.
    var pvCurrent: Double? {
        let power = (pvOutPower ?? 0) > 0 ? pvOutPower : pvInPower
        guard let p = power, p > 0, let v = pvVoltage, v > 0 else { return nil }
        return Double(p) / (Double(v) * 3)
    }

    /// Utility (element) current in amps: I = P / V
    var utilityCurrent: Double? {
        guard let p = acOutPower, p > 0, let v = mpptVoltage, v > 0 else { return nil }
        return Double(p) / Double(v)
    }

    /// 1 when solar (PV) output is active, 0 otherwise
    var pvOn: Int { state == "pv_out" ? 1 : 0 }

    /// 1 when AC element is active, 0 otherwise
    var acOn: Int { state == "ac_out" ? 1 : 0 }
}

enum GeckoState: String {
    case solarHeating = "pv_out"
    case elementHeating = "ac_out"
    case off = "off"
    case idle = "idle"
    case unknown

    var label: String {
        switch self {
        case .solarHeating: return "Solar Heating"
        case .elementHeating: return "AC Heating"
        case .idle: return "Idle"
        case .off: return "Off"
        case .unknown: return "Unknown"
        }
    }

    var symbolName: String {
        switch self {
        case .solarHeating: return "sun.max.fill"
        case .elementHeating: return "bolt.fill"
        case .idle: return "moon.fill"
        case .off: return "power"
        case .unknown: return "questionmark.circle"
        }
    }

    init(rawString: String?) {
        if let raw = rawString, let state = GeckoState(rawValue: raw) {
            self = state
        } else {
            self = .unknown
        }
    }
}

private let iso8601Formatter: ISO8601DateFormatter = {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return f
}()

extension PerformanceHistoryResponse {
    func toHistoryPoints() -> [HistoryPoint] {
        guard let timestamps else { return [] }
        // Server returns newest-first; we want oldest-first for charts
        return timestamps.enumerated().compactMap { i, tsString in
            guard let date = iso8601Formatter.date(from: tsString) else { return nil }
            return HistoryPoint(
                timestamp: date,
                temperature: temperatures?[safe: i],
                pvVoltage: pvVoltage?[safe: i].map { $0 / 3 },   // scale reported PV voltage ÷3
                mpptVoltage: mpptVoltage?[safe: i],
                pvInPower: pvInPower?[safe: i],
                pvOutPower: pvOutPower?[safe: i],
                acOutPower: acOutPower?[safe: i],
                state: state?[safe: i]
            )
        }.reversed()
    }

    var latestTemperature: Double? { temperatures?.first }
    var latestState: String? { state?.first }
    var latestPVVoltage: Int? { pvVoltage?.first.map { $0 / 3 } }   // scale ÷3
}

extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
