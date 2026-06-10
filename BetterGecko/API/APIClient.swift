import Foundation

private let baseURL = URL(string: "https://geckows.co.za")!
// Static app token, captured from the official app's live traffic. The server rotates
// it periodically; if /auth/signin starts returning the app's JSON "Invalid token"
// error, re-capture the current value. /auth/* and /app/deviceid send this token;
// per-device data calls send the session token returned by /auth/signin instead.
private let staticAppToken = "1f31271a71c05ca1b9c6c52a14086bb9"
// Must be a plain integer; the official app sends "17".
private let appVersion = "17"
// The server's nginx gateway returns a bare 403 to any request whose User-Agent
// contains "bettergecko" (URLSession's default UA is derived from the bundle name).
// Send the same iPhone Safari UA the official app uses so requests reach the API.
private let userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"

enum APIError: LocalizedError {
    case httpError(Int, String)
    case unauthorized
    case decodingError(Error)

    var errorDescription: String? {
        switch self {
        case .httpError(_, let msg): return msg
        case .unauthorized: return "Session expired. Please log in again."
        case .decodingError(let e): return e.localizedDescription
        }
    }
}

actor APIClient {
    static let shared = APIClient()

    private var sessionToken: String? {
        get { UserDefaults.standard.string(forKey: "gg_token") }
    }

    func setToken(_ token: String?) {
        if let token {
            UserDefaults.standard.set(token, forKey: "gg_token")
        } else {
            UserDefaults.standard.removeObject(forKey: "gg_token")
        }
    }

    func clearSession() {
        UserDefaults.standard.removeObject(forKey: "gg_token")
        UserDefaults.standard.removeObject(forKey: "gg_deviceid")
    }

    func request<T: Decodable>(
        method: String,
        path: String,
        body: [String: Any]? = nil,
        params: [String: String]? = nil,
        overrideToken: String? = nil,
        as type: T.Type = T.self
    ) async throws -> T {
        var urlComponents = URLComponents(url: baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if let params {
            urlComponents.queryItems = params.map { URLQueryItem(name: $0.key, value: $0.value) }
        }

        var urlRequest = URLRequest(url: urlComponents.url!, cachePolicy: .reloadIgnoringLocalCacheData)
        urlRequest.httpMethod = method
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        urlRequest.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        urlRequest.setValue(appVersion, forHTTPHeaderField: "X-App-Version")
        urlRequest.setValue(appVersion, forHTTPHeaderField: "X-Android-App-Version")
        urlRequest.setValue(appVersion, forHTTPHeaderField: "X-IOS-App-Version")
        urlRequest.setValue("https://geckows.co.za", forHTTPHeaderField: "Origin")
        urlRequest.setValue("https://geckows.co.za/", forHTTPHeaderField: "Referer")

        let token = overrideToken ?? sessionToken
        if let token {
            urlRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        if let body {
            urlRequest.httpBody = try JSONSerialization.data(withJSONObject: body)
        }

        print("[API] \(method) \(urlComponents.url!)")
        let (data, response) = try await URLSession.shared.data(for: urlRequest)
        guard let http = response as? HTTPURLResponse else { throw APIError.httpError(0, "No response") }
        print("[API] \(http.statusCode) \(path)")

        // Only 401 means "token expired" — safe to silently re-login and replay.
        // 403 is "forbidden" (e.g. app-version/build rejected, or account locked):
        // re-logging in just repeats the same rejected signin in a tight loop, which
        // can trigger a server-side account lockout. Let 403 fall through to the
        // error block below so it surfaces the server's message instead of looping.
        if http.statusCode == 401 {
            // Don't retry auth requests themselves (avoids infinite loop)
            guard overrideToken == nil else { throw APIError.unauthorized }
            // Try silent re-login, then replay with the fresh session token
            do {
                _ = try await AuthAPI.shared.reloginWithStoredCredentials()
                let freshToken = sessionToken
                return try await request(method: method, path: path, body: body,
                                         params: params, overrideToken: freshToken, as: type)
            } catch {
                throw APIError.unauthorized
            }
        }

        if !(200..<300).contains(http.statusCode) {
            let msg = (try? JSONDecoder().decode([String: String].self, from: data))
                .flatMap { $0["customMsg"] ?? $0["message"] ?? $0["error"] }
                ?? "HTTP \(http.statusCode)"
            throw APIError.httpError(http.statusCode, msg)
        }

        do {
            let decoder = JSONDecoder()
            return try decoder.decode(T.self, from: data)
        } catch let decodeError {
            // Log raw response to help diagnose mismatches
            print("[API] Decode error for \(path): \(decodeError)")
            print("[API] Raw response: \(String(data: data, encoding: .utf8) ?? "<binary>")")
            throw APIError.decodingError(decodeError)
        }
    }

    func get<T: Decodable>(_ path: String, params: [String: String]? = nil, as type: T.Type = T.self) async throws -> T {
        try await request(method: "GET", path: path, params: params, as: type)
    }

    func post<T: Decodable>(_ path: String, body: [String: Any]? = nil, as type: T.Type = T.self) async throws -> T {
        try await request(method: "POST", path: path, body: body, as: type)
    }

    func postWith<T: Decodable>(_ path: String, body: [String: Any], token: String, as type: T.Type = T.self) async throws -> T {
        try await request(method: "POST", path: path, body: body, overrideToken: token, as: type)
    }

    var storedToken: String? { sessionToken }
    var storedDeviceID: String? { UserDefaults.standard.string(forKey: "gg_deviceid") }

    func storeDeviceID(_ id: String) {
        UserDefaults.standard.set(id, forKey: "gg_deviceid")
    }
}
