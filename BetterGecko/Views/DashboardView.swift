import SwiftUI

struct DashboardView: View {
    @Environment(AppState.self) private var appState
    @Binding var navPath: NavigationPath
    @State private var showAddDevice = false
    @State private var deviceReadings: [String: DeviceReading] = [:]
    @State private var isRefreshing = false

    var body: some View {
        NavigationStack(path: $navPath) {
            Group {
                if appState.devices.isEmpty {
                    emptyState
                        .navigationTitle("Stats")
                        .toolbar {
                            ToolbarItem(placement: .navigationBarTrailing) {
                                Button { showAddDevice = true } label: { Image(systemName: "plus") }
                            }
                        }
                } else if appState.devices.count == 1, let device = appState.devices.first {
                    // Single device — skip the list and show detail directly
                    DeviceDetailView(device: device)
                } else {
                    deviceList
                        .navigationTitle("Stats")
                        .toolbar {
                            ToolbarItem(placement: .navigationBarTrailing) {
                                Button { showAddDevice = true } label: { Image(systemName: "plus") }
                            }
                            ToolbarItem(placement: .navigationBarLeading) {
                                Button { Task { await refresh() } } label: {
                                    Image(systemName: "arrow.clockwise")
                                        .rotationEffect(.degrees(isRefreshing ? 360 : 0))
                                        .animation(isRefreshing ? .linear(duration: 1).repeatForever(autoreverses: false) : .default, value: isRefreshing)
                                }
                            }
                        }
                        .task { await refresh() }
                        .refreshable { await refresh() }
                }
            }
            .sheet(isPresented: $showAddDevice) {
                AddDeviceSheet()
            }
        }
    }

    private var emptyState: some View {
        ContentUnavailableView {
            Label("No Devices", systemImage: "lizard")
        } description: {
            Text("Add your GeyserGecko device using the GSN printed on the unit's screen.")
        } actions: {
            Button("Add Device") { showAddDevice = true }
                .buttonStyle(.borderedProminent)
                .tint(.green)
        }
    }

    private var deviceList: some View {
        List {
            ForEach(appState.devices) { device in
                NavigationLink(value: device) {
                    DeviceRowView(device: device, reading: deviceReadings[device.geckoSerialNumber])
                }
            }
            .onDelete { indexSet in
                indexSet.forEach { i in
                    appState.removeDevice(gsn: appState.devices[i].geckoSerialNumber)
                }
            }
        }
        .navigationDestination(for: GeckoDevice.self) { device in
            DeviceDetailView(device: device)
        }
        .listStyle(.insetGrouped)
    }

    private func refresh() async {
        isRefreshing = true
        await withTaskGroup(of: Void.self) { group in
            for device in appState.devices {
                group.addTask {
                    if let reading = try? await fetchReading(gsn: device.geckoSerialNumber) {
                        await MainActor.run {
                            deviceReadings[device.geckoSerialNumber] = reading
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

struct DeviceReading {
    let temperature: Double?
    let state: GeckoState
    let pvVoltage: Int?
}

struct DeviceRowView: View {
    let device: GeckoDevice
    let reading: DeviceReading?

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(stateColor.opacity(0.15))
                    .frame(width: 48, height: 48)
                Image(systemName: reading?.state.symbolName ?? "thermometer.medium")
                    .font(.system(size: 20))
                    .foregroundStyle(stateColor)
            }

            VStack(alignment: .leading, spacing: 3) {
                Text(device.displayName)
                    .font(.body.bold())
                Text(reading?.state.label ?? "Loading…")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            if let temp = reading?.temperature {
                VStack(alignment: .trailing, spacing: 2) {
                    Text(String(format: "%.1f°", temp))
                        .font(.title2.bold())
                        .foregroundStyle(tempColor(temp))
                    if let pv = reading?.pvVoltage, pv > 0 {
                        Text("\(pv)V PV")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
            } else {
                ProgressView().scaleEffect(0.7)
            }
        }
        .padding(.vertical, 4)
    }

    private var stateColor: Color {
        switch reading?.state {
        case .solarHeating: return .orange
        case .elementHeating: return .red
        case .idle: return .blue
        default: return .secondary
        }
    }

    private func tempColor(_ t: Double) -> Color {
        t >= 65 ? .red : t <= 30 ? .blue : .orange
    }
}
