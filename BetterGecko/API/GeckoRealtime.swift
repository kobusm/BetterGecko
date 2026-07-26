import Foundation

/// Minimal Engine.IO v3 (socket.io v2) long-polling client.
///
/// The GeyserGecko API has no REST endpoint that returns the current temperature
/// setpoints or operating mode — those values are only delivered over the socket.io
/// realtime channel. This client performs just enough of the protocol to fetch a
/// snapshot: it opens a polling session, subscribes to the device, reads the queued
/// `heartbeatUpdate` (AC_MAX / PV_MAX) and `userGeckos` (geckoMode) events, then stops.
///
/// It is deliberately fetch-once (not a persistent connection): open Settings → pull
/// the live values → close. Transport is HTTP long-polling (not websocket) so it reuses
/// URLSession and avoids frame-level websocket handling.
struct GeckoRealtime {
    static let shared = GeckoRealtime()

    struct Live {
        var acMax: Int?
        var pvMax: Int?
        var geckoMode: String?   // "ALL_OFF" | "PV_ONLY" | "AC_AND_PV" | "AC_ONLY"

        // Device info (from heartbeatUpdate)
        var temperature: Double?
        var fwVersion: String?
        var mcuFwVersion: Int?
        var connectedAP: String?
        var rssi: Int?

        var hasSetpoints: Bool { acMax != nil && pvMax != nil }
        var hasDeviceInfo: Bool { fwVersion != nil || rssi != nil }
        var isComplete: Bool { hasSetpoints && geckoMode != nil }
    }

    private let baseURL = "https://geckows.co.za/socket.io/"
    private let userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"
    private let session = URLSession(configuration: .ephemeral)

    /// Fetches the current live settings for a device. Returns whatever it managed to
    /// read before the deadline; fields may be nil if the device was slow to report.
    func fetchSettings(gsn: String, deviceID: String, timeout: TimeInterval = 12) async throws -> Live {
        let token = "u" + deviceID
        let commonQuery = [
            "token": token,
            "ANDROID_APP_VERSION": "17",
            "IOS_APP_VERSION": "17",
            "EIO": "3",
            "transport": "polling",
        ]

        // 1. Handshake — get the session id.
        let handshakeBody = try await get(query: commonQuery)
        guard let sid = parseSID(from: handshakeBody) else {
            throw APIError.httpError(0, "socket.io handshake failed")
        }

        var query = commonQuery
        query["sid"] = sid

        // 2. Subscribe to the device's heartbeats and request its profile (mode).
        try? await post(query: query,
                        packet: #"42["watchGecko",{"geckoSerialNumber":"\#(gsn)","deviceID":"\#(deviceID)"}]"#)
        try? await post(query: query,
                        packet: #"42["getUserGeckos",{"deviceID":"\#(deviceID)","ANDROID_APP_VERSION":17,"IOS_APP_VERSION":17}]"#)

        // 3. Poll until we have the values or the deadline passes.
        var live = Live()
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline, !live.isComplete {
            let body: String
            do { body = try await get(query: query) } catch { break }
            for packet in parsePackets(body) {
                apply(packet: packet, gsn: gsn, into: &live)
            }
            // A poll can return empty (server timed out with a noop) — avoid a hot loop.
            if body.isEmpty { try? await Task.sleep(nanoseconds: 300_000_000) }
        }

        // 4. Best-effort close.
        try? await post(query: query, packet: "1")

        return live
    }

    // MARK: - HTTP

    private func makeURL(query: [String: String]) -> URL {
        var comps = URLComponents(string: baseURL)!
        // Manually percent-encode so "@" in the token becomes %40 (matches the app).
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.")
        comps.percentEncodedQuery = query
            .map { "\($0.key)=\($0.value.addingPercentEncoding(withAllowedCharacters: allowed) ?? $0.value)" }
            .joined(separator: "&")
        return comps.url!
    }

    private func get(query: [String: String]) async throws -> String {
        var req = URLRequest(url: makeURL(query: query), cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("*/*", forHTTPHeaderField: "Accept")
        let (data, _) = try await session.data(for: req)
        return String(data: data, encoding: .utf8) ?? ""
    }

    private func post(query: [String: String], packet: String) async throws {
        var req = URLRequest(url: makeURL(query: query), timeoutInterval: 30)
        req.httpMethod = "POST"
        req.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("text/plain;charset=UTF-8", forHTTPHeaderField: "Content-Type")
        // Engine.IO v3 framing: "<charLength>:<packet>"
        req.httpBody = "\(packet.count):\(packet)".data(using: .utf8)
        _ = try await session.data(for: req)
    }

    // MARK: - Engine.IO parsing

    /// Splits an Engine.IO v3 polling payload ("<len>:<packet><len>:<packet>…") into packets.
    private func parsePackets(_ s: String) -> [String] {
        var packets: [String] = []
        let chars = Array(s)
        var i = 0
        while i < chars.count {
            var numStr = ""
            while i < chars.count, chars[i].isNumber { numStr.append(chars[i]); i += 1 }
            guard i < chars.count, chars[i] == ":", let len = Int(numStr) else { break }
            i += 1 // skip ':'
            guard i + len <= chars.count else { break }
            packets.append(String(chars[i ..< i + len]))
            i += len
        }
        return packets
    }

    private func parseSID(from payload: String) -> String? {
        for packet in parsePackets(payload) where packet.hasPrefix("0") {
            let json = String(packet.dropFirst())
            if let data = json.data(using: .utf8),
               let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let sid = obj["sid"] as? String {
                return sid
            }
        }
        return nil
    }

    /// Applies one packet's event data into the accumulating snapshot.
    private func apply(packet: String, gsn: String, into live: inout Live) {
        // We only care about socket.io event packets ("42[...]").
        guard packet.hasPrefix("42") else { return }
        let json = String(packet.dropFirst(2))
        guard let data = json.data(using: .utf8),
              let arr = try? JSONSerialization.jsonObject(with: data) as? [Any],
              let event = arr.first as? String,
              arr.count > 1 else { return }

        switch event {
        case "heartbeatUpdate":
            if let hb = arr[1] as? [String: Any] {
                if let ac = intValue(hb["AC_MAX"]) { live.acMax = ac }
                if let pv = intValue(hb["PV_MAX"]) { live.pvMax = pv }
                if let t = hb["temperature"] as? Double { live.temperature = t }
                if let fw = hb["FW_VERSION"] as? String { live.fwVersion = fw }
                if let mcu = intValue(hb["MCU_FW_VERSION"]) { live.mcuFwVersion = mcu }
                if let ap = hb["CONNECTED_AP_NAME"] as? String { live.connectedAP = ap }
                if let r = intValue(hb["rssi"]) { live.rssi = r }
            }
        case "userGeckos", "onlineGeckos", "userDevices":
            if let dict = arr[1] as? [String: Any] {
                // The array is under a key matching the event name.
                let list = (dict[event] as? [[String: Any]]) ?? dict.values.compactMap { $0 as? [[String: Any]] }.first
                if let match = list?.first(where: { ($0["geckoSerialNumber"] as? String) == gsn }),
                   let mode = match["geckoMode"] as? String {
                    live.geckoMode = mode
                }
            }
        default:
            break
        }
    }

    private func intValue(_ any: Any?) -> Int? {
        if let i = any as? Int { return i }
        if let d = any as? Double { return Int(d.rounded()) }
        return nil
    }
}
