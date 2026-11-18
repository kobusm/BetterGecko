import SwiftUI

@main
struct BetterGeckoApp: App {
    @State private var appState = AppState()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        NotificationManager.shared.configure()
        Keychain.migrateAccessibility()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(appState)
        }
        .onChange(of: scenePhase) { _, phase in
            // Catch up to the active schedule whenever the app comes to the foreground.
            if phase == .active {
                Task { await appState.applyActiveSchedulesIfNeeded() }
            }
            // Re-request the daily AC-hours refresh each time the app leaves the screen.
            if phase == .background { ACRefresh.schedule() }
        }
        // iOS launches the app in the background (no UI) and runs this when it decides to.
        // Schedule the next one first, so running out of background time doesn't drop it.
        .backgroundTask(.appRefresh(ACRefresh.taskID)) {
            ACRefresh.schedule()
            await ACRefresh.run()
        }
    }
}

struct RootView: View {
    @Environment(AppState.self) private var appState

    var body: some View {
        Group {
            if appState.isLoadingSession {
                SplashView()
            } else if appState.isLoggedIn {
                MainTabView()
            } else {
                LoginView()
            }
        }
        .task {
            await appState.restoreSession()
            await appState.applyActiveSchedulesIfNeeded()
        }
    }
}

