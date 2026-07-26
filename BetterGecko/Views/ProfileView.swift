import SwiftUI

struct ProfileView: View {
    @Environment(AppState.self) private var appState
    @State private var showLogoutConfirm = false

    @State private var selectedGSN: String = ""
    @State private var systemName: String = ""
    @State private var address: String = ""
    @State private var live: GeckoRealtime.Live?
    @State private var isLoadingInfo = false

    private var selectedDevice: GeckoDevice? {
        appState.devices.first { $0.geckoSerialNumber == selectedGSN } ?? appState.devices.first
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Account") {
                    LabeledContent("Email", value: appState.session?.email ?? "—")
                    LabeledContent("Role", value: appState.session?.role ?? "—")
                }

                if let device = selectedDevice {
                    if appState.devices.count > 1 {
                        Section {
                            Picker("Device", selection: $selectedGSN) {
                                ForEach(appState.devices) { d in
                                    Text(d.displayName).tag(d.geckoSerialNumber)
                                }
                            }
                        }
                    }

                    systemSection(device)
                    deviceInfoSection(device)
                }

                Section {
                    Button("Sign out", role: .destructive) { showLogoutConfirm = true }
                }
            }
            .navigationTitle("Profile")
            .confirmationDialog("Sign out?", isPresented: $showLogoutConfirm, titleVisibility: .visible) {
                Button("Sign out", role: .destructive) { Task { await appState.logout() } }
                Button("Cancel", role: .cancel) {}
            }
            .onAppear {
                if selectedGSN.isEmpty, let first = appState.devices.first {
                    selectedGSN = first.geckoSerialNumber
                }
                loadLocal()
                Task { await loadInfo() }
            }
            .onChange(of: selectedGSN) { old, _ in
                commitAll(gsn: old)   // save edits for the device we're leaving
                loadLocal()
                live = nil
                Task { await loadInfo() }
            }
            .onDisappear { commitAll(gsn: selectedGSN) }
        }
    }

    // MARK: - Sections

    private func systemSection(_ device: GeckoDevice) -> some View {
        Section {
            HStack {
                Text("System Name")
                Spacer()
                TextField("Name", text: $systemName)
                    .multilineTextAlignment(.trailing)
                    .submitLabel(.done)
                    .onSubmit { commitName(device) }
            }
            VStack(alignment: .leading, spacing: 4) {
                Text("Installation Address").font(.caption).foregroundStyle(.secondary)
                TextField("Street, city…", text: $address, axis: .vertical)
                    .lineLimit(1...3)
            }
        } header: {
            Text("System")
        } footer: {
            Text("Stored on this device only — GeyserGecko has no API to save the name or address to the server.")
        }
    }

    private func deviceInfoSection(_ device: GeckoDevice) -> some View {
        Section {
            LabeledContent("GSN", value: device.geckoSerialNumber)
            LabeledContent("Serial Number", value: device.geckoSerialNumber)
            infoRow("Firmware", live?.fwVersion)
            infoRow("MCU Firmware", live?.mcuFwVersion.map { "\($0)" })
            infoRow("Connected AP", live?.connectedAP)
            infoRow("Signal (RSSI)", live?.rssi.map { "\($0) dBm" })
            if let t = live?.temperature {
                LabeledContent("Temperature", value: String(format: "%.1f °C", t))
            }
        } header: {
            HStack {
                Text("Device")
                Spacer()
                if isLoadingInfo { ProgressView().scaleEffect(0.7) }
            }
        } footer: {
            Text("The API exposes only one serial (the GSN); there is no separate hardware serial number. Firmware, AP and signal come from the live device.")
        }
    }

    private func infoRow(_ title: String, _ value: String?) -> some View {
        LabeledContent(title, value: value ?? (isLoadingInfo ? "…" : "—"))
    }

    // MARK: - Actions

    private func loadLocal() {
        guard let device = selectedDevice else { return }
        systemName = device.friendlyName ?? ""
        address = appState.deviceAddress(gsn: device.geckoSerialNumber)
    }

    private func commitName(_ device: GeckoDevice) {
        appState.renameDevice(gsn: device.geckoSerialNumber, name: systemName)
    }

    /// Persists both the name and address for a given device (used when switching
    /// devices or leaving the tab, so in-progress edits aren't lost).
    private func commitAll(gsn: String) {
        guard !gsn.isEmpty else { return }
        appState.renameDevice(gsn: gsn, name: systemName)
        appState.setDeviceAddress(address, gsn: gsn)
    }

    private func loadInfo() async {
        guard let device = selectedDevice else { return }
        isLoadingInfo = true
        if let deviceID = await APIClient.shared.storedDeviceID,
           let snapshot = try? await GeckoRealtime.shared.fetchSettings(
               gsn: device.geckoSerialNumber, deviceID: deviceID) {
            live = snapshot
        }
        isLoadingInfo = false
    }
}
