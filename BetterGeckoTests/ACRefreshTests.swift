import Foundation
import Testing
@testable import BetterGecko

/// Uses real UserDefaults (where ACUsageLedger lives) under throwaway serials.
@Suite(.serialized)
struct ACRefreshTests {
    private struct Boom: Error {}
    private let start = ISO8601DateFormatter().date(from: "2026-11-16T08:00:00Z")!

    /// One hour of 5-minute samples on 2026-11-16 (SAST); the first `on` intervals are element-on.
    private func hour(on: Int = 12) -> [HistoryPoint] {
        (0...12).map {
            HistoryPoint(timestamp: start.addingTimeInterval(Double($0) * 300), temperature: nil,
                         pvVoltage: nil, mpptVoltage: nil, pvInPower: nil, pvOutPower: nil,
                         acOutPower: nil, state: $0 < on ? "ac_out" : "idle")
        }
    }

    private func clear(_ gsns: [String]) {
        gsns.forEach { UserDefaults.standard.removeObject(forKey: "ac_usage_ledger_\($0)") }
    }

    @Test func mergesEveryDeviceAndSkipsFailuresWithoutStopping() async {
        let gsns = ["test-aaa", "test-bad", "test-ccc"]
        clear(gsns); defer { clear(gsns) }
        let updated = await ACRefresh.refreshLedgers(gsns: gsns) { gsn in
            if gsn == "test-bad" { throw Boom() }
            return hour()
        }
        #expect(updated == 2)
        #expect(ACUsageLedger.load(gsn: "test-aaa").days["2026-11-16"] == 3600)
        #expect(ACUsageLedger.load(gsn: "test-ccc").days["2026-11-16"] == 3600)
        #expect(ACUsageLedger.load(gsn: "test-bad").days.isEmpty)
    }

    @Test func laterSmallerWindowNeverLowersADay() async {
        let gsns = ["test-keep"]
        clear(gsns); defer { clear(gsns) }
        await ACRefresh.refreshLedgers(gsns: gsns) { _ in hour(on: 12) }
        await ACRefresh.refreshLedgers(gsns: gsns) { _ in hour(on: 6) }
        #expect(ACUsageLedger.load(gsn: "test-keep").days["2026-11-16"] == 3600)
    }
}
