import BackgroundTasks
import Foundation

/// Background App Refresh for AC on-hours.
///
/// The history endpoint only covers ~3 days, so the on-phone `ACUsageLedger` has gaps
/// whenever the app isn't opened for a while. iOS can wake the app in the background to
/// refresh it, but *it* decides when: an app refresh task is only a request, weighed
/// against how often the app is used, battery, Low Power Mode and the user's Background
/// App Refresh setting. Swiping the app away in the switcher stops these until next launch.
enum ACRefresh {
    /// Must match BGTaskSchedulerPermittedIdentifiers in Info.plist.
    static let taskID = "com.bettergecko.app.acrefresh"
    private static let lastRunKey = "ac_refresh_last_run"

    /// Asks iOS to run the refresh some time after `earliest`. Submitting again replaces
    /// the pending request, so this is safe to call every time the app is backgrounded.
    static func schedule(earliest: TimeInterval = 12 * 3600) {
        let request = BGAppRefreshTaskRequest(identifier: taskID)
        request.earliestBeginDate = Date(timeIntervalSinceNow: earliest)
        do { try BGTaskScheduler.shared.submit(request) }
        catch { print("[ACRefresh] schedule failed: \(error)") }   // e.g. unavailable on Simulator
    }

    /// Runs one refresh for every saved device. Skipped in demo mode and when signed out.
    static func run() async {
        guard !Demo.isEnabled, await APIClient.shared.storedToken != nil else { return }
        let gsns = AppState.savedDevices().map(\.geckoSerialNumber)
        let ok = await refreshLedgers(
            gsns: gsns,
            fetch: { try await DeviceAPI.shared.getPerformanceHistory(gsn: $0).toHistoryPoints() },
            remote: Tracker.isConfigured ? { try await Tracker.fetchDays(gsn: $0) } : nil)
        if ok > 0 { UserDefaults.standard.set(Date(), forKey: lastRunKey) }
    }

    /// When a background refresh last updated at least one device (nil if never).
    static var lastRun: Date? { UserDefaults.standard.object(forKey: lastRunKey) as? Date }

    /// Fetches each device's history and merges it into that device's ledger, then (if
    /// `remote` is given) the tracker's per-day totals. A device counts as updated if either
    /// source worked, and one device failing doesn't stop the rest. Returns how many updated.
    @discardableResult
    static func refreshLedgers(gsns: [String],
                               fetch: (String) async throws -> [HistoryPoint],
                               remote: ((String) async throws -> [String: Double])? = nil) async -> Int {
        var updated = 0
        for gsn in gsns {
            var ledger = ACUsageLedger.load(gsn: gsn)
            var ok = false
            do { ledger.merge(try await fetch(gsn)); ok = true }
            catch { print("[ACRefresh] history \(gsn): \(error)") }
            if let remote {
                do { ledger.mergeRemote(try await remote(gsn)); ok = true }
                catch { print("[ACRefresh] tracker \(gsn): \(error)") }
            }
            if ok { ledger.save(gsn: gsn); updated += 1 }
        }
        return updated
    }
}
