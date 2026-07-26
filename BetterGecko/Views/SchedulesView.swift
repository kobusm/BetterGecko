import SwiftUI

struct SchedulesView: View {
    let device: GeckoDevice

    @State private var schedules: [ModeSchedule] = []
    @State private var editing: ModeSchedule?
    @State private var showEditor = false

    var body: some View {
        List {
            Section {
                if schedules.isEmpty {
                    Text("No schedules yet. Add one to switch mode automatically at a set time.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(schedules) { s in
                        Button {
                            editing = s
                            showEditor = true
                        } label: {
                            scheduleRow(s)
                        }
                        .tint(.primary)
                    }
                    .onDelete(perform: delete)
                }
            } footer: {
                Text("iOS can't run mode changes in the background at an exact time. At each scheduled time a notification appears — tap it to apply. When you open BetterGecko it also catches up to whichever schedule should be active now.")
            }
        }
        .navigationTitle("Mode Schedules")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    editing = nil
                    showEditor = true
                } label: { Image(systemName: "plus") }
            }
        }
        .sheet(isPresented: $showEditor) {
            ScheduleEditor(existing: editing) { result in
                upsert(result)
            }
        }
        .onAppear { schedules = ScheduleStore.load(gsn: device.geckoSerialNumber) }
    }

    private func scheduleRow(_ s: ModeSchedule) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(s.timeLabel).font(.title3.monospacedDigit().bold())
                Text(s.modeLabel).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Toggle("", isOn: Binding(
                get: { s.enabled },
                set: { newValue in
                    if let i = schedules.firstIndex(where: { $0.id == s.id }) {
                        schedules[i].enabled = newValue
                        persist()
                    }
                }
            ))
            .labelsHidden()
        }
    }

    private func upsert(_ s: ModeSchedule) {
        if let i = schedules.firstIndex(where: { $0.id == s.id }) {
            schedules[i] = s
        } else {
            schedules.append(s)
        }
        schedules.sort { ($0.hour, $0.minute) < ($1.hour, $1.minute) }
        persist()
        Task { await NotificationManager.shared.requestAuthorization() }
    }

    private func delete(_ offsets: IndexSet) {
        schedules.remove(atOffsets: offsets)
        persist()
    }

    private func persist() {
        ScheduleStore.save(schedules, gsn: device.geckoSerialNumber, deviceName: device.displayName)
    }
}

// MARK: - Editor

private struct ScheduleEditor: View {
    let existing: ModeSchedule?
    let onSave: (ModeSchedule) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var time: Date
    @State private var mode: String

    init(existing: ModeSchedule?, onSave: @escaping (ModeSchedule) -> Void) {
        self.existing = existing
        self.onSave = onSave
        var comps = DateComponents()
        comps.hour = existing?.hour ?? 6
        comps.minute = existing?.minute ?? 0
        _time = State(initialValue: Calendar.current.date(from: comps) ?? Date())
        _mode = State(initialValue: existing?.mode ?? "PV_ONLY")
    }

    var body: some View {
        NavigationStack {
            Form {
                DatePicker("Time", selection: $time, displayedComponents: .hourAndMinute)

                Picker("Mode", selection: $mode) {
                    ForEach(ModeSchedule.allModes, id: \.self) { m in
                        Text(ModeSchedule.label(for: m)).tag(m)
                    }
                }
            }
            .navigationTitle(existing == nil ? "New Schedule" : "Edit Schedule")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        let c = Calendar.current.dateComponents([.hour, .minute], from: time)
                        var s = existing ?? ModeSchedule(hour: 0, minute: 0, mode: mode)
                        s.hour = c.hour ?? 0
                        s.minute = c.minute ?? 0
                        s.mode = mode
                        onSave(s)
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }
}
