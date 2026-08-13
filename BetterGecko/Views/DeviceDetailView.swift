import SwiftUI

// The server is hosted in South Africa; timestamps are always SAST (UTC+2, no DST).
// (TimeRange enum is defined here and shared with EnergyView.)
private let serverTimeZone = TimeZone(identifier: "Africa/Johannesburg") ?? .current

enum TimeRange: String, CaseIterable, Identifiable {
    case oneHour   = "1H"
    case sixHours  = "6H"
    case oneDay    = "24H"
    case threeDays = "3D"

    var id: String { rawValue }

    var duration: TimeInterval {
        switch self {
        case .oneHour:   return 3600
        case .sixHours:  return 6 * 3600
        case .oneDay:    return 24 * 3600
        case .threeDays: return 3 * 24 * 3600
        }
    }

    var xStride: Calendar.Component {
        switch self {
        case .oneHour:   return .minute
        case .sixHours:  return .hour
        case .oneDay:    return .hour
        case .threeDays: return .day
        }
    }

    var xStrideCount: Int {
        switch self {
        case .oneHour:   return 15
        case .sixHours:  return 1
        case .oneDay:    return 6
        case .threeDays: return 1
        }
    }

    var xFormat: Date.FormatStyle {
        switch self {
        case .oneHour:   return Date.FormatStyle(timeZone: serverTimeZone).hour().minute()
        case .sixHours:  return Date.FormatStyle(timeZone: serverTimeZone).hour().minute()
        case .oneDay:    return Date.FormatStyle(timeZone: serverTimeZone).hour().minute()
        case .threeDays: return Date.FormatStyle(timeZone: serverTimeZone).month().day().hour()
        }
    }
}

struct DeviceDetailView: View {
    let device: GeckoDevice
    @Environment(AppState.self) private var appState

    @State private var history: [HistoryPoint] = []
    @State private var pvUsageWeek: Int?
    @State private var acUsageWeek: Int?
    @State private var isLoading = true
    @State private var error: String?
    @State private var showRename = false
    @State private var newName = ""
    @State private var showDeviceSettings = false
    @State private var selectedRange: TimeRange = .oneDay
    @State private var exportFile: ExportFile?

    var latest: HistoryPoint? { history.last }

    var filteredHistory: [HistoryPoint] {
        let cutoff = Date().addingTimeInterval(-selectedRange.duration)
        return history.filter { $0.timestamp >= cutoff }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                if isLoading {
                    ProgressView("Loading…").padding(.top, 60)
                } else if let error {
                    ContentUnavailableView("Error", systemImage: "exclamationmark.triangle", description: Text(error))
                } else {
                    // Time range picker (drives energy-total calculations in the stats grid)
                    Picker("Range", selection: $selectedRange) {
                        ForEach(TimeRange.allCases) { r in
                            Text(r.rawValue).tag(r)
                        }
                    }
                    .pickerStyle(.segmented)

                    statsGrid
                }
            }
            .padding()
        }
        .navigationTitle(device.displayName)
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    Task { await load() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .disabled(isLoading)
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    Button {
                        exportFile = ExportFile(url: buildCSV())
                    } label: {
                        Label("Export CSV", systemImage: "square.and.arrow.up")
                    }
                    .disabled(filteredHistory.isEmpty)

                    Divider()

                    Button {
                        showDeviceSettings = true
                    } label: {
                        Label("Device Settings", systemImage: "gearshape")
                    }

                    Divider()

                    Button("Rename") {
                        newName = device.friendlyName ?? ""
                        showRename = true
                    }
                    Button("Remove Device", role: .destructive) {
                        appState.removeDevice(gsn: device.geckoSerialNumber)
                    }
                } label: {
                    Image(systemName: "line.3.horizontal")
                }
            }
        }
        .alert("Rename Device", isPresented: $showRename) {
            TextField("Name", text: $newName)
            Button("Save") { appState.renameDevice(gsn: device.geckoSerialNumber, name: newName) }
            Button("Cancel", role: .cancel) {}
        }
        .sheet(item: $exportFile) { file in
            CSVExporter(url: file.url)
        }
        .sheet(isPresented: $showDeviceSettings) {
            DeviceSettingsView(device: device)
        }
        .task { await load() }
        .refreshable { await load() }
    }

    // MARK: - Stats

    /// Time (in seconds) spent in each state over the currently-selected time window.
    private var stateTotals: (solarSecs: Double, elementSecs: Double, idleSecs: Double) {
        var solar   = 0.0
        var element = 0.0
        var idle    = 0.0
        let pts = filteredHistory
        for i in 0 ..< pts.count - 1 {
            let dt = pts[i + 1].timestamp.timeIntervalSince(pts[i].timestamp)
            switch GeckoState(rawString: pts[i].state) {
            case .solarHeating:   solar   += dt
            case .elementHeating: element += dt
            default:              idle    += dt
            }
        }
        return (solar, element, idle)
    }

    private var statsGrid: some View {
        let s = stateTotals
        return LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
            StatCard(title: "Temperature",      value: latest?.temperature.map { String(format: "%.1f°C", $0) } ?? "—",  icon: "thermometer.medium",      color: tempColor)
            StatCard(title: "State",            value: GeckoState(rawString: latest?.state).label,                        icon: GeckoState(rawString: latest?.state).symbolName, color: .orange)
            StatCard(title: "PV Voltage",       value: latest?.pvVoltage.map   { "\($0) V" } ?? "—",                     icon: "sun.max.fill",             color: .orange)
            StatCard(title: "Mode",             value: modeLabel,                                                          icon: "slider.horizontal.3",      color: .green)
            StatCard(title: "Solar Active",     value: formatDuration(s.solarSecs),                                       icon: "sun.max.fill",             color: .orange)
            StatCard(title: "AC Active",         value: formatDuration(s.elementSecs),                                     icon: "bolt.fill",                color: .red)
            StatCard(title: "Idle / Off",       value: formatDuration(s.idleSecs),                                        icon: "moon.fill",                color: .blue)
            StatCard(title: "Solar (Week)",      value: pvUsageWeek.map  { formatDuration(Double($0) * 60) } ?? "—",    icon: "sun.max.fill",             color: .orange)
            StatCard(title: "AC (Week)",        value: acUsageWeek.map  { formatDuration(Double($0) * 60) } ?? "—",    icon: "bolt.fill",                color: .red)
        }
    }

    // MARK: - Helpers

    private var modeLabel: String {
        struct _S: Decodable { var mode: String = "AC_AND_PV" }
        let mode: String
        if let data = UserDefaults.standard.data(forKey: "device_settings_\(device.geckoSerialNumber)"),
           let s = try? JSONDecoder().decode(_S.self, from: data) {
            mode = s.mode
        } else {
            mode = "AC_AND_PV"
        }
        switch mode {
        case "ALL_OFF":   return "Off"
        case "PV_ONLY":   return "Solar Only"
        case "AC_AND_PV": return "Solar + AC"
        case "AC_ONLY":   return "AC Only"
        default:          return mode
        }
    }

    private var tempColor: Color {
        guard let t = latest?.temperature else { return .secondary }
        return t >= 65 ? .red : t <= 30 ? .blue : .orange
    }

    private func formatDuration(_ seconds: Double) -> String {
        let h = Int(seconds) / 3600
        let m = (Int(seconds) % 3600) / 60
        if h > 0 { return "\(h)h \(m)m" }
        return "\(m)m"
    }

    private func load() async {
        isLoading = true
        error = nil
        do {
            let response = try await DeviceAPI.shared.getPerformanceHistory(gsn: device.geckoSerialNumber)
            history     = response.toHistoryPoints()
            pvUsageWeek = response.pvUsageWeek
            acUsageWeek = response.acUsageWeek
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }

    // MARK: - CSV export

    private func buildCSV() -> URL {
        let fmt = ISO8601DateFormatter()
        var csv = "Timestamp,Temperature (°C),MPPT Voltage (V),PV On,AC On,State\n"
        for p in filteredHistory {
            let ts    = fmt.string(from: p.timestamp)
            let temp  = p.temperature.map { String(format: "%.4f", $0) } ?? ""
            let mpptV = p.mpptVoltage.map { "\($0)" } ?? ""
            let st    = p.state ?? ""
            csv += "\(ts),\(temp),\(mpptV),\(p.pvOn),\(p.acOn),\(st)\n"
        }

        let name = "\(device.geckoSerialNumber)_\(selectedRange.rawValue).csv"
        let url  = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        try? csv.write(to: url, atomically: true, encoding: .utf8)
        return url
    }
}

// MARK: - CSV Export helpers

struct ExportFile: Identifiable {
    let id = UUID()
    let url: URL
}

/// Uses UIDocumentPickerViewController (Save to Files) instead of UIActivityViewController
/// to avoid the SHKSharePlaySharingService Metal crash on Mac Catalyst.
struct CSVExporter: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forExporting: [url])
        picker.shouldShowFileExtensions = true
        return picker
    }

    func updateUIViewController(_ vc: UIDocumentPickerViewController, context: Context) {}
}

// MARK: - StatCard

struct StatCard: View {
    let title: String
    let value: String
    let icon: String
    let color: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.caption2)
                    .foregroundStyle(color)
                Text(title)
                    .font(.caption2)
                    .minimumScaleFactor(0.7)
                    .lineLimit(1)
                    .foregroundStyle(.secondary)
            }
            Text(value)
                .font(.footnote.bold())
                .minimumScaleFactor(0.6)
                .lineLimit(1)
                .foregroundStyle(color)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.quaternary, in: RoundedRectangle(cornerRadius: 16))
    }
}
