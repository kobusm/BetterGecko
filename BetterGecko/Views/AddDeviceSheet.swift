import SwiftUI

struct AddDeviceSheet: View {
    @Environment(AppState.self) private var appState
    @Environment(\.dismiss) private var dismiss

    @State private var gsn = ""
    @State private var name = ""
    @State private var isVerifying = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("GSN (Gecko Serial Number)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        TextField("e.g. fce8c0de16e0", text: $gsn)
                            .font(.system(.body, design: .monospaced))
                            .autocapitalization(.none)
                            .autocorrectionDisabled()
                    }
                    TextField("Device name (optional)", text: $name)
                } header: {
                    Text("Device details")
                } footer: {
                    Text("Find the GSN on your GeyserGecko unit's display screen — it's the short hex code, not the longer SN.")
                }

                if let error = errorMessage {
                    Section {
                        Text(error)
                            .foregroundStyle(.red)
                            .font(.caption)
                    }
                }
            }
            .navigationTitle("Add Device")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") { addDevice() }
                        .disabled(gsn.trimmingCharacters(in: .whitespaces).isEmpty || isVerifying)
                        .overlay {
                            if isVerifying { ProgressView().scaleEffect(0.7) }
                        }
                }
            }
        }
    }

    private func addDevice() {
        let serial = gsn.trimmingCharacters(in: .whitespaces).lowercased().replacingOccurrences(of: ":", with: "")
        guard !serial.isEmpty else { return }

        isVerifying = true
        errorMessage = nil

        Task {
            do {
                // Verify the GSN is reachable
                _ = try await DeviceAPI.shared.getPerformanceHistory(gsn: serial)
                let device = GeckoDevice(geckoSerialNumber: serial, friendlyName: name.trimmingCharacters(in: .whitespaces).isEmpty ? nil : name.trimmingCharacters(in: .whitespaces))
                appState.addDevice(device)
                dismiss()
            } catch {
                errorMessage = error.localizedDescription
            }
            isVerifying = false
        }
    }
}
