import SwiftUI

struct SettingsView: View {
    @Environment(AppState.self) private var appState
    @State private var selectedGSN: String = ""

    var selectedDevice: GeckoDevice? {
        appState.devices.first { $0.geckoSerialNumber == selectedGSN }
            ?? appState.devices.first
    }

    var body: some View {
        NavigationStack {
            Group {
                if appState.devices.isEmpty {
                    ContentUnavailableView(
                        "No Devices",
                        systemImage: "gearshape",
                        description: Text("Add a device on the Summary tab first.")
                    )
                } else if let device = selectedDevice {
                    VStack(spacing: 0) {
                        if appState.devices.count > 1 {
                            Picker("Device", selection: $selectedGSN) {
                                ForEach(appState.devices) { d in
                                    Text(d.displayName).tag(d.geckoSerialNumber)
                                }
                            }
                            .pickerStyle(.segmented)
                            .padding()
                            .background(Color(.systemGroupedBackground))
                        }
                        DeviceSettingsView(device: device, embedded: true)
                    }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear {
                if selectedGSN.isEmpty, let first = appState.devices.first {
                    selectedGSN = first.geckoSerialNumber
                }
            }
            .onChange(of: appState.devices) { _, _ in
                if selectedGSN.isEmpty, let first = appState.devices.first {
                    selectedGSN = first.geckoSerialNumber
                }
            }
        }
    }
}
