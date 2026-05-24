import SwiftUI

struct MainTabView: View {
    @State private var selectedTab = 0
    @State private var statsNavPath = NavigationPath()

    var body: some View {
        TabView(selection: $selectedTab) {
            InfoView()
                .tabItem { Label("Summary", systemImage: "info.circle.fill") }
                .tag(0)

            DashboardView(navPath: $statsNavPath)
                .tabItem { Label("Stats", systemImage: "thermometer.medium") }
                .tag(1)

            EnergyView()
                .tabItem { Label("Graphs", systemImage: "chart.line.uptrend.xyaxis") }
                .tag(2)

            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape.fill") }
                .tag(3)

            ProfileView()
                .tabItem { Label("Profile", systemImage: "person.crop.circle") }
                .tag(4)
        }
        .tint(.green)
        .onChange(of: selectedTab) { _, new in
            // Pop Stats to root whenever the tab is tapped
            if new == 1 { statsNavPath = NavigationPath() }
        }
    }
}
