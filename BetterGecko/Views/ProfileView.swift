import SwiftUI

struct ProfileView: View {
    @Environment(AppState.self) private var appState
    @State private var showLogoutConfirm = false

    @State private var selectedGSN: String = ""
    @State private var systemName: String = ""
    @State private var address: String = ""
    @State private var live: GeckoRealtime.Live?
    @State private var isLoadingInfo = false

    @State private var trackerURL = Tracker.url ?? ""
    @State private var trackerKey = ""
    @State private var trackerStatus: String?
    @State private var isTestingTracker = false

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

                if !Demo.isEnabled { trackerSection() }

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

    private var hasSavedKey: Bool { Tracker.apiKey?.isEmpty == false }

    private func trackerSection() -> some View {
        Section {
            TextField("https://….workers.dev", text: $trackerURL)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            SecureField(hasSavedKey ? "API key (saved)" : "API key", text: $trackerKey)
            Button {
                Task { await saveTracker() }
            } label: {
                HStack {
                    Text("Save & Test")
                    if isTestingTracker { Spacer(); ProgressView() }
                }
            }
            .disabled(isTestingTracker || Tracker.validURL(trackerURL) == nil || (trackerKey.isEmpty && !hasSavedKey))
            if let trackerStatus {
                Text(trackerStatus).font(.footnote).foregroundStyle(.secondary)
            }
            if Tracker.url != nil {
                Button("Remove Tracker", role: .destructive) {
                    Tracker.clear()
                    trackerURL = ""; trackerKey = ""; trackerStatus = nil
                }
            }
        } header: {
            Text("AC Hours Tracker")
        } footer: {
            Text("Optional. A tracker you host (see worker/ in the BetterGecko repo) keeps every day's AC hours even when this app isn't opened. Its days are merged with the ones counted on this phone.\n\nLast background refresh: \(ACRefresh.lastRun?.formatted(date: .abbreviated, time: .shortened) ?? "not yet")")
        }
    }

    private func saveTracker() async {
        let key = trackerKey.isEmpty ? (Tracker.apiKey ?? "") : trackerKey
        Tracker.save(url: trackerURL, apiKey: key)
        trackerKey = ""
        guard let gsn = selectedDevice?.geckoSerialNumber else {
            trackerStatus = "Saved. Add a device to test it."
            return
        }
        isTestingTracker = true
        defer { isTestingTracker = false }
        do {
            let days = try await Tracker.fetchDays(gsn: gsn)
            var ledger = ACUsageLedger.load(gsn: gsn)
            ledger.mergeRemote(days)
            ledger.save(gsn: gsn)
            let hours = days.values.reduce(0, +) / 3600
            trackerStatus = "Connected: \(days.count) days on record, \(String(format: "%.1f", hours)) h of AC."
        } catch {
            trackerStatus = "Saved, but the test failed: \(error.localizedDescription)"
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
