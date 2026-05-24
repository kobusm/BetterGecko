import Foundation
import SwiftUI

private let manualDevicesKey = "gg_manual_devices"

@MainActor
@Observable
class AppState {
    var session: SessionInfo? = nil
    var isLoadingSession = true

    var devices: [GeckoDevice] = []

    var isLoggedIn: Bool { session != nil }

    func restoreSession() async {
        isLoadingSession = true
        do {
            let info = try await AuthAPI.shared.restoreSession()
            session = info
            loadDevices()
        } catch {
            session = nil
        }
        isLoadingSession = false
    }

    func login(email: String, password: String) async throws {
        let info = try await AuthAPI.shared.login(email: email, password: password)
        session = info
        loadDevices()
    }

    func logout() async {
        await AuthAPI.shared.logout()
        session = nil
        devices = []
    }

    // MARK: - Device management (persisted locally)

    func loadDevices() {
        guard let data = UserDefaults.standard.data(forKey: manualDevicesKey),
              let saved = try? JSONDecoder().decode([GeckoDevice].self, from: data) else {
            devices = []
            return
        }
        devices = saved
    }

    func addDevice(_ device: GeckoDevice) {
        guard !devices.contains(where: { $0.geckoSerialNumber == device.geckoSerialNumber }) else { return }
        devices.append(device)
        saveDevices()
    }

    func removeDevice(gsn: String) {
        devices.removeAll { $0.geckoSerialNumber == gsn }
        saveDevices()
    }

    func renameDevice(gsn: String, name: String) {
        if let i = devices.firstIndex(where: { $0.geckoSerialNumber == gsn }) {
            devices[i].friendlyName = name
            saveDevices()
        }
    }

    private func saveDevices() {
        if let data = try? JSONEncoder().encode(devices) {
            UserDefaults.standard.set(data, forKey: manualDevicesKey)
        }
    }
}
