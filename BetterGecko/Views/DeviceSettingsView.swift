import SwiftUI

// MARK: - Mode definitions

private struct GeckoMode: Identifiable {
    let id: String   // API string value e.g. "ALL_OFF"
    let label: String
    let icon: String
}

private let geckoModes: [GeckoMode] = [
    GeckoMode(id: "ALL_OFF",   label: "Off",             icon: "power"),
    GeckoMode(id: "PV_ONLY",   label: "Solar Only",      icon: "sun.max.fill"),
    GeckoMode(id: "AC_AND_PV", label: "Solar + Element", icon: "sun.and.horizon.fill"),
    GeckoMode(id: "AC_ONLY",   label: "Element Only",    icon: "bolt.fill"),
]

// MARK: - Persisted settings

private struct PersistedSettings: Codable {
    var targetTemp: Double
    var pvTargetTemp: Double = 60
    var mode: String = "AC_AND_PV"

    static func load(gsn: String) -> PersistedSettings? {
        guard let data = UserDefaults.standard.data(forKey: "device_settings_\(gsn)"),
              let settings = try? JSONDecoder().decode(PersistedSettings.self, from: data)
        else { return nil }
        return settings
    }

    func save(gsn: String) {
        if let data = try? JSONEncoder().encode(self) {
            UserDefaults.standard.set(data, forKey: "device_settings_\(gsn)")
        }
    }
}

// MARK: - DeviceSettingsView

struct DeviceSettingsView: View {
    let device: GeckoDevice
    /// When `true` the view is embedded directly in a parent (e.g. the Settings tab)
    /// rather than presented as a sheet, so dismiss-based navigation is skipped.
    var embedded: Bool = false
    @Environment(\.dismiss) private var dismiss

    @State private var acTargetTemp: Double
    @State private var pvTargetTemp: Double
    @State private var selectedMode: String

    @State private var isLoading    = true
    @State private var isSaving     = false
    @State private var isSavingMode = false
    @State private var hasControl   = true   // assume true until checked
    @State private var serverMode: String? = nil  // mode as loaded from server
    @State private var errorMsg: String?
    @State private var warnMsg: String?

    init(device: GeckoDevice, embedded: Bool = false) {
        self.device = device
        self.embedded = embedded
        let saved = PersistedSettings.load(gsn: device.geckoSerialNumber)
        _acTargetTemp = State(initialValue: saved?.targetTemp ?? 50)
        _pvTargetTemp = State(initialValue: saved?.pvTargetTemp ?? 60)
        _selectedMode = State(initialValue: saved?.mode ?? "AC_AND_PV")
    }

    var body: some View {
        Form {
            if isLoading {
                Section {
                    HStack {
                        Spacer()
                        ProgressView("Loading settings…")
                        Spacer()
                    }
                }
            } else {
                if !hasControl {
                    Section {
                        Label("Your account does not have control rights for this device. Contact your GeyserGecko installer.",
                              systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.orange)
                            .font(.callout)
                    }
                }

                temperatureSection
                modeSection.disabled(!hasControl)
            }

            if let warnMsg {
                Section {
                    Label(warnMsg, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.orange)
                        .font(.callout)
                }
            }
            if let errorMsg {
                Section {
                    Text(errorMsg).foregroundStyle(.red)
                }
            }

            if !embedded {
                Section {
                    Button {
                        dismiss()
                    } label: {
                        HStack {
                            Spacer()
                            Text("Back")
                            Spacer()
                        }
                    }
                    .buttonStyle(.bordered)
                    .tint(.secondary)
                    .listRowInsets(.init())
                    .listRowBackground(Color.clear)
                }
            }
        }
        .navigationTitle("Device Settings")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    Task { await loadSettings() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .disabled(isLoading || isSaving)
            }
        }
        .task { await loadSettings() }
    }

    // MARK: Sections

    private var temperatureSection: some View {
        Section {
            // AC setpoint
            Label("AC Setpoint", systemImage: "bolt.fill")
                .foregroundStyle(.red)
            ValueSlider(value: $acTargetTemp, tint: .red)
                .onChange(of: acTargetTemp) { _, new in
                    if new > pvTargetTemp { pvTargetTemp = new }
                }

            // PV setpoint
            Label("PV Setpoint", systemImage: "sun.max.fill")
                .foregroundStyle(.orange)
            ValueSlider(value: $pvTargetTemp, tint: .orange)
                .onChange(of: pvTargetTemp) { _, new in
                    if new < acTargetTemp { acTargetTemp = new }
                }

            // Explanatory summary
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 4) {
                    Image(systemName: "bolt.fill").foregroundStyle(.red).font(.caption)
                    Text("Gecko will use AC to heat to ") + Text("\(Int(acTargetTemp))°C").bold()
                }
                HStack(alignment: .top, spacing: 4) {
                    Image(systemName: "sun.max.fill").foregroundStyle(.orange).font(.caption)
                    (Text("Gecko will use PV to heat from ") + Text("\(Int(acTargetTemp))°C").bold() + Text(" to ") + Text("\(Int(pvTargetTemp))°C").bold())
                }
                HStack(alignment: .top, spacing: 4) {
                    Image(systemName: "exclamationmark.circle").foregroundStyle(.secondary).font(.caption)
                    (Text("If no AC is available, Gecko will use PV to heat to ") + Text("\(Int(pvTargetTemp))°C").bold())
                        .foregroundStyle(.secondary)
                }
            }
            .font(.footnote)
            .padding(.top, 4)
        } header: {
            HStack {
                Label("Temperature", systemImage: "thermometer.medium")
                Spacer()
                Button {
                    Task { await save() }
                } label: {
                    if isSaving {
                        ProgressView().scaleEffect(0.7)
                    } else {
                        Text("Save")
                    }
                }
                .font(.caption.bold())
                .buttonStyle(.borderedProminent)
                .disabled(isSaving || isLoading)
                .textCase(nil)
            }
        }
    }

    private var modeSection: some View {
        Section {
            Picker("Mode", selection: $selectedMode) {
                ForEach(geckoModes) { m in
                    Label(m.label, systemImage: m.icon).tag(m.id)
                }
            }
            .pickerStyle(.inline)
            .labelsHidden()
            .onChange(of: selectedMode) { _, newMode in
                Task { await saveMode(newMode) }
            }

            if isSavingMode {
                HStack {
                    Spacer()
                    ProgressView().scaleEffect(0.8)
                    Text("Saving mode…").font(.caption).foregroundStyle(.secondary)
                    Spacer()
                }
            }
        } header: {
            Label("Operating Mode", systemImage: "slider.horizontal.3")
        }
    }

    // MARK: Save

    private func loadSettings() async {
        isLoading = true
        // The API has no endpoint that returns current setpoints or mode —
        // /app/getDeviceSettings returns device profile only (panels, geyser size, etc.)
        // /app/getPerformanceHistory returns telemetry only.
        // We rely on values persisted locally after each successful save.
        // Only fetch hasControl, which gates mode changes.
        if let ctrl = try? await DeviceAPI.shared.hasControl(gsn: device.geckoSerialNumber) {
            hasControl = ctrl.controlled
        }
        // Treat persisted mode as the server baseline so we only send setMode when changed.
        serverMode = selectedMode
        isLoading = false
    }

    private func saveMode(_ mode: String) async {
        guard hasControl, mode != serverMode else { return }
        isSavingMode = true
        do {
            try await DeviceAPI.shared.setMode(gsn: device.geckoSerialNumber, geckoMode: mode)
        } catch let apiErr as APIError {
            if case .httpError(500, _) = apiErr {
                // Known server-side bug — change still takes effect
            } else {
                warnMsg = "Mode: \(apiErr.localizedDescription)"
            }
        } catch {
            warnMsg = "Mode: \(error.localizedDescription)"
        }
        serverMode = mode
        isSavingMode = false
    }

    private func persistSettings() {
        PersistedSettings(targetTemp: acTargetTemp, pvTargetTemp: pvTargetTemp, mode: selectedMode)
            .save(gsn: device.geckoSerialNumber)
    }

    private func save() async {
        isSaving = true
        errorMsg = nil
        warnMsg  = nil
        var warnings: [String] = []
        var tempSaved = false

        do {
            try await DeviceAPI.shared.setTemperature(gsn: device.geckoSerialNumber, acMax: acTargetTemp, pvMax: pvTargetTemp)
            tempSaved = true
        } catch { errorMsg = "Temperature: \(error.localizedDescription)" }

        // Only send setMode if the user actually changed it from the server value.
        // The server has a known bug that intermittently returns 500 even when the
        // mode change succeeds — the original app silently ignores it, so we do too.
        if hasControl && selectedMode != serverMode {
            do {
                try await DeviceAPI.shared.setMode(gsn: device.geckoSerialNumber, geckoMode: selectedMode)
            } catch let apiErr as APIError {
                if case .httpError(500, _) = apiErr {
                    // Known server-side bug — change still takes effect, ignore silently
                } else {
                    warnings.append("Mode: \(apiErr.localizedDescription)")
                }
            } catch {
                warnings.append("Mode: \(error.localizedDescription)")
            }
            serverMode = selectedMode   // optimistically update baseline either way
        }

        isSaving = false

        if !warnings.isEmpty {
            warnMsg = warnings.joined(separator: "\n")
        }

        if tempSaved && errorMsg == nil {
            persistSettings()
            if warnings.isEmpty && !embedded { dismiss() }
        }
    }
}

// MARK: - ValueSlider

/// A slider (40–75 °C, step 1) that shows the current value above the thumb.
private struct ValueSlider: View {
    @Binding var value: Double
    let tint: Color

    /// Fraction of the way along the track (0–1).
    private var fraction: CGFloat {
        CGFloat((value - 40) / (75 - 40))
    }

    /// X centre of the thumb within a track of the given width.
    /// iOS insets the track by ~13 pt on each side to accommodate the 28 pt thumb.
    private func thumbX(trackWidth: CGFloat) -> CGFloat {
        let inset: CGFloat = 13
        return inset + (trackWidth - inset * 2) * fraction
    }

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                // Value bubble above thumb
                Text("\(Int(value))°")
                    .font(.caption.bold())
                    .foregroundStyle(tint)
                    .position(x: thumbX(trackWidth: geo.size.width), y: 10)

                // Slider pushed down to leave room for the label
                Slider(value: $value, in: 40...75, step: 1) {
                    EmptyView()
                } minimumValueLabel: {
                    Text("40°").font(.caption2).foregroundStyle(.secondary)
                } maximumValueLabel: {
                    Text("75°").font(.caption2).foregroundStyle(.secondary)
                }
                .tint(tint)
                .padding(.top, 20)
            }
        }
        .frame(height: 64)
    }
}
