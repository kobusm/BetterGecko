import SwiftUI
import Charts

// SAST (UTC+2) — same timezone the server uses.
private let graphTimeZone = TimeZone(identifier: "Africa/Johannesburg") ?? .current

struct EnergyView: View {
    @Environment(AppState.self) private var appState

    @State private var selectedGSN: String = ""
    @State private var history: [HistoryPoint] = []
    @State private var pvUsageWeek: Int?
    @State private var acUsageWeek: Int?
    @State private var savingsWeek: Double?
    @State private var isLoading = false
    @State private var error: String?
    @State private var selectedRange: TimeRange = .oneDay
    @State private var exportFile: ExportFile?
    @State private var selectedTempDate: Date?

    var selectedDevice: GeckoDevice? {
        appState.devices.first { $0.geckoSerialNumber == selectedGSN }
            ?? appState.devices.first
    }

    var filteredHistory: [HistoryPoint] {
        let cutoff = Date().addingTimeInterval(-selectedRange.duration)
        return history.filter { $0.timestamp >= cutoff }
    }

    private var isPad: Bool {
        let idiom = UIDevice.current.userInterfaceIdiom
        return idiom == .pad || idiom == .mac
    }

    private var xAxisFontSize: CGFloat { isPad ? 14 : 10 }

    private var xAxisFormatter: DateFormatter {
        let f = DateFormatter()
        f.timeZone = graphTimeZone
        f.locale = Locale(identifier: "en_US_POSIX")
        switch selectedRange {
        case .oneHour, .sixHours, .oneDay: f.dateFormat = "HH:mm"
        case .threeDays:                   f.dateFormat = "EEE HH:mm"
        }
        return f
    }

    var body: some View {
        NavigationStack {
            Group {
                if appState.devices.isEmpty {
                    ContentUnavailableView("No Devices",
                        systemImage: "chart.line.uptrend.xyaxis",
                        description: Text("Add a device on the Summary tab first."))
                } else {
                    content
                }
            }
            .navigationTitle("Graphs")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { Task { await load() } } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .disabled(isLoading)
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        if let gsn = selectedDevice?.geckoSerialNumber {
                            exportFile = ExportFile(url: buildCSV(gsn: gsn))
                        }
                    } label: {
                        Image(systemName: "square.and.arrow.up")
                    }
                    .disabled(filteredHistory.isEmpty)
                }
            }
        }
    }

    // MARK: - Content

    private var content: some View {
        ScrollView {
            VStack(spacing: 20) {
                // Device picker (only shown when multiple devices)
                if appState.devices.count > 1 {
                    Picker("Device", selection: $selectedGSN) {
                        ForEach(appState.devices) { d in
                            Text(d.displayName).tag(d.geckoSerialNumber)
                        }
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal)
                }

                // Time range picker
                Picker("Range", selection: $selectedRange) {
                    ForEach(TimeRange.allCases) { r in Text(r.rawValue).tag(r) }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)

                if isLoading {
                    ProgressView().padding(.top, 40)
                } else if let error {
                    ContentUnavailableView("Error",
                        systemImage: "exclamationmark.triangle",
                        description: Text(error))
                        .padding(.top, 20)
                } else if filteredHistory.isEmpty {
                    ContentUnavailableView("No Data",
                        systemImage: "chart.line.downtrend.xyaxis",
                        description: Text("No readings in the selected time range."))
                        .padding(.vertical, 20)
                } else {
                    Group {
                        temperatureChart
                        pvOnChart
                        acOnChart
                        pvVoltageChart
                        pvInPowerChart
                        utilityVoltageChart
                    }
                    .padding(.horizontal)
                }
            }
            .padding(.vertical)
        }
        .dynamicTypeSize(isPad ? .xxxLarge : .large)
        .onChange(of: appState.devices) { _, _ in
            if selectedGSN.isEmpty, let first = appState.devices.first {
                selectedGSN = first.geckoSerialNumber
            }
        }
        .onAppear {
            if selectedGSN.isEmpty, let first = appState.devices.first {
                selectedGSN = first.geckoSerialNumber
            }
            Task { await load() }
        }
        .refreshable { await load() }
        .sheet(item: $exportFile) { file in CSVExporter(url: file.url) }
    }

    // MARK: - Axis helpers

    private func axisValues() -> [Date] {
        let ts = filteredHistory.map { $0.timestamp }
        guard let first = ts.first, let last = ts.last, first < last else { return ts }
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = graphTimeZone
        var values: [Date] = [first]
        var current = first
        while let next = cal.date(byAdding: selectedRange.xStride,
                                   value: selectedRange.xStrideCount, to: current),
              next < last {
            values.append(next)
            current = next
        }
        if values.last != last { values.append(last) }
        return values
    }

    // MARK: - Charts

    /// Nearest history point to a given date (for chart selection callout).
    private func nearestTempPoint(to date: Date) -> HistoryPoint? {
        filteredHistory.min(by: {
            abs($0.timestamp.timeIntervalSince(date)) < abs($1.timestamp.timeIntervalSince(date))
        })
    }

    /// Y-axis domain rounded outward to the nearest 5 °C, with ±10 °C padding.
    private var temperatureYDomain: ClosedRange<Double> {
        let temps = filteredHistory.compactMap { $0.temperature }
        guard let lo = temps.min(), let hi = temps.max() else { return 0...100 }
        let yMin = (floor((lo - 10) / 5) * 5)
        let yMax = (ceil ((hi + 10) / 5) * 5)
        return yMin...yMax
    }

    /// Explicit 5 °C tick marks that span the domain.
    private var temperatureYStride: [Double] {
        let d = temperatureYDomain
        return stride(from: d.lowerBound, through: d.upperBound, by: 5).map { $0 }
    }

    private var temperatureChart: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Temperature").font(.headline)
                Spacer()
                // Show selected reading in the header; falls back to count when nothing selected
                if let sel = selectedTempDate,
                   let nearest = nearestTempPoint(to: sel),
                   let temp = nearest.temperature {
                    VStack(alignment: .trailing, spacing: 1) {
                        Text(String(format: "%.1f°C", temp))
                            .font(.caption.bold())
                            .foregroundStyle(.orange)
                        Text(xAxisFormatter.string(from: nearest.timestamp))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                } else {
                    Text("\(filteredHistory.count) readings").font(.caption).foregroundStyle(.secondary)
                }
            }
            Chart(filteredHistory) { point in
                LineMark(x: .value("Time", point.timestamp), y: .value("°C", point.temperature ?? 0))
                    .foregroundStyle(.orange)
                    .lineStyle(StrokeStyle(lineWidth: 2))

                // Vertical rule at the selected point (no annotation — value shown in header above)
                if let sel = selectedTempDate,
                   let nearest = nearestTempPoint(to: sel) {
                    RuleMark(x: .value("Selected", nearest.timestamp))
                        .foregroundStyle(.secondary.opacity(0.4))
                        .lineStyle(StrokeStyle(lineWidth: 1, dash: [4]))
                }
            }
            .chartXSelection(value: $selectedTempDate)
            .chartYScale(domain: temperatureYDomain)
            .chartXAxis {
                let vals = axisValues()
                AxisMarks(values: vals) { value in
                    AxisGridLine()
                    AxisValueLabel {
                        if let date = value.as(Date.self) {
                            Text(xAxisFormatter.string(from: date))
                                .font(.system(size: xAxisFontSize))
                        }
                    }
                }
            }
            .chartYAxis {
                AxisMarks(position: .leading, values: temperatureYStride) { v in
                    AxisGridLine()
                    AxisValueLabel { Text("\(v.as(Double.self).map { Int($0) } ?? 0)°") }
                }
            }
            .frame(height: 200)
            .clipped()
        }
        .padding()
        .background(.quaternary, in: RoundedRectangle(cornerRadius: 16))
    }

    private var pvVoltageChart: some View {
        intChart(title: "PV Voltage", unit: "V", color: .orange,
                 data: filteredHistory.map { ($0.timestamp, $0.pvVoltage) },
                 emptyMessage: "No PV voltage data in this range")
    }

    private var utilityVoltageChart: some View {
        intChart(title: "MPPT Voltage", unit: "V", color: .blue,
                 data: filteredHistory.map { ($0.timestamp, $0.mpptVoltage) },
                 emptyMessage: "No MPPT voltage data in this range")
    }

    private var pvInPowerChart: some View {
        intChart(title: "PV Input Power", unit: "W", color: .yellow,
                 data: filteredHistory.map { ($0.timestamp, $0.pvInPower) },
                 emptyMessage: "No PV input power data in this range")
    }

    private var pvOnChart: some View {
        binaryChart(title: "PV Active", color: .orange,
                    data: filteredHistory.map { ($0.timestamp, $0.pvOn) })
    }

    private var acOnChart: some View {
        binaryChart(title: "AC Active", color: .red,
                    data: filteredHistory.map { ($0.timestamp, $0.acOn) })
    }

    private func intChart(title: String, unit: String, color: Color,
                          data: [(Date, Int?)], emptyMessage: String) -> some View {
        let filtered = data.filter { ($0.1 ?? 0) > 0 }
        let vals = axisValues()
        return VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline)
            if filtered.isEmpty {
                Text(emptyMessage).font(.caption).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center).padding()
            } else {
                Chart(filtered.indices, id: \.self) { i in
                    LineMark(x: .value("Time", filtered[i].0), y: .value(unit, filtered[i].1 ?? 0))
                        .foregroundStyle(color)
                        .lineStyle(StrokeStyle(lineWidth: 2))
                }
                .chartXAxis {
                    AxisMarks(values: vals) { value in
                        AxisGridLine()
                        AxisValueLabel {
                            if let date = value.as(Date.self) {
                                Text(xAxisFormatter.string(from: date))
                                    .font(.system(size: xAxisFontSize))
                            }
                        }
                    }
                }
                .chartYAxis {
                    AxisMarks(position: .leading) { v in
                        AxisGridLine()
                        AxisValueLabel { Text("\(v.as(Int.self) ?? 0) \(unit)") }
                    }
                }
                .frame(height: 150)
            }
        }
        .padding()
        .background(.quaternary, in: RoundedRectangle(cornerRadius: 16))
    }

    private func binaryChart(title: String, color: Color, data: [(Date, Int)]) -> some View {
        let vals = axisValues()
        return VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline)
            Chart(data.indices, id: \.self) { i in
                RectangleMark(
                    xStart: .value("Time", i > 0 ? data[i - 1].0 : data[i].0),
                    xEnd:   .value("Time", data[i].0),
                    yStart: .value("State", 0),
                    yEnd:   .value("State", data[i].1)
                )
                .foregroundStyle(color.opacity(0.35))
                LineMark(x: .value("Time", data[i].0), y: .value("State", data[i].1))
                    .foregroundStyle(color)
                    .lineStyle(StrokeStyle(lineWidth: 2))
                    .interpolationMethod(.stepStart)
            }
            .chartXAxis {
                AxisMarks(values: vals) { value in
                    AxisGridLine()
                    AxisValueLabel {
                        if let date = value.as(Date.self) {
                            Text(xAxisFormatter.string(from: date))
                                .font(.system(size: xAxisFontSize))
                        }
                    }
                }
            }
            .chartYScale(domain: 0...1)
            .chartYAxis {
                AxisMarks(position: .leading, values: [0, 1]) { value in
                    AxisGridLine()
                    AxisValueLabel {
                        Text(value.as(Int.self) == 1 ? "On" : "Off").font(.caption)
                    }
                }
            }
            .frame(height: 100)
        }
        .padding()
        .background(.quaternary, in: RoundedRectangle(cornerRadius: 16))
    }

    // MARK: - Data

    private func load() async {
        guard !selectedGSN.isEmpty else { return }
        isLoading = true
        error = nil
        do {
            let response  = try await DeviceAPI.shared.getPerformanceHistory(gsn: selectedGSN)
            history       = response.toHistoryPoints()
            pvUsageWeek   = response.pvUsageWeek
            acUsageWeek   = response.acUsageWeek
            savingsWeek   = response.savingsWeek
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }

    private func buildCSV(gsn: String) -> URL {
        let fmt = ISO8601DateFormatter()
        var csv = "Timestamp,Temperature (°C),MPPT Voltage (V),PV On,AC On,State\n"
        for p in filteredHistory {
            let ts    = fmt.string(from: p.timestamp)
            let temp  = p.temperature.map { String(format: "%.4f", $0) } ?? ""
            let mpptV = p.mpptVoltage.map { "\($0)" } ?? ""
            let st    = p.state ?? ""
            csv += "\(ts),\(temp),\(mpptV),\(p.pvOn),\(p.acOn),\(st)\n"
        }
        let name = "\(gsn)_\(selectedRange.rawValue).csv"
        let url  = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        try? csv.write(to: url, atomically: true, encoding: .utf8)
        return url
    }
}
