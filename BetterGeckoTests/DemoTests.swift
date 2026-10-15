import Foundation
import Testing
@testable import BetterGecko

struct DemoTests {
    private let now = ISO8601DateFormatter().date(from: "2026-10-14T12:03:20Z")!   // 14:03 SAST
    private let defaults = PersistedSettings(targetTemp: 45, pvTargetTemp: 65, mode: "AC_AND_PV")

    /// Round-trips the simulation through the same JSON decoding the real API uses.
    private func points(_ s: PersistedSettings) throws -> [HistoryPoint] {
        let data = try JSONSerialization.data(withJSONObject: Demo.history(now: now, settings: s).json)
        return try JSONDecoder().decode(PerformanceHistoryResponse.self, from: data).toHistoryPoints()
    }

    @Test func coversThreeDaysAtFiveMinutesNewestFirst() throws {
        let h = Demo.history(now: now, settings: defaults)
        #expect(h.timestamps.count == 3 * 24 * 12 + 1)
        #expect(h.timestamps.first == "2026-10-14T12:00:00.000Z")
        let pts = try points(defaults)
        #expect(pts.count == h.timestamps.count)   // every timestamp parses
        #expect(zip(pts, pts.dropFirst()).allSatisfy { $0.timestamp < $1.timestamp })
    }

    @Test func solarAndElementBothRunAndTemperatureStaysPlausible() throws {
        let pts = try points(defaults)
        #expect(pts.contains { $0.pvOn == 1 })
        #expect(pts.contains { $0.acOn == 1 })
        #expect(pts.allSatisfy { (18...75).contains($0.temperature ?? -1) })
        #expect(pts.filter { $0.pvOn == 1 }.allSatisfy { ($0.pvVoltage ?? 0) > 0 })
    }

    @Test func elementRunsWithinTheLastDay() throws {
        let cutoff = now.addingTimeInterval(-24 * 3600)
        #expect(try points(defaults).filter { $0.timestamp >= cutoff }.contains { $0.acOn == 1 })
    }

    @Test func respectsMode() throws {
        var solarOnly = defaults; solarOnly.mode = "PV_ONLY"
        #expect(try points(solarOnly).allSatisfy { $0.acOn == 0 })
        var off = defaults; off.mode = "ALL_OFF"
        #expect(try points(off).allSatisfy { $0.pvOn == 0 && $0.acOn == 0 })
    }

    @Test func isDeterministic() {
        let a = Demo.history(now: now, settings: defaults)
        let b = Demo.history(now: now, settings: defaults)
        #expect(a.timestamps == b.timestamps && a.temperatures == b.temperatures && a.states == b.states)
    }

    @Test func cannedResponsesDecode() throws {
        let control = try JSONDecoder().decode(HasControlResponse.self, from: Demo.response(for: "/app/hasControl"))
        #expect(control.controlled)
        _ = try JSONDecoder().decode(EmptyResponse.self, from: Demo.response(for: "/app/setMode"))
        let id = try JSONDecoder().decode(DeviceIDResponse.self, from: Demo.response(for: "/app/deviceid"))
        #expect(id.deviceID == Demo.deviceID)
    }
}
