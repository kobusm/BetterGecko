import SwiftUI

@main
struct BetterGeckoApp: App {
    @State private var appState = AppState()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        NotificationManager.shared.configure()
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

