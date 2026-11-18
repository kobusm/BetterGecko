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

    // Shape returned by the worker's GET /usage.
    private let sample = #"{"gsn":"fce8c0de16e0","lastRun":{"ran_at":"2026-11-17T20:10:15.590Z","ok":1,"error":null},"months":[{"month":"2026-11","hours":0.67,"days":[{"day":"2026-11-15","hours":0.08,"seconds":300},{"day":"2026-11-16","hours":0.42,"seconds":1500}]},{"month":"2026-10","hours":0,"days":[]}]}"#

    @Test func parsesTrackerDays() throws {
        let days = try TrackerUsageResponse.days(from: Data(sample.utf8))
        #expect(days == ["2026-11-15": 300, "2026-11-16": 1500])
    }

    @Test func mergeRemoteKeepsTheLargerDay() {
        var ledger = ACUsageLedger(days: ["2026-11-15": 600, "2026-11-16": 900])
        ledger.mergeRemote(["2026-11-15": 300, "2026-11-16": 1500, "2026-11-01": 60])
        #expect(ledger.days == ["2026-11-15": 600, "2026-11-16": 1500, "2026-11-01": 60])
    }

    @Test func trackerStillUpdatesWhenHistoryFails() async {
        let gsns = ["test-remote"]
        clear(gsns); defer { clear(gsns) }
        let updated = await ACRefresh.refreshLedgers(gsns: gsns,
                                                     fetch: { _ in throw Boom() },
                                                     remote: { _ in ["2026-11-01": 7200] })
        #expect(updated == 1)
        #expect(ACUsageLedger.load(gsn: "test-remote").days["2026-11-01"] == 7200)
    }

    @Test func trackerFailureDoesNotBlockHistory() async {
        let gsns = ["test-hist"]
        clear(gsns); defer { clear(gsns) }
        let updated = await ACRefresh.refreshLedgers(gsns: gsns,
                                                     fetch: { _ in hour() },
                                                     remote: { _ in throw Boom() })
        #expect(updated == 1)
        #expect(ACUsageLedger.load(gsn: "test-hist").days["2026-11-16"] == 3600)
    }

    @Test func trackerOnlyAcceptsHTTPS() {
        #expect(Tracker.validURL("https://x.workers.dev") != nil)
        #expect(Tracker.validURL("http://x.workers.dev") == nil)
        #expect(Tracker.validURL("x.workers.dev") == nil)
        #expect(Tracker.validURL("  ") == nil)
    }
}
