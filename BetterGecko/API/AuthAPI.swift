import Foundation

private let staticToken  = "a6d2818e0657b5efb2e3ef9fecfe4731"
private let keychainEmail    = "gg_email"
private let keychainPassword = "gg_password"

struct SignInResponse: Decodable {
    let xsrfToken: String?
    let token: String
    let role: String?
}

struct DeviceIDResponse: Decodable {
    let deviceID: String
    let role: String?
}

struct AuthAPI {
    static let shared = AuthAPI()
    private let client = APIClient.shared

    // MARK: - Login

    func login(email: String, password: String) async throws -> SessionInfo {
        let authData = try await client.postWith(
            "/auth/signin",
            body: ["email": email, "pw": password],
            token: staticToken,
            as: SignInResponse.self
        )
        await client.setToken(authData.token)

        let deviceData = try await client.post("/app/deviceid", body: [:], as: DeviceIDResponse.self)
        await client.storeDeviceID(deviceData.deviceID)

        // Persist credentials so we can re-login automatically
        Keychain.save(email,    forKey: keychainEmail)
        Keychain.save(password, forKey: keychainPassword)

        return SessionInfo(
            email: email,
            token: authData.token,
            role: deviceData.role ?? authData.role ?? "user",
            deviceID: deviceData.deviceID
        )
    }

    // MARK: - Restore / auto re-login

    /// Called on app launch. Tries stored token first; if that 401s, re-logs in
    /// with saved credentials automatically.
    func restoreSession() async throws -> SessionInfo {
        // 1. Try using the stored session token
        if await client.storedToken != nil {
            do {
                return try await fetchSessionInfo()
            } catch APIError.unauthorized {
                // Token expired — fall through to credential re-login
            }
        }

        // 2. Try re-login with stored credentials
        return try await reloginWithStoredCredentials()
    }

    /// Re-authenticates silently using Keychain credentials. Used both during
    /// session restore and whenever a mid-session 401 is encountered.
    func reloginWithStoredCredentials() async throws -> SessionInfo {
        guard let email    = Keychain.load(forKey: keychainEmail),
              let password = Keychain.load(forKey: keychainPassword) else {
            throw APIError.unauthorized
        }
        return try await login(email: email, password: password)
    }

    // MARK: - Logout

    func logout() async {
        _ = try? await client.post("/auth/logout", body: [:], as: EmptyResponse.self)
        await client.clearSession()
        Keychain.delete(forKey: keychainEmail)
        Keychain.delete(forKey: keychainPassword)
    }

    // MARK: - Private

    private func fetchSessionInfo() async throws -> SessionInfo {
        let deviceData = try await client.post("/app/deviceid", body: [:], as: DeviceIDResponse.self)
        await client.storeDeviceID(deviceData.deviceID)
        let deviceID = deviceData.deviceID
        let email    = Keychain.load(forKey: keychainEmail) ?? String(deviceID.dropLast(32))
        return SessionInfo(
            email: email,
            token: await client.storedToken ?? "",
            role: deviceData.role ?? "user",
            deviceID: deviceID
        )
    }
}

struct EmptyResponse: Decodable {}
