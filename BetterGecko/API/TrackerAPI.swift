import Foundation

/// Optional self-hosted AC-hours tracker (the Cloudflare Worker in `worker/`).
///
/// The worker logs in on its own schedule and keeps every day's AC on-time, so it fills the
/// gaps the on-phone ledger gets when the app isn't opened for 3+ days. It's per-user: the
/// URL and API key are entered in Profile, never built into the app. The key lives in the
/// Keychain like the GeyserGecko password.
enum Tracker {
    private static let urlKey = "ac_tracker_url"
    private static let keychainKey = "ac_tracker_key"

    static var url: String? { UserDefaults.standard.string(forKey: urlKey) }
    static var apiKey: String? { Keychain.load(forKey: keychainKey) }
    static var isConfigured: Bool { !Demo.isEnabled && baseURL != nil && apiKey?.isEmpty == false }

    static func save(url: String, apiKey: String) {
        UserDefaults.standard.set(url.trimmingCharacters(in: .whitespaces), forKey: urlKey)
        Keychain.save(apiKey.trimmingCharacters(in: .whitespaces), forKey: keychainKey)
    }

    static func clear() {
        UserDefaults.standard.removeObject(forKey: urlKey)
        Keychain.delete(forKey: keychainKey)
    }

    /// Only https URLs are accepted: the API key travels in a header.
    static func validURL(_ string: String?) -> URL? {
        guard let s = string?.trimmingCharacters(in: .whitespaces), !s.isEmpty,
              let u = URL(string: s), u.scheme == "https", u.host?.isEmpty == false else { return nil }
        return u
    }
    private static var baseURL: URL? { validURL(url) }

    /// The tracker's per-day AC seconds for this month and last month ("yyyy-MM-dd" → seconds).
    static func fetchDays(gsn: String) async throws -> [String: Double] {
        guard let base = baseURL, let key = apiKey, !key.isEmpty else {
            throw APIError.httpError(0, "Tracker not set up")
        }
        var comps = URLComponents(url: base.appendingPathComponent("usage"), resolvingAgainstBaseURL: false)!
        comps.queryItems = [URLQueryItem(name: "gsn", value: gsn)]
        var req = URLRequest(url: comps.url!, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
        req.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await URLSession.shared.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200 else {
            let msg = code == 401 ? "Wrong API key" : code == 404 ? "Tracker doesn't track this device" : "HTTP \(code)"
            throw APIError.httpError(code, msg)
        }
        return try TrackerUsageResponse.days(from: data)
    }
}

struct TrackerUsageResponse: Decodable {
    struct Day: Decodable { let day: String; let seconds: Double }
    struct Month: Decodable { let month: String; let days: [Day] }
    let months: [Month]

    static func days(from data: Data) throws -> [String: Double] {
        let r = try JSONDecoder().decode(TrackerUsageResponse.self, from: data)
        return Dictionary(r.months.flatMap(\.days).map { ($0.day, $0.seconds) }, uniquingKeysWith: max)
    }
}

extension ACUsageLedger {
    /// Folds in another source's per-day totals. Each day keeps the larger value, since
    /// either side may have missed part of a day.
    mutating func mergeRemote(_ remote: [String: Double]) {
        for (day, secs) in remote { days[day] = max(days[day] ?? 0, secs) }
    }
}
