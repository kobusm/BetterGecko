import SwiftUI

struct InfoView: View {
    @Environment(AppState.self) private var appState

    @State private var readings: [String: DeviceReading] = [:]
    @State private var isRefreshing = false

    var body: some View {
        NavigationStack {
            Group {
                if appState.devices.isEmpty {
                    ContentUnavailableView {
                        Label("No Devices", systemImage: "lizard")
                    } description: {
                        Text("Add a device on the Summary tab first.")
                    }
                } else {
                    ScrollView {
                        VStack(spacing: 16) {
                            ForEach(appState.devices) { device in
                                InfoCard(
                                    device: device,
                                    reading: readings[device.geckoSerialNumber]
                                )
                            }
                        }
                        .padding()
                    }
                    .refreshable { await refresh() }
                }
            }
            .navigationTitle("Summary")
            .toolbar {
                if !appState.devices.isEmpty {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button { Task { await refresh() } } label: {
                            Image(systemName: "arrow.clockwise")
                                .rotationEffect(.degrees(isRefreshing ? 360 : 0))
                                .animation(
                                    isRefreshing
                                        ? .linear(duration: 1).repeatForever(autoreverses: false)
                                        : .default,
                                    value: isRefreshing
                                )
                        }
                        .disabled(isRefreshing)
                    }
                }
            }
        }
        .task { await refresh() }
    }

    private func refresh() async {
        isRefreshing = true
        await withTaskGroup(of: Void.self) { group in
            for device in appState.devices {
                group.addTask {
                    if let reading = try? await fetchReading(gsn: device.geckoSerialNumber) {
                        await MainActor.run {
                            readings[device.geckoSerialNumber] = reading
                        }
                    }
                }
            }
        }
        isRefreshing = false
    }

    private func fetchReading(gsn: String) async throws -> DeviceReading {
        let history = try await DeviceAPI.shared.getPerformanceHistory(gsn: gsn)
        return DeviceReading(
            temperature: history.latestTemperature,
            state: GeckoState(rawString: history.latestState),
            pvVoltage: history.latestPVVoltage
        )
    }
}

// MARK: - InfoCard

private struct InfoCard: View {
    let device: GeckoDevice
    let reading: DeviceReading?

    private var state: GeckoState { reading?.state ?? .unknown }

    private var stateColor: Color {
        switch state {
        case .solarHeating:   return .orange
        case .elementHeating: return .red
        case .idle:           return .blue
        case .off:            return .secondary
        default:              return .secondary
        }
    }

    private var tempColor: Color {
        guard let t = reading?.temperature else { return .primary }
        return t >= 65 ? .red : t <= 30 ? .blue : .orange
    }

    var body: some View {
        VStack(spacing: 20) {
            // Device name
            if let name = device.friendlyName, !name.isEmpty {
                Text(name)
                    .font(.headline)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            HStack(alignment: .center, spacing: 24) {
                // Temperature
                VStack(spacing: 4) {
                    if let temp = reading?.temperature {
                        Text(String(format: "%.1f°", temp))
                            .font(.system(size: 48, weight: .bold, design: .rounded))
                            .foregroundStyle(tempColor)
                    } else {
                        ProgressView()
                            .scaleEffect(1.5)
                            .frame(height: 56)
                    }
                    Label("Temperature", systemImage: "thermometer.medium")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)

                Divider().frame(height: 80)

                // State
                VStack(spacing: 8) {
                    Image(systemName: state.symbolName)
                        .font(.system(size: 44))
                        .foregroundStyle(stateColor)
                    Text(state.label)
                        .font(.title3.bold())
                        .foregroundStyle(stateColor)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
            }
        }
        .padding(20)
        .background(.quaternary, in: RoundedRectangle(cornerRadius: 20))
    }
}
